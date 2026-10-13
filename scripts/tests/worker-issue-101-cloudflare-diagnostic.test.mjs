import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const script = readFileSync(new URL('../worker-issue-101-cloudflare-diagnostic.mjs', import.meta.url), 'utf8');
const source = script.replace("import { connect } from 'cloudflare:sockets';", 'const connect = (...args) => globalThis.__mockConnect(...args);');
if (source === script) throw new Error('Cloudflare socket import not matched');

class FakeWebSocket {
  constructor() { this.listeners = new Map(); this.sent = []; this.readyState = 0; this.closed = false; }
  addEventListener(kind, handler) { this.listeners.set(kind, handler); }
  accept() { this.readyState = 1; }
  send(data) { this.sent.push([...data]); }
  close(code, reason) { this.readyState = 3; this.closed = true; this.closeCode = code; this.closeReason = reason; }
  emit(type, event = {}) { this.listeners.get(type)?.(event); }
}
class FakeWebSocketPair {
  constructor() { this[0] = new FakeWebSocket(); this[1] = new FakeWebSocket(); globalThis.__lastPair = this; }
}
class FakeResponse {
  constructor(body, options) { this.body = body; this.status = options.status; this.webSocket = options.webSocket; }
}
const originalConsoleLog = console.log;
const originalConsoleError = console.error;
const logs = [];
console.log = message => logs.push(JSON.parse(message));
console.error = message => logs.push(JSON.parse(message));
globalThis.WebSocketPair = FakeWebSocketPair;
globalThis.Response = FakeResponse;
const handler = (await import('data:text/javascript,' + encodeURIComponent(source))).default;

test('diagnostic Worker restricts targets, logs stages and relays byte-exact data', async () => {
  assert.equal((await handler.fetch(new Request('https://foo.test/'))).status, 404);
  assert.equal((await handler.fetch(new Request('https://foo.test/apiws'))).status, 426);
  assert.equal((await handler.fetch(new Request('https://foo.test/apiws?dst=evil.example', { headers: { Upgrade: 'websocket' } }))).status, 403);

  let controller;
  const writes = [];
  let socketClosed = false;
  globalThis.__mockConnect = ({ hostname, port }) => {
    assert.equal(hostname, 'www.google.com');
    assert.equal(port, 443);
    return {
      opened: Promise.resolve({}),
      readable: new ReadableStream({ start(c) { controller = c; } }),
      writable: new WritableStream({ write(data) { writes.push([...data]); } }),
      close() { socketClosed = true; controller.close(); return Promise.resolve(); },
    };
  };
  const response = await handler.fetch(new Request('https://foo.test/apiws?dst=www.google.com', { headers: { Upgrade: 'websocket' } }));
  assert.equal(response.status, 101);
  const pair = globalThis.__lastPair;
  assert.equal(response.webSocket, pair[0]);
  pair[1].emit('message', { data: new Uint8Array([0, 1, 255, 42]) });
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.deepEqual(writes, [[0, 1, 255, 42]]);
  controller.enqueue(new Uint8Array([4, 5, 6]));
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.deepEqual(pair[1].sent, [[4, 5, 6]]);
  const phases = logs.map(x => x.phase);
  for (const required of ['ws_accepted', 'tcp_opened', 'ws_message_received', 'tcp_write_completed', 'tcp_read_completed', 'ws_send_completed']) {
    assert.ok(phases.includes(required), required);
  }
  assert.ok(logs.every(x => x.component === 'flowseal_issue101' && !JSON.stringify(x).includes('255')), 'logs must contain counters, not payload');
  pair[1].emit('close', { code: 1000 });
  assert.equal(socketClosed, true);
});

test('upstream connect failure logs its stage and closes the WebSocket', async () => {
  globalThis.__mockConnect = () => ({
    opened: Promise.reject(new Error('unit simulated TCP failure')),
    readable: new ReadableStream(),
    writable: new WritableStream(),
    close() { return Promise.resolve(); },
  });
  const before = logs.length;
  const response = await handler.fetch(new Request('https://foo.test/apiws?dst=149.154.167.51', { headers: { Upgrade: 'websocket' } }));
  assert.equal(response.status, 101);
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(logs.slice(before).some(x => x.phase === 'tcp_read_or_open_failed'));
  assert.equal(globalThis.__lastPair[1].closeCode, 1011);
});

process.on('exit', () => { console.log = originalConsoleLog; console.error = originalConsoleError; });
