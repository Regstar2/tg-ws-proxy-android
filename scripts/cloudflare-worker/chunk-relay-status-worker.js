import chunkRelayWorker, { ChunkRelayHub } from "./chunk-relay-worker.js";

const REVISION = "chunk-relay-mtproto-v10";
const HUB_REVISION = "relay-hub-v1";
const RELAY_ERROR_HEADER = "X-Tgws-Relay-Error";
const WARP_BOOTSTRAP_REVISION = "warp-bootstrap-v1";
const WARP_BOOTSTRAP_REVISION_HEADER = "X-Tgws-Warp-Bootstrap-Revision";
const WARP_API_ORIGIN = "https://api.cloudflareclient.com";
const WARP_REGISTER_PATH = "/v0a4005/reg";
const WARP_BOOTSTRAP_PREFIX = "/warp-bootstrap/";
const WARP_BOOTSTRAP_STATUS_PATH = "/warp-bootstrap/status";
const WARP_BOOTSTRAP_REGISTER_PATH = "/warp-bootstrap/v0a4005/reg";
const WARP_REQUEST_BODY_LIMIT = 64 * 1024;
const WARP_RESPONSE_BODY_LIMIT = 256 * 1024;
const WARP_REGISTRATION_ID = /^[A-Za-z0-9_-]{1,128}$/;

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

function warpBootstrapHeaders(contentType = "application/json; charset=utf-8") {
  return new Headers({
    "Cache-Control": "no-store",
    "Content-Type": contentType,
    [WARP_BOOTSTRAP_REVISION_HEADER]: WARP_BOOTSTRAP_REVISION,
  });
}

function warpBootstrapFailure(status, text) {
  return new Response(text, {
    status,
    headers: warpBootstrapHeaders("text/plain; charset=utf-8"),
  });
}

function boundedHeader(value, fallback, maxLength = 128) {
  const normalized = String(value || "").trim();
  if (!normalized || normalized.length > maxLength) return fallback;
  return normalized;
}

async function readBoundedResponseBody(response) {
  const declared = Number(response.headers.get("content-length") || "0");
  if (Number.isFinite(declared) && declared > WARP_RESPONSE_BODY_LIMIT) return null;
  if (!response.body) return new Uint8Array();

  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > WARP_RESPONSE_BODY_LIMIT) {
        await reader.cancel("response too large");
        return null;
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const body = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}

function warpBootstrapTarget(request) {
  const url = new URL(request.url);
  if (url.search) return { error: warpBootstrapFailure(400, "query not allowed") };

  if (url.pathname === WARP_BOOTSTRAP_STATUS_PATH) {
    if (request.method !== "GET") return { error: warpBootstrapFailure(405, "method not allowed") };
    return { status: true };
  }

  if (url.pathname === WARP_BOOTSTRAP_REGISTER_PATH) {
    if (request.method !== "POST") return { error: warpBootstrapFailure(405, "method not allowed") };
    return { upstreamPath: WARP_REGISTER_PATH, method: "POST" };
  }

  const patchPrefix = `${WARP_BOOTSTRAP_REGISTER_PATH}/`;
  if (url.pathname.startsWith(patchPrefix)) {
    if (request.method !== "PATCH") return { error: warpBootstrapFailure(405, "method not allowed") };
    const registrationId = url.pathname.slice(patchPrefix.length);
    if (!WARP_REGISTRATION_ID.test(registrationId)) {
      return { error: warpBootstrapFailure(400, "invalid registration id") };
    }
    return { upstreamPath: `${WARP_REGISTER_PATH}/${registrationId}`, method: "PATCH" };
  }

  return { error: warpBootstrapFailure(404, "not found") };
}

export async function handleWarpBootstrap(request, upstreamFetch = fetch) {
  const target = warpBootstrapTarget(request);
  if (target.error) return target.error;
  if (target.status) {
    return new Response(JSON.stringify({ revision: WARP_BOOTSTRAP_REVISION }), {
      status: 200,
      headers: warpBootstrapHeaders(),
    });
  }

  const declaredLength = Number(request.headers.get("content-length") || "0");
  if (Number.isFinite(declaredLength) && declaredLength > WARP_REQUEST_BODY_LIMIT) {
    return warpBootstrapFailure(413, "request too large");
  }

  let requestBody;
  try {
    requestBody = await request.arrayBuffer();
  } catch {
    return warpBootstrapFailure(400, "invalid request body");
  }
  if (requestBody.byteLength > WARP_REQUEST_BODY_LIMIT) {
    return warpBootstrapFailure(413, "request too large");
  }

  const headers = new Headers({
    "Accept": "application/json",
    "Content-Type": "application/json; charset=UTF-8",
    "User-Agent": boundedHeader(request.headers.get("User-Agent"), "okhttp/3.12.1"),
    "CF-Client-Version": boundedHeader(request.headers.get("CF-Client-Version"), "a-6.11-2223"),
  });

  if (target.method === "PATCH") {
    const authorization = boundedHeader(request.headers.get("Authorization"), "", 4096);
    if (!authorization.startsWith("Bearer ")) {
      return warpBootstrapFailure(400, "authorization required");
    }
    headers.set("Authorization", authorization);
  }

  let upstream;
  try {
    upstream = await upstreamFetch(new Request(`${WARP_API_ORIGIN}${target.upstreamPath}`, {
      method: target.method,
      headers,
      body: requestBody,
      redirect: "manual",
    }));
  } catch {
    return warpBootstrapFailure(502, "upstream unavailable");
  }

  const responseBody = await readBoundedResponseBody(upstream);
  if (responseBody === null) return warpBootstrapFailure(502, "upstream response too large");

  const contentType = boundedHeader(
    upstream.headers.get("content-type"),
    "application/json; charset=utf-8",
  );
  return new Response(responseBody, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: warpBootstrapHeaders(contentType),
  });
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
      const url = new URL(request.url);
      if (url.pathname.startsWith(WARP_BOOTSTRAP_PREFIX)) {
        return await handleWarpBootstrap(request);
      }

      const response = await chunkRelayWorker.fetch(request, env, ctx);
      if (!url.pathname.startsWith("/chunk-relay/")) return response;
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
