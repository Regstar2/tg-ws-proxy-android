import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function loadWorkerModule(connectImpl) {
  const workerUrl = new URL("./chunk-relay-worker.js", import.meta.url);
  let source = await readFile(workerUrl, "utf8");
  source = source
    .replace('import baseWorker from "./worker.js";', "const baseWorker = { fetch() { return new Response('base'); } };")
    .replace('import { connect } from "cloudflare:sockets";', "const connect = (...args) => globalThis.__chunkRelayConnect(...args);")
    .replace('import { DurableObject } from "cloudflare:workers";', "class DurableObject { constructor(ctx, env) { this.ctx = ctx; this.env = env; } }")
    .concat("\nexport { clampPollWaitMS };\n");
  globalThis.__chunkRelayConnect = connectImpl;
  return import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);
}

function controllableSocket() {
  let resolveRead;
  const socket = {
    opened: Promise.resolve(),
    writable: {
      getWriter() {
        return {
          async write() {},
          async abort() {},
          releaseLock() {},
        };
      },
    },
    readable: {
      getReader() {
        return {
          read() {
            return new Promise((resolve) => {
              resolveRead = resolve;
            });
          },
          async cancel() {},
          releaseLock() {},
        };
      },
    },
    async close() {},
  };
  return {
    socket,
    push(bytes) {
      assert.ok(resolveRead, "relay pump must be waiting for upstream data");
      const resolve = resolveRead;
      resolveRead = undefined;
      resolve({ value: bytes, done: false });
    },
  };
}

function relayRequest(action, sid, dst, params = {}) {
  const query = new URLSearchParams({ sid, dst, ...params });
  return new Request(`https://relay.test/chunk-relay/${action}?${query}`, {
    method: action === "down" ? "GET" : "POST",
  });
}

async function nextTurn() {
  await new Promise((resolve) => setImmediate(resolve));
}

test("long poll accepts the 20 second adaptive ceiling", async () => {
  const controller = controllableSocket();
  const { clampPollWaitMS } = await loadWorkerModule(() => controller.socket);

  assert.equal(clampPollWaitMS("6000"), 6000);
  assert.equal(clampPollWaitMS("12000"), 12000);
  assert.equal(clampPollWaitMS("20000"), 20000);
  assert.equal(clampPollWaitMS("99999"), 20000);
  assert.equal(clampPollWaitMS("invalid"), 0);
});

test("upstream payload wakes a 20 second downstream poll immediately", async () => {
  const controller = controllableSocket();
  const { ChunkRelayHub } = await loadWorkerModule(() => controller.socket);
  const relay = new ChunkRelayHub({}, {});
  const sid = "adaptive_poll_session";
  const dst = "149.154.167.51";

  const opened = await relay.fetch(relayRequest("open", sid, dst));
  assert.equal(opened.status, 204);
  await nextTurn();

  const startedAt = Date.now();
  const responsePromise = relay.fetch(relayRequest("down", sid, dst, { ack: "0", wait: "20000" }));
  await nextTurn();
  controller.push(new Uint8Array([1, 2, 3, 4]));

  const response = await Promise.race([
    responsePromise,
    new Promise((_, reject) => setTimeout(() => reject(new Error("long poll was not woken by payload")), 1000)),
  ]);
  const elapsedMS = Date.now() - startedAt;

  assert.equal(response.status, 200);
  assert.deepEqual(new Uint8Array(await response.arrayBuffer()), new Uint8Array([1, 2, 3, 4]));
  assert.ok(elapsedMS < 1000, `payload wake took ${elapsedMS} ms`);

  const closed = await relay.fetch(relayRequest("close", sid, dst));
  assert.equal(closed.status, 204);
});
