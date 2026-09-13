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

function makeDownstreamSocket(chunks, delaysMS = []) {
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
              const index = readIndex++;
              const delayMS = delaysMS[index] || 0;
              if (delayMS > 0) await new Promise((resolve) => setTimeout(resolve, delayMS));
              return { value: chunks[index], done: false };
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

test("relay coalesces three queued 4 KiB TCP reads into one 12 KiB downstream response", async () => {
  const chunks = [
    new Uint8Array(4 * 1024).fill(0x11),
    new Uint8Array(4 * 1024).fill(0x22),
    new Uint8Array(4 * 1024).fill(0x33),
  ];
  const expected = new Uint8Array(12 * 1024);
  expected.set(chunks[0], 0);
  expected.set(chunks[1], 4 * 1024);
  expected.set(chunks[2], 8 * 1024);

  const { ChunkRelayHub } = await loadWorkerModule(() => makeDownstreamSocket(chunks));
  const relay = new ChunkRelayHub({}, {});
  const sid = "downstream_coalesce_4k";
  const dst = "149.154.167.51";

  const opened = await relay.fetch(relayRequest("open", sid, dst));
  assert.equal(opened.status, 204);
  // Keep this assertion deterministic. The production coalescing window is a
  // wall-clock optimization, so scheduling jitter on Windows must not turn
  // this byte/seq invariant test into a timing test.
  await waitForQueuedChunks(relay, sid, 3);

  const first = await relay.fetch(relayRequest("down", sid, dst, { ack: "0", wait: "0" }));
  assert.equal(first.status, 200);
  assert.equal(first.headers.get("X-Tgws-Chunk-Seq"), "1");
  assert.equal((await first.clone().arrayBuffer()).byteLength, 12 * 1024);
  assert.deepEqual(new Uint8Array(await first.arrayBuffer()), expected);

  const duplicate = await relay.fetch(relayRequest("down", sid, dst, { ack: "0", wait: "0" }));
  assert.equal(duplicate.status, 200);
  assert.equal(duplicate.headers.get("X-Tgws-Chunk-Seq"), "1");
  assert.deepEqual(new Uint8Array(await duplicate.arrayBuffer()), expected);

  const closed = await relay.fetch(relayRequest("close", sid, dst));
  assert.equal(closed.status, 204);
});

test("coalescing caps one downstream response at 12 KiB and preserves the remainder", async () => {
  const chunks = [
    new Uint8Array(4 * 1024).fill(1),
    new Uint8Array(4 * 1024).fill(2),
    new Uint8Array(4 * 1024).fill(3),
    new Uint8Array(4 * 1024).fill(4),
  ];

  const { ChunkRelayHub } = await loadWorkerModule(() => makeDownstreamSocket(chunks));
  const relay = new ChunkRelayHub({}, {});
  const sid = "downstream_coalesce_cap";
  const dst = "149.154.167.51";

  const opened = await relay.fetch(relayRequest("open", sid, dst));
  assert.equal(opened.status, 204);
  await waitForQueuedChunks(relay, sid, 4);

  const first = await relay.fetch(relayRequest("down", sid, dst, { ack: "0", wait: "0" }));
  assert.equal(first.status, 200);
  assert.equal(first.headers.get("X-Tgws-Chunk-Seq"), "1");
  assert.equal((await first.arrayBuffer()).byteLength, 12 * 1024);

  const second = await relay.fetch(relayRequest("down", sid, dst, { ack: "1", wait: "0" }));
  assert.equal(second.status, 200);
  assert.equal(second.headers.get("X-Tgws-Chunk-Seq"), "2");
  const secondBody = new Uint8Array(await second.arrayBuffer());
  assert.equal(secondBody.byteLength, 4 * 1024);
  assert.ok(secondBody.every((value) => value === 4));

  const closed = await relay.fetch(relayRequest("close", sid, dst));
  assert.equal(closed.status, 204);
});
