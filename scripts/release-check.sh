#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

node -e 'JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"))' "$repo_dir/protocol/remote-v2.schema.json"

# Validate the merged release manifest after assembly. Explicit HTTP is supported
# for trusted local connections; HTTPS remains the default for new connections.

python3 "$repo_dir/scripts/check-compose-ui.py"
python3 "$repo_dir/scripts/test_check_compose_ui.py"

cd "$repo_dir/server"
npm test
npm run build

cd "$repo_dir/android"
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease

python3 "$repo_dir/scripts/check-release-manifest.py"

echo "Codex Remote release checks passed."
