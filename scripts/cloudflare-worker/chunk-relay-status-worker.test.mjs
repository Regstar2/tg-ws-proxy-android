import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const source = (await readFile(new URL("./chunk-relay-status-worker.js", import.meta.url), "utf8"))
  .replace(
    'import chunkRelayWorker, { ChunkRelayHub } from "./chunk-relay-worker.js";',
    `const chunkRelayWorker = { fetch: (...args) => globalThis.__statusAwareFetch(...args) };
     class ChunkRelayHub {
       constructor() { this.sessions = new Map(); }
       getOrCreateSession(sid) {
         let session = this.sessions.get(sid);
         if (!session) {
           session = {
             target: "149.154.167.51",
             terminate() { return Promise.resolve(true); },
             async drainUploads() {},
             async pump() {},
             async fetch() { return new Response(null, { status: 204 }); },
           };
           this.sessions.set(sid, session);
         }
         return session;
       }
     }`,
  );
const mod = await import("data:text/javascript;base64," + Buffer.from(source).toString("base64"));

test("top-level wrapper annotates terminal 410", async (t) => {
  const old = globalThis.__statusAwareFetch;
  globalThis.__statusAwareFetch = async () => new Response("closed", { status: 410 });
  t.after(() => { globalThis.__statusAwareFetch = old; });
  const response = await mod.default.fetch(new Request("https://example.workers.dev/chunk-relay/down?sid=session_123&ack=1"), {}, {});
  assert.equal(response.status, 410);
  assert.equal(response.headers.get("X-Tgws-Relay-Error"), "session_lost");
});

test("top-level wrapper annotates transient 502", async (t) => {
  const old = globalThis.__statusAwareFetch;
  globalThis.__statusAwareFetch = async () => new Response("relay failed", { status: 502 });
  t.after(() => { globalThis.__statusAwareFetch = old; });
  const response = await mod.default.fetch(new Request("https://example.workers.dev/chunk-relay/open?sid=session_123"), {}, {});
  assert.equal(response.status, 502);
  assert.equal(response.headers.get("X-Tgws-Relay-Error"), "transient_worker_error");
});

test("patched relay session classifies target mismatch as 409", async () => {
  const hub = new mod.ChunkRelaySession();
  const session = hub.getOrCreateSession("session_123");
  const response = await session.fetch(new Request("https://example.workers.dev/chunk-relay/open?sid=session_123&dst=149.154.167.91", { method: "POST" }));
  assert.equal(response.status, 409);
  assert.equal(response.headers.get("X-Tgws-Relay-Error"), "target_mismatch");
});
