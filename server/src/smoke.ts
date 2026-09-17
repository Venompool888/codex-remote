import { CodexAppServer } from "./codex-app-server.js";

const codex = new CodexAppServer(process.env.CODEX_REMOTE_CODEX_BIN ?? "codex");
codex.on("log", (line) => console.error(`[codex] ${line}`));
try {
  await codex.start();
  const result = await codex.call("thread/list", { limit: 3, sortKey: "updated_at", sortDirection: "desc" });
  const data = (result as { data?: unknown[] }).data ?? [];
  console.log(JSON.stringify({ ok: true, threadCount: data.length }));
} finally {
  await codex.stop();
}

