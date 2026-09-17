#!/usr/bin/env node
import { mkdir, access, writeFile, rename, chmod } from "node:fs/promises";
import { constants } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { homedir } from "node:os";

const options = {};
for (let i = 2; i < process.argv.length; i += 2) {
  const key = process.argv[i];
  if (!["--bin-dir", "--server-dir", "--state-dir", "--control-socket"].includes(key) || !process.argv[i + 1]) {
    console.error("Usage: node scripts/install-cli.mjs [--bin-dir DIR] [--server-dir DIR] [--state-dir DIR] [--control-socket FILE]");
    process.exit(1);
  }
  options[key] = resolve(process.argv[i + 1]);
}
const server = options["--server-dir"] ?? resolve(dirname(fileURLToPath(import.meta.url)), "..");
const bin = options["--bin-dir"] ?? join(homedir(), ".local", "bin");
const state = options["--state-dir"] ?? process.env.CODEX_REMOTE_STATE_DIR ?? join(homedir(), ".codex-remote");
const socket = options["--control-socket"] ?? process.env.CODEX_REMOTE_CONTROL_SOCKET ?? join(state, "control.sock");
const quote = (value) => "'" + value.replaceAll("'", "'\\''") + "'";
try {
  await access(join(server, "dist", "cli.js"), constants.R_OK);
  await mkdir(bin, { recursive: true, mode: 0o700 });
  const target = join(bin, "codexremote");
  const temporary = target + `.tmp-${process.pid}`;
  // Freeze the matching service state directory; never guess another user's credentials.
  await writeFile(temporary, `#!/bin/sh\nexport CODEX_REMOTE_STATE_DIR=${quote(state)}\nexport CODEX_REMOTE_CONTROL_SOCKET=${quote(socket)}\nexec ${quote(process.execPath)} ${quote(join(server, "dist", "cli.js"))} "$@"\n`, { mode: 0o700 });
  await chmod(temporary, 0o700);
  await rename(temporary, target);
  console.log("Installed codexremote. Run it as the same user that runs the Remote Host.");
  if (!(process.env.PATH ?? "").split(":").includes(bin)) console.log(`Add the command directory to PATH: export PATH=${quote(bin)}:"$PATH"`);
} catch {
  console.error("Installation failed. Build the server first and check directory permissions. No service settings were changed.");
  process.exitCode = 1;
}
