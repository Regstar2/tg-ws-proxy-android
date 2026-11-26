import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

const base = new URL(process.argv[2] || "http://127.0.0.1:8787");
assert.ok(["http:", "https:"].includes(base.protocol));
assert.equal(base.pathname, "/");
assert.equal(base.search + base.hash + base.username + base.password, "");
const missing = process.argv.includes("--missing-binding");
const sid = "diag_" + randomUUID().replaceAll("-", "");
const request = (path) => fetch(new URL(path, base), { redirect: "error", signal: AbortSignal.timeout(10000) });
function checkRevision(response) {
  assert.equal(response.headers.get("X-Tgws-Chunk-Relay-Revision"), "chunk-relay-mtproto-v10");
  assert.equal(response.headers.get("X-Tgws-Relay-Hub-Revision"), "relay-hub-v1");
}

const health = await request("/warp-bootstrap/health");
assert.equal(health.status, 200);
assert.deepEqual(await health.json(), { service: "warp-bootstrap", revision: "warp-bootstrap-v1" });

const download = await request("/diag/fresh-download?size=16");
assert.equal(download.status, 200);
checkRevision(download);
assert.equal((await download.arrayBuffer()).byteLength, 16);

const invalid = await request("/chunk-relay/close?sid=bad");
assert.equal(invalid.status, 400);
checkRevision(invalid);

// Closing a fresh session exercises real DO dispatch without opening a Telegram socket.
const closed = await request("/chunk-relay/close?sid=" + sid);
checkRevision(closed);
if (missing) {
  assert.equal(closed.status, 503);
  assert.equal(closed.headers.get("X-Tgws-Worker-State"), "missing-chunk-relay-binding");
  assert.equal(closed.headers.get("X-Tgws-Relay-Error"), "missing_chunk_relay_binding");
  assert.match(await closed.text(), /CHUNK_RELAY.*ChunkRelaySession/);
} else {
  assert.equal(closed.status, 204);
  const unknown = await request("/chunk-relay/unknown?sid=" + sid);
  assert.equal(unknown.status, 404);
  checkRevision(unknown);
}
const legacy = await request("/apiws");
assert.equal(legacy.status, 426);
console.log("Worker deployment smoke passed: " + (missing ? "missing binding" : "DO dispatch") + "; no upstream traffic");
