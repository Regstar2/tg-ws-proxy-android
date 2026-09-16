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

test("WARP bootstrap capability endpoint is explicit and non-cacheable", async () => {
  const response = await mod.handleWarpBootstrap(
    new Request("https://example.workers.dev/warp-bootstrap/status"),
    async () => { throw new Error("upstream fetch must not run"); },
  );
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("X-Tgws-Warp-Bootstrap-Revision"), "warp-bootstrap-v1");
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.deepEqual(await response.json(), { revision: "warp-bootstrap-v1" });
});

test("WARP bootstrap POST forwards only to the fixed consumer registration endpoint", async () => {
  let forwarded;
  const response = await mod.handleWarpBootstrap(
    new Request("https://example.workers.dev/warp-bootstrap/v0a4005/reg", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "CF-Client-Version": "a-6.11-2223",
        "User-Agent": "okhttp/3.12.1",
        "Authorization": "Bearer must-not-forward-on-post",
      },
      body: JSON.stringify({ key: "public-key-only" }),
    }),
    async (request) => {
      forwarded = request;
      return new Response(JSON.stringify({ id: "device_1", token: "token" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    },
  );

  assert.equal(response.status, 200);
  assert.equal(forwarded.url, "https://api.cloudflareclient.com/v0a4005/reg");
  assert.equal(forwarded.method, "POST");
  assert.equal(forwarded.headers.get("Authorization"), null);
  assert.equal(forwarded.headers.get("CF-Client-Version"), "a-6.11-2223");
  assert.deepEqual(JSON.parse(await forwarded.text()), { key: "public-key-only" });
});

test("WARP bootstrap PATCH forwards bearer token only to one validated registration id", async () => {
  let forwarded;
  const response = await mod.handleWarpBootstrap(
    new Request("https://example.workers.dev/warp-bootstrap/v0a4005/reg/device_123", {
      method: "PATCH",
      headers: { "Authorization": "Bearer registration-token" },
      body: JSON.stringify({ warp_enabled: true }),
    }),
    async (request) => {
      forwarded = request;
      return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
    },
  );

  assert.equal(response.status, 200);
  assert.equal(forwarded.url, "https://api.cloudflareclient.com/v0a4005/reg/device_123");
  assert.equal(forwarded.method, "PATCH");
  assert.equal(forwarded.headers.get("Authorization"), "Bearer registration-token");
  assert.deepEqual(JSON.parse(await forwarded.text()), { warp_enabled: true });
});

test("WARP bootstrap rejects arbitrary paths, query strings, and oversized bodies", async () => {
  const neverFetch = async () => { throw new Error("upstream fetch must not run"); };

  const arbitrary = await mod.handleWarpBootstrap(
    new Request("https://example.workers.dev/warp-bootstrap/https://attacker.example/"),
    neverFetch,
  );
  assert.equal(arbitrary.status, 404);

  const query = await mod.handleWarpBootstrap(
    new Request("https://example.workers.dev/warp-bootstrap/v0a4005/reg?url=https://attacker.example", {
      method: "POST",
      body: "{}",
    }),
    neverFetch,
  );
  assert.equal(query.status, 400);

  const oversized = await mod.handleWarpBootstrap(
    new Request("https://example.workers.dev/warp-bootstrap/v0a4005/reg", {
      method: "POST",
      body: "x".repeat(64 * 1024 + 1),
    }),
    neverFetch,
  );
  assert.equal(oversized.status, 413);
});
