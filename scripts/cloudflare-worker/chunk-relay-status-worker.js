import chunkRelayWorker, { ChunkRelayHub } from "./chunk-relay-worker.js";

const REVISION = "chunk-relay-mtproto-v10";
const HUB_REVISION = "relay-hub-v1";
const RELAY_ERROR_HEADER = "X-Tgws-Relay-Error";

function errorHeaders(relayError, source = null) {
  const headers = new Headers(source?.headers || undefined);
  headers.set("Cache-Control", "no-store");
  headers.set("X-Tgws-Chunk-Relay-Revision", REVISION);
  headers.set("X-Tgws-Relay-Hub-Revision", HUB_REVISION);
  headers.set(RELAY_ERROR_HEADER, relayError);
  return headers;
}

function relayResponse(response, relayError, status = response.status) {
  return new Response(response.body, {
    status,
    statusText: response.statusText,
    headers: errorHeaders(relayError, response),
  });
}

function relayFailure(relayError, status, text = "relay failed") {
  return new Response(text, { status, headers: errorHeaders(relayError) });
}

function requestAction(request) {
  return new URL(request.url).pathname.split("/").filter(Boolean).at(-1) || "";
}

function terminalReason(reason, error) {
  if (reason === "upstream_close") return "upstream_closed";
  if (reason === "orphan_timeout" || reason === "client_close") return "session_lost";
  const message = String(error?.message || error || "");
  if (message.includes("target_mismatch")) return "target_mismatch";
  return "";
}

function patchRelaySession(session) {
  if (session.__statusAwarePatched) return session;
  session.__statusAwarePatched = true;
  session.__relayError = "";

  const originalTerminate = session.terminate.bind(session);
  session.terminate = (reason, error) => {
    const classified = terminalReason(reason, error);
    if (classified && !session.__relayError) session.__relayError = classified;
    return originalTerminate(reason, error);
  };

  const originalDrainUploads = session.drainUploads.bind(session);
  session.drainUploads = async () => {
    try {
      return await originalDrainUploads();
    } catch (error) {
      session.__relayError = "socket_write_failed";
      throw error;
    }
  };

  const originalPump = session.pump.bind(session);
  session.pump = async () => {
    try {
      return await originalPump();
    } catch (error) {
      session.__relayError = "socket_read_failed";
      throw error;
    }
  };

  const originalFetch = session.fetch.bind(session);
  session.fetch = async (request) => {
    const url = new URL(request.url);
    const action = requestAction(request);
    const target = (url.searchParams.get("dst") || "").trim();

    if (session.target && target && session.target !== target) {
      return relayFailure("target_mismatch", 409, "relay failed");
    }

    const response = await originalFetch(request);
    if (response.headers.get(RELAY_ERROR_HEADER)) return response;

    if (response.status === 410) {
      return relayResponse(response, session.__relayError || "session_lost");
    }
    if (response.status === 409) {
      return relayResponse(response, "sequence_conflict");
    }
    if (response.status === 413) {
      return relayResponse(response, "invalid_size");
    }
    if (response.status === 405) {
      return relayResponse(response, "method_not_allowed");
    }
    if (response.status === 400) {
      if (action === "up") return relayResponse(response, "invalid_sequence");
      if (action === "down") return relayResponse(response, "invalid_ack");
      return relayResponse(response, "invalid_request");
    }
    if (response.status === 502 && session.__relayError) {
      const code = session.__relayError;
      const terminal = code === "socket_write_failed" || code === "socket_read_failed" || code === "upstream_closed" || code === "session_lost";
      return relayResponse(response, code, terminal ? 410 : 502);
    }
    if (response.status >= 500) {
      return relayResponse(response, "transient_worker_error");
    }
    return response;
  };

  return session;
}

export class ChunkRelaySession extends ChunkRelayHub {
  getOrCreateSession(sid) {
    return patchRelaySession(super.getOrCreateSession(sid));
  }
}

export { ChunkRelaySession as ChunkRelayHub };

export default {
  async fetch(request, env, ctx) {
    try {
      const response = await chunkRelayWorker.fetch(request, env, ctx);
      if (!new URL(request.url).pathname.startsWith("/chunk-relay/")) return response;
      if (response.headers.get(RELAY_ERROR_HEADER)) return response;
      if (response.status === 410) return relayResponse(response, "session_lost");
      if (response.status === 409) return relayResponse(response, "sequence_conflict");
      if (response.status >= 500 && response.status !== 503) return relayResponse(response, "transient_worker_error");
      return response;
    } catch (error) {
      console.log("chunk relay status wrapper caught worker failure", {
        revision: REVISION,
        hub_revision: HUB_REVISION,
        relay_error: "transient_worker_error",
        error: String(error),
      });
      return relayFailure("transient_worker_error", 502);
    }
  },
};