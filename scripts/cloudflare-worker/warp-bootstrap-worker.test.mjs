import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const source = (await readFile(new URL("./warp-bootstrap-worker.js", import.meta.url), "utf8"))
  .replace(
    'import relayWorker, { ChunkRelaySession } from "./chunk-relay-status-worker.js";',
    `const relayWorker = { fetch: (...args) => globalThis.__warpBootstrapRelayFetch(...args) };
     class ChunkRelaySession {}`,
  );
const mod = await import("data:text/javascript;base64," + Buffer.from(source).toString("base64"));

const validPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

function jsonRequest(path, method, body, headers = {}) {
  return new Request(`https://example.workers.dev${path}`, {
    method,
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}

test("bootstrap entrypoint exports the configured Durable Object class", () => {
  assert.equal(typeof mod.ChunkRelaySession, "function");
});

test("health endpoint is local and does not reach relay", async (t) => {
  const old = globalThis.__warpBootstrapRelayFetch;
  globalThis.__warpBootstrapRelayFetch = async () => {
    assert.fail("relay must not handle bootstrap health");
  };
  t.after(() => { globalThis.__warpBootstrapRelayFetch = old; });

  const response = await mod.default.fetch(
    new Request("https://example.workers.dev/warp-bootstrap/health"),
    {},
    {},
  );
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal(response.headers.get("X-Tgws-Warp-Bootstrap-Revision"), "warp-bootstrap-v1");
  assert.deepEqual(await response.json(), { revision: "warp-bootstrap-v1" });
});

test("registration forwards only the public key to the fixed WARP API", async () => {
  let upstreamUrl;
  let upstreamInit;
  const fakeFetch = async (url, init) => {
    upstreamUrl = url;
    upstreamInit = init;
    return new Response(JSON.stringify({ id: "registration-id", token: "sensitive-token" }), {
      status: 201,
      headers: { "Content-Type": "application/json" },
    });
  };

  const response = await mod.handleWarpBootstrapRequest(
    jsonRequest(
      "/warp-bootstrap/v0a4005/reg",
      "POST",
      { key: validPublicKey, ignored: "must-not-be-forwarded" },
      { "X-Untrusted-Header": "must-not-be-forwarded" },
    ),
    undefined,
    fakeFetch,
  );

  assert.equal(upstreamUrl, "https://api.cloudflareclient.com/v0a4005/reg");
  assert.equal(upstreamInit.method, "POST");
  assert.equal(upstreamInit.headers.get("CF-Client-Version"), "a-6.11-2223");
  assert.equal(upstreamInit.headers.get("User-Agent"), "okhttp/3.12.1");
  assert.equal(upstreamInit.headers.get("X-Untrusted-Header"), null);
  assert.equal(upstreamInit.headers.get("Authorization"), null);
  assert.deepEqual(JSON.parse(upstreamInit.body), { key: validPublicKey });
  assert.equal(response.status, 201);
  assert.equal(response.headers.get("X-Tgws-Warp-Bootstrap-Revision"), "warp-bootstrap-v1");
  assert.deepEqual(await response.json(), { id: "registration-id", token: "sensitive-token" });
});

test("activation forwards only warp_enabled and a bounded bearer token", async () => {
  let upstreamUrl;
  let upstreamInit;
  const fakeFetch = async (url, init) => {
    upstreamUrl = url;
    upstreamInit = init;
    return new Response(JSON.stringify({ warp_enabled: true }), {
      status: 200,
      headers: { "Content-Type": "application/json", "Retry-After": "7" },
    });
  };

  const response = await mod.handleWarpBootstrapRequest(
    jsonRequest(
      "/warp-bootstrap/v0a4005/reg/123e4567-e89b-12d3-a456-426614174000",
      "PATCH",
      { warp_enabled: true, ignored: "must-not-be-forwarded" },
      { Authorization: "Bearer test.token_123" },
    ),
    undefined,
    fakeFetch,
  );

  assert.equal(
    upstreamUrl,
    "https://api.cloudflareclient.com/v0a4005/reg/123e4567-e89b-12d3-a456-426614174000",
  );
  assert.equal(upstreamInit.method, "PATCH");
  assert.equal(upstreamInit.headers.get("Authorization"), "Bearer test.token_123");
  assert.deepEqual(JSON.parse(upstreamInit.body), { warp_enabled: true });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Retry-After"), "7");
});

test("invalid public key is rejected before any upstream request", async () => {
  let calls = 0;
  const response = await mod.handleWarpBootstrapRequest(
    jsonRequest("/warp-bootstrap/v0a4005/reg", "POST", { key: "invalid" }),
    undefined,
    async () => { calls++; return new Response(); },
  );
  assert.equal(response.status, 400);
  assert.equal(calls, 0);
});

test("activation requires authorization and exact activation payload", async () => {
  let calls = 0;
  const request = jsonRequest(
    "/warp-bootstrap/v0a4005/reg/123e4567-e89b-12d3-a456-426614174000",
    "PATCH",
    { warp_enabled: false },
  );
  const response = await mod.handleWarpBootstrapRequest(
    request,
    undefined,
    async () => { calls++; return new Response(); },
  );
  assert.equal(response.status, 401);
  assert.equal(calls, 0);
});

test("unknown bootstrap paths never become an open proxy", async () => {
  let calls = 0;
  const response = await mod.handleWarpBootstrapRequest(
    jsonRequest("/warp-bootstrap/proxy", "POST", { url: "https://example.com" }),
    undefined,
    async () => { calls++; return new Response(); },
  );
  assert.equal(response.status, 404);
  assert.equal(calls, 0);
});

test("non-bootstrap requests are delegated unchanged", async (t) => {
  const old = globalThis.__warpBootstrapRelayFetch;
  let seen;
  globalThis.__warpBootstrapRelayFetch = async (...args) => {
    seen = args;
    return new Response("relay", { status: 202 });
  };
  t.after(() => { globalThis.__warpBootstrapRelayFetch = old; });

  const request = new Request("https://example.workers.dev/chunk-relay/open?sid=session_123");
  const env = { CHUNK_RELAY: "binding" };
  const ctx = { marker: true };
  const response = await mod.default.fetch(request, env, ctx);
  assert.equal(response.status, 202);
  assert.equal(await response.text(), "relay");
  assert.equal(seen[0], request);
  assert.equal(seen[1], env);
  assert.equal(seen[2], ctx);
});
