import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const directory = new URL("./", import.meta.url);

function readConfig(name) {
  return readFileSync(new URL(name, directory), "utf8").trim();
}

test("Deploy to Cloudflare config stays in sync with the chunk relay config", () => {
  assert.equal(readConfig("wrangler.jsonc"), readConfig("wrangler.chunk-relay.jsonc"));
});
