import { homedir } from "node:os";
import { join } from "node:path";
import { DEVICE_SCOPES, DeviceAuth } from "./auth.js";

const stateDirectory = process.env.CODEX_REMOTE_STATE_DIR ?? join(homedir(), ".codex-remote");
const auth = new DeviceAuth(join(stateDirectory, "devices.json"));
await auth.load();

const [command = "list", deviceId, scopeList] = process.argv.slice(2);
if (command === "list") {
  console.log(JSON.stringify({ devices: auth.list() }, null, 2));
} else if (command === "revoke") {
  if (!deviceId) throw new Error("Usage: npm run devices -- revoke <device-id>");
  const revoked = await auth.revoke(deviceId);
  if (!revoked) throw new Error(`Unknown device: ${deviceId}`);
  console.log(JSON.stringify({ revoked: deviceId }));
} else if (command === "scopes") {
  if (!deviceId || scopeList === undefined) {
    throw new Error(`Usage: npm run devices -- scopes <device-id> <comma-separated-scopes>\nAvailable: ${DEVICE_SCOPES.join(",")}`);
  }
  const scopes = scopeList ? scopeList.split(",").map((scope) => scope.trim()).filter(Boolean) : [];
  const updated = await auth.setScopes(deviceId, scopes);
  if (!updated) throw new Error(`Unknown device: ${deviceId}`);
  console.log(JSON.stringify({ updated: deviceId, scopes }));
} else {
  throw new Error("Usage: npm run devices -- [list | revoke <device-id> | scopes <device-id> <comma-separated-scopes>]");
}
