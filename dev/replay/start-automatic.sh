#!/usr/bin/env bash
# Requires the local MySQL container, the built dashboard/JAR and the Linux replay model on port 8001.
set -euo pipefail
core=$(cd "$(dirname "$0")/../.." && pwd)
dashboard="$core/../sentrix-dashboard"
manifest="$core/../sentrix-model-server/demo/replay-cases.json"
artifacts=("$core"/target/sentrix-core-*-SNAPSHOT.jar)
jdk=${JAVA_HOME:?Set JAVA_HOME to JDK 21}
for port in 8082 5178; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null; then echo "Port $port is in use" >&2; exit 1; fi
done
curl -fsS --max-time 5 http://127.0.0.1:8001/health | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["modelVersion"]=="linuxv1-b-fold2" and d["onlineFeatureStatus"]=="FILE_INPUT_ONLY"'
bounds=$(python3 - "$manifest" <<'PY'
import json,sys
from datetime import datetime,timezone
case=next(c for c in json.load(open(sys.argv[1]))['cases'] if c['case_id']=='lv1_linux-v1-delay-front-product-r02')
a,b=(int(datetime.fromisoformat(case[k].replace('Z','+00:00')).timestamp()) for k in ('first_window_end','last_window_end'))
print(*(datetime.fromtimestamp(t,timezone.utc).isoformat() for t in ((a+29)//30*30,b//30*30)))
PY
)
read -r first last <<<"$bounds"
work=$(mktemp -d)
children=()
cleanup() {
  for pid in "${children[@]:-}"; do [[ -z "$pid" ]] || kill "$pid" 2>/dev/null || true; done
  wait || true
  rm -rf -- "$work"
}
trap cleanup EXIT
trap 'exit 0' INT TERM
unzip -q "${artifacts[0]}" 'BOOT-INF/classes/*' 'BOOT-INF/lib/*' -d "$work"
classpath="$work/BOOT-INF/classes:$work/BOOT-INF/lib/*"
"$jdk/bin/javac" -cp "$classpath" -d "$work" "$core/dev/replay/AutomaticReplay.java"
mysql=sentrix-core-mysql-1
docker exec -i "$mysql" sh -c 'test "$MYSQL_USER" = sentrix && MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root' <<'SQL'
CREATE DATABASE IF NOT EXISTS sentrixautodemo;
GRANT ALL PRIVILEGES ON sentrixautodemo.* TO 'sentrix'@'%';
SQL
(
  export DB_USERNAME=sentrix DB_URL=jdbc:mysql://127.0.0.1:3307/sentrixautodemo
  DB_PASSWORD=$(docker exec "$mysql" printenv MYSQL_PASSWORD)
  export DB_PASSWORD
  export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8082 SENTRIX_ANALYSIS_WORKER_ENABLED=false
  export SENTRIX_ANALYSIS_ENABLED=true SENTRIX_ANALYSIS_BASE_URL=http://127.0.0.1:8001
  export SENTRIX_ANALYSIS_MODEL_VERSION=linuxv1-b-fold2 SENTRIX_ANALYSIS_FEATURE_SCHEMA_VERSION=re2ob-v0-rel18-all58
  export SENTRIX_ANALYSIS_TIMEOUT_SECONDS=40
  exec "$jdk/bin/java" -Xmx160m -XX:ActiveProcessorCount=2 -cp "$work:$classpath" AutomaticReplay "$first" "$last"
) &
children+=($!)
(
  cd "$dashboard"
  SENTRIX_API_TARGET=http://127.0.0.1:8082 exec node_modules/.bin/vite preview --host 127.0.0.1 --port 5178 --strictPort
) &
children+=($!)
while :; do
  for pid in "${children[@]}"; do kill -0 "$pid" 2>/dev/null || exit 1; done
  sleep 3
done
