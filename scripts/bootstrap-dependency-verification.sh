#!/usr/bin/env bash
set -euo pipefail

./gradlew --write-verification-metadata sha256 help
cat <<'MSG'
Generated gradle/verification-metadata.xml.
Review every trusted artifact and checksum before committing it, then run the normal CI tasks with dependency verification enabled.
MSG
