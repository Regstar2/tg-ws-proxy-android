import relayWorker, { ChunkRelaySession } from "./chunk-relay-status-worker.js";
import {
  handleWarpBootstrapRequest,
  isWarpBootstrapPath,
} from "./warp-bootstrap-standalone-worker.js";

export { ChunkRelaySession };
export { handleWarpBootstrapRequest };

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (isWarpBootstrapPath(url.pathname)) {
      return handleWarpBootstrapRequest(request, url);
    }
    return relayWorker.fetch(request, env, ctx);
  },
};
