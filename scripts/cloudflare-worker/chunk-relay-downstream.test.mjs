import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function loadWorkerModule(connectImpl) {
  const workerUrl = new URL("./chunk-relay-worker.js", import.meta.url);
  let source = await readFile(workerUrl, "utf8");
  source = source
    .replace('import baseWorker from "./worker.js";', "const baseWorker = { fetch() { return new Response('base'); } };")
    .replace('import { connect } from "cloudflare:sockets";', "const connect = (...args) => globalThis.__chunkRelayConnect(...args);")
    .replace('import { DurableObject } from "cloudflare:workers";', "class DurableObject { constructor(ctx, env) { this.ctx = ctx; this.env = env; } }");
  globalThis.__chunkRelayConnect = connectImpl;
  return import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);
}

function makeDownstreamSocket(chunks) {
  let readIndex = 0;
  return {
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
          async read() {
            if (readIndex < chunks.length) {
              return { value: chunks[readIndex++], done: false };
            }
            return new Promise(() => {});
          },
          async cancel() {},
          releaseLock() {},
        };
      },
    },
    async close() {},
  };
}

function relayRequest(action, sid, dst, params = {}) {
  const query = new URLSearchParams({ sid, dst, ...params });
  return new Request(`https://relay.test/chunk-relay/${action}?${query}`, {
    method: action === "down" ? "GET" : "POST",
  });
}

async function waitForQueuedChunks(relay, sid, count) {
  for (let attempt = 0; attempt < 20; attempt++) {
    if ((relay.sessions.get(sid)?.queue.length || 0) >= count) return;
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.fail(`expected ${count} queued downstream chunks`);
}

test("relay emits 12 KiB downstream chunks without changing seq/ACK semantics", async () => {
  const payload = new Uint8Array(24 * 1024);
  for (let i = 0; i < payload.length; i++) payload[i] = i % 251;

  const { ChunkRelayHub } = await loadWorkerModule(() => makeDownstreamSocket([payload]));
  const relay = new ChunkRelayHub({}, {});
  const sid = "downstream_12k_session";
  const dst = "149.154.167.51";

  const opened = await relay.fetch(relayRequest("open", sid, dst));
  assert.equal(opened.status, 204);
  await waitForQueuedChunks(relay, sid, 2);

  const first = await relay.fetch(relayRequest("down", sid, dst, { ack: "0", wait: "0" }));
  assert.equal(first.status, 200);
  assert.equal(first.headers.get("X-Tgws-Chunk-Seq"), "1");
  assert.deepEqual(new Uint8Array(await first.arrayBuffer()), payload.slice(0, 12 * 1024));

  const duplicate = await relay.fetch(relayRequest("down", sid, dst, { ack: "0", wait: "0" }));
  assert.equal(duplicate.status, 200);
  assert.equal(duplicate.headers.get("X-Tgws-Chunk-Seq"), "1");
  assert.deepEqual(new Uint8Array(await duplicate.arrayBuffer()), payload.slice(0, 12 * 1024));

  const second = await relay.fetch(relayRequest("down", sid, dst, { ack: "1", wait: "0" }));
  assert.equal(second.status, 200);
  assert.equal(second.headers.get("X-Tgws-Chunk-Seq"), "2");
  assert.deepEqual(new Uint8Array(await second.arrayBuffer()), payload.slice(12 * 1024));

  const closed = await relay.fetch(relayRequest("close", sid, dst));
  assert.equal(closed.status, 204);
});
