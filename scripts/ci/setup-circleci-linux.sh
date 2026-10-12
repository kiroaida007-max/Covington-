#!/usr/bin/env bash
set -euo pipefail
ci_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ci_root"
mkdir -p .ci-tools
fetch_unpack() {
  local name="$1" url="$2" hash="$3"
  local archive="$ci_root/.ci-tools/$name.archive"
  curl --fail --location --retry 3 --max-time 300 --output "$archive" "$url"
  printf '%s  %s\n' "$hash" "$archive" | sha256sum --check --status
  mkdir -p ".ci-tools/$name"
  tar -xf "$archive" --strip-components=1 -C ".ci-tools/$name"
  rm "$archive"
}
fetch_unpack jdk17 \
  'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz' \
  '3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e'
fetch_unpack python312 \
  'https://github.com/astral-sh/python-build-standalone/releases/download/20261009/cpython-3.12.15%2B20261009-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz' \
  'ffa8f85f1b56e88b687f08d743d817015524b86a9365fd921243132b80f1a36d'
fetch_unpack node22 \
  'https://nodejs.org/dist/v22.22.0/node-v22.22.0-linux-x64.tar.xz' \
  '9aa8e9d2298ab68c600bd6fb86a6c13bce11a4eca1ba9b39d79fa021755d7c37'
if [[ -n "${CODEX_PROXY_CERT:-}" && -f "$CODEX_PROXY_CERT" ]]; then
  .ci-tools/jdk17/bin/keytool -importcert -noprompt -alias covington-ci-proxy \
    -file "$CODEX_PROXY_CERT" -keystore .ci-tools/jdk17/lib/security/cacerts -storepass changeit
  export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt PIP_CERT=/etc/ssl/certs/ca-certificates.crt
fi
.ci-tools/python312/bin/python3 -m venv .ci-python
.ci-python/bin/python -m pip install PyYAML==6.0.2
python3 - "$ci_root" <<'PY'
import pathlib, shlex, sys
root = pathlib.Path(sys.argv[1])
entries = {
    'JAVA_HOME': str(root/'.ci-tools/jdk17'),
    'JDK17_HOME': str(root/'.ci-tools/jdk17'),
    'APKPROJECT_TOOLS': str(root/'.tools'),
    'APKPROJECT_PERFORMANCE': '1',
}
lines = [f'export {key}={shlex.quote(value)}' for key, value in entries.items()]
prefix = ':'.join(str(root/p) for p in ['.ci-python/bin', '.ci-tools/jdk17/bin', '.ci-tools/node22/bin'])
lines.append(f'export PATH={shlex.quote(prefix)}:"$PATH"')
(root/'.ci-env.sh').write_text('\n'.join(lines)+'\n')
PY
source .ci-env.sh
python3 -c 'import sys; assert sys.version_info[:2] == (3, 12)'
node -e 'if (process.versions.node.split(".")[0] !== "22") process.exit(1)'
java -version
