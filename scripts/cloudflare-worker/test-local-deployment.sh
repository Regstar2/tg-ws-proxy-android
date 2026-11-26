#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
tmp=$(mktemp -d)
config=$(mktemp "$PWD/.wrangler-smoke-XXXXXX.json")
pid=""
stop_worker() {
  if [[ -n "$pid" ]]; then
    kill -- "-$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    pid=""
  fi
}
cleanup() {
  stop_worker
  rm -f "$config"
  rm -rf "$tmp"
}
trap cleanup EXIT
export WRANGLER_SEND_METRICS=false
export CI=true

run_smoke() {
  local main="$1" mode="$2"
  node --input-type=module - "$config" "$main" "$mode" <<'NODE'
import { readFileSync, writeFileSync } from "node:fs";
const [output, main, mode] = process.argv.slice(2);
const config = JSON.parse(readFileSync("wrangler.chunk-relay.jsonc", "utf8"));
config.main = main;
delete config.$schema;
if (mode === "missing") delete config.durable_objects;
writeFileSync(output, JSON.stringify(config));
NODE
  npx --yes wrangler@latest deploy --dry-run --config "$config" --outdir "$tmp/build-$mode-${main%.js}"
  setsid npx --yes wrangler@latest dev --local --config "$config" --ip 127.0.0.1 --port 8787 --persist-to "$tmp/state-$mode-${main%.js}" >"$tmp/worker.log" 2>&1 &
  pid=$!
  local ready=false
  for ((i=0; i<90; i++)); do
    if curl --silent --fail --max-time 2 http://127.0.0.1:8787/warp-bootstrap/health >/dev/null; then
      ready=true
      break
    fi
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 1
  done
  if [[ "$ready" != true ]]; then cat "$tmp/worker.log"; return 1; fi
  local args=()
  if [[ "$mode" == missing ]]; then args+=(--missing-binding); fi
  if ! node smoke-deployment.mjs http://127.0.0.1:8787 "${args[@]}"; then
    cat "$tmp/worker.log"
    return 1
  fi
  stop_worker
}
run_smoke warp-bootstrap-worker.js bound
run_smoke dashboard-worker.js bound
run_smoke dashboard-worker.js missing
