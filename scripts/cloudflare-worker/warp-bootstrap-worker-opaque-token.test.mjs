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

function activationRequest(authorization) {
  return new Request(
    "https://example.workers.dev/warp-bootstrap/v0a4005/reg/123e4567-e89b-12d3-a456-426614174000",
    {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
        Authorization: authorization,
      },
      body: JSON.stringify({ warp_enabled: true }),
    },
  );
}

test("activation treats the Consumer WARP bearer token as opaque visible ASCII", async () => {
  const authorization = "Bearer opaque:token%with!punctuation";
  let upstreamAuthorization = null;

  const response = await mod.handleWarpBootstrapRequest(
    activationRequest(authorization),
    undefined,
    async (_url, init) => {
      upstreamAuthorization = init.headers.get("Authorization");
      return new Response(JSON.stringify({ warp_enabled: true }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    },
  );

  assert.equal(response.status, 200);
  assert.equal(upstreamAuthorization, authorization);
});

test("activation still rejects whitespace inside the bearer token", async () => {
  let upstreamCalls = 0;
  const response = await mod.handleWarpBootstrapRequest(
    activationRequest("Bearer token with spaces"),
    undefined,
    async () => {
      upstreamCalls += 1;
      return new Response();
    },
  );

  assert.equal(response.status, 401);
  assert.equal(response.headers.get("X-Tgws-Warp-Bootstrap-Error"), "invalid_bearer");
  assert.equal(upstreamCalls, 0);
});
