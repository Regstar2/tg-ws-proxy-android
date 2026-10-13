// Diagnostic-only Flowseal-compatible Worker. Deploy to a NEW workers.dev name.
// Does not record payloads, source IP addresses, session IDs or secrets.
import { connect } from 'cloudflare:sockets';

// Restrict the diagnostic endpoint; never deploy this as an unrestricted relay.
const ALLOWED_TARGETS = new Set(['www.google.com', '149.154.167.51']);
const SAMPLE_EVERY = 64;

function bytesOf(data) {
  if (data instanceof ArrayBuffer) return Promise.resolve(new Uint8Array(data));
  if (ArrayBuffer.isView(data)) {
    return Promise.resolve(new Uint8Array(data.buffer, data.byteOffset, data.byteLength));
  }
  if (typeof data === 'string') return Promise.resolve(new TextEncoder().encode(data));
  if (data && typeof data.arrayBuffer === 'function') {
    return data.arrayBuffer().then(buffer => new Uint8Array(buffer));
  }
  return Promise.reject(new TypeError('unsupported WebSocket message type'));
}

function safeError(error) {
  return { error_name: String(error?.name || 'Error'), error_message: String(error?.message || error || '').slice(0, 160) };
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname !== '/apiws') return new Response('Not found', { status: 404 });
    if ((request.headers.get('Upgrade') || '').toLowerCase() !== 'websocket') {
      return new Response('Expected websocket', { status: 426 });
    }
    const dst = url.searchParams.get('dst');
    if (!ALLOWED_TARGETS.has(dst)) return new Response('Destination not allowed', { status: 403 });

    const id = crypto.randomUUID().slice(0, 12);
    const log = (phase, values = {}) => console.log(JSON.stringify({ component: 'flowseal_issue101', id, phase, dst, ...values }));
    const fail = (phase, error) => console.error(JSON.stringify({ component: 'flowseal_issue101', id, phase, dst, ...safeError(error) }));
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    let socket;
    try {
      socket = connect({ hostname: dst, port: 443 });
    } catch (error) {
      fail('tcp_connect_sync_failed', error);
      return new Response('Upstream unavailable', { status: 502 });
    }
    const reader = socket.readable.getReader();
    const writer = socket.writable.getWriter();
    let finished = false;
    let upFrames = 0;
    let downFrames = 0;
    let upBytes = 0;
    let downBytes = 0;
    let queued = Promise.resolve();

    const finish = (reason, closeCode = 1000, closeClient = true) => {
      if (finished) return;
      finished = true;
      log('closing', { reason, upFrames, downFrames, upBytes, downBytes });
      if (closeClient && server.readyState === 1) {
        try { server.close(closeCode, reason.slice(0, 95)); } catch (error) { fail('ws_close_failed', error); }
      }
      try {
        const done = socket.close();
        if (done && typeof done.catch === 'function') done.catch(error => fail('tcp_close_failed', error));
      } catch (error) { fail('tcp_close_failed', error); }
    };

    server.addEventListener('message', event => {
      // Promise chain guarantees ordered writes even if many events arrive at once.
      queued = queued.then(async () => {
        if (finished) return;
        const data = await bytesOf(event.data);
        upFrames++;
        upBytes += data.byteLength;
        if (upFrames <= 3 || upFrames % SAMPLE_EVERY === 0) {
          log('ws_message_received', { upFrames, bytes: data.byteLength, upBytes });
        }
        await socket.opened;
        await writer.write(data);
        if (upFrames <= 3 || upFrames % SAMPLE_EVERY === 0) {
          log('tcp_write_completed', { upFrames, upBytes });
        }
      }).catch(error => {
        fail('tcp_write_failed', error);
        finish('tcp_write_failed', 1011);
      });
    });
    server.addEventListener('close', event => {
      log('ws_close_received', { code: event.code || 1000 });
      finish('client_closed', 1000, false);
    });
    server.addEventListener('error', error => {
      fail('ws_error', error);
      finish('ws_error', 1011);
    });
    server.accept();
    log('ws_accepted');

    // This task is bounded by the live WebSocket and upstream socket.
    // No message contents or arbitrary client-identifying metadata are logged.
    void (async () => {
      try {
        await socket.opened;
        log('tcp_opened');
        while (!finished) {
          const { value, done } = await reader.read();
          if (done) { log('tcp_eof'); break; }
          if (finished || !value?.byteLength) continue;
          downFrames++;
          downBytes += value.byteLength;
          if (downFrames <= 3 || downFrames % SAMPLE_EVERY === 0) {
            log('tcp_read_completed', { downFrames, bytes: value.byteLength, downBytes });
          }
          server.send(value);
          if (downFrames <= 3 || downFrames % SAMPLE_EVERY === 0) {
            log('ws_send_completed', { downFrames, downBytes });
          }
        }
        finish('upstream_eof');
      } catch (error) {
        fail('tcp_read_or_open_failed', error);
        finish('upstream_error', 1011);
      } finally {
        try { reader.releaseLock(); } catch { /* nothing */ }
        try { writer.releaseLock(); } catch { /* nothing */ }
      }
    })();

    return new Response(null, { status: 101, webSocket: client });
  },
};
