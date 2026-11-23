#!/usr/bin/env bash
# Run beside the live stack, using its MySQL container but a dedicated rehearsal database.
set -eu
core=$(cd "$(dirname "$0")/../.." && pwd)
model="$core/../sentrix-model-server"
dashboard="$core/../sentrix-dashboard"
mysql=sentrix-core-mysql-1
artifacts=("$core"/target/sentrix-core-*-SNAPSHOT.jar)
jar=${artifacts[0]}
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
[[ -x "$model/.venv/bin/uvicorn" && -x "$dashboard/node_modules/.bin/vite" && -f "$jar" ]]
for port in 8001 8081 5177; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null; then
    echo "재생용 포트 $port 사용 중" >&2
    exit 1
  fi
done
docker exec -i "$mysql" sh -c 'test "$MYSQL_USER" = sentrix && MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root' <<'SQL'
CREATE DATABASE IF NOT EXISTS sentrixreplayfold2;
GRANT ALL PRIVILEGES ON `sentrixreplayfold2`.* TO 'sentrix'@'%';
SQL
work=$(mktemp -d)
children=()
cleanup() {
  for pid in "${children[@]}"; do kill "$pid" 2>/dev/null || true; done
  wait || true
  rm -rf -- "$work"
}
trap cleanup EXIT
trap 'exit 0' INT TERM
# Keep lazy class loading independent of later Maven builds in target/.
cp "$jar" "$work/replay.jar"
(
  cd "$model"
  unset SENTRIX_TELEMETRY_CONFIG
  # Keep per-visitor observation windows outside the checkout. Environment 1 is the original archive.
  export SENTRIX_FEATURE_DIR="$(python3 -c 'import tempfile; print(tempfile.gettempdir()+"/sentrix-exhibition-features")')"
  mkdir -p -m 700 "$SENTRIX_FEATURE_DIR"
  if [[ ! -e "$SENTRIX_FEATURE_DIR/1" ]]; then ln -s "$model/demo/replay/1" "$SENTRIX_FEATURE_DIR/1"; fi
  [[ "$SENTRIX_FEATURE_DIR/1" -ef "$model/demo/replay/1" ]]
  export SENTRIX_CALIBRATION_FILE="$model/demo/model-b-fold2/calibration.json"
  export SENTRIX_MODEL_DIR="$model/demo/model-b-fold2"
  exec .venv/bin/uvicorn app:app --host 127.0.0.1 --port 8001
) &
children+=($!)
(
  cd "$core"
  export DB_USERNAME=sentrix
  DB_PASSWORD=$(docker exec "$mysql" printenv MYSQL_PASSWORD)
  export DB_PASSWORD DB_URL=jdbc:mysql://127.0.0.1:3307/sentrixreplayfold2
  export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8081
  export SENTRIX_ANALYSIS_ENABLED=true SENTRIX_ANALYSIS_BASE_URL=http://127.0.0.1:8001
  export SENTRIX_ANALYSIS_MODEL_VERSION=linuxv1-b-fold2 SENTRIX_ANALYSIS_FEATURE_SCHEMA_VERSION=re2ob-v0-rel18-all58
  export SENTRIX_ANALYSIS_TIMEOUT_SECONDS=40
  exec "$java" -Xmx160m -XX:ActiveProcessorCount=2 -jar "$work/replay.jar"
) &
children+=($!)
(
  cd "$dashboard"
  export SENTRIX_API_TARGET=http://127.0.0.1:8081
  exec node_modules/.bin/vite preview --host 127.0.0.1 --port 5177 --strictPort
) &
children+=($!)
while :; do
  for pid in "${children[@]}"; do kill -0 "$pid" 2>/dev/null || exit 1; done
  sleep 3
done
