import assert from "node:assert/strict";
import test from "node:test";
import { createPairingUri } from "../src/cli.js";

test("pairing QR URI preserves the host URL and opaque code", () => {
  const value = new URL(createPairingUri("https://remote.example.test:18788/", "opaque+/=_ code"));
  assert.equal(value.protocol, "codexremote:");
  assert.equal(value.host, "pair");
  assert.equal(value.searchParams.get("server"), "https://remote.example.test:18788");
  assert.equal(value.searchParams.get("code"), "opaque+/=_ code");
});
