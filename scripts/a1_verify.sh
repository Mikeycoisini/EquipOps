#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DB_URL:?Set isolated DB_URL}" "${DB_USER:?Set DB_USER}" "${DB_PASSWORD:?Set DB_PASSWORD}"
: "${A1_DB_CONFIRM:?Confirm isolated schema}" "${A1_TEST_PASSWORD:?Set a protected test password}"
: "${REDIS_SESSION_NAMESPACE:?Set an isolated A1 Redis namespace}" "${INVENTORY_EXPORT_ROOT:?Set isolated export root}"
[[ "$A1_DB_CONFIRM" =~ ^equipops_a1_[A-Za-z0-9_]+$ ]] || { echo 'Only A1 isolated schemas allowed' >&2; exit 1; }
[[ "$REDIS_SESSION_NAMESPACE" == equipops:a1:* ]] || { echo 'Only equipops:a1: namespaces allowed' >&2; exit 1; }
# 不允许Spring环境变量覆盖已核对的DB_URL、会话或文件目录。
for option in "${!SPRING_@}"; do
  [[ -z "$option" ]] || { echo 'Remove SPRING_* overrides before isolated verification' >&2; exit 1; }
done
python3 - <<'PY'
import os,pathlib
root=pathlib.Path(os.environ['INVENTORY_EXPORT_ROOT'])
if not root.is_absolute() or '..' in root.parts or not root.parts[-3:]==('artifacts','a1','exports'):
    raise SystemExit('Export root must be an absolute artifacts/a1/exports directory')
if any(p.is_symlink() for p in [root,*root.parents]):
    raise SystemExit('Export root must not contain symlinks')
PY
mkdir -p artifacts/a1
maven=(bash mvnw -B)
if [[ -n "${MAVEN_SETTINGS:-}" ]]; then maven+=(-s "$MAVEN_SETTINGS"); fi
if [[ -n "${A1_MAVEN_REPO:-}" ]]; then maven+=("-Dmaven.repo.local=$A1_MAVEN_REPO"); fi
# 只编译测试工具，不运行Spring测试；实际库名通过后才允许Flyway启动。
"${maven[@]}" -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/a1-classpath.txt > artifacts/a1/classpath.log 2>&1
classpath="target/test-classes:target/classes:$(cat target/a1-classpath.txt)"
java -cp "$classpath" com.support.A1Bootstrap --check-target > artifacts/a1/preflight.log 2>&1
"${maven[@]}" '-Dtest=!OperationsHttpTest' package > artifacts/a1/backend-tests.log 2>&1
"${maven[@]}" -Dtest=OperationsHttpTest test > artifacts/a1/operations-test.log 2>&1
java -cp "$classpath" com.support.A1Bootstrap > artifacts/a1/bootstrap.log 2>&1
java -cp "$classpath" com.support.A1Bootstrap >> artifacts/a1/bootstrap.log 2>&1
if A1_TEST_PASSWORD="${A1_TEST_PASSWORD}x" java -cp "$classpath" com.support.A1Bootstrap > artifacts/a1/bootstrap-wrong-password.log 2>&1; then
  echo 'Repeat bootstrap must not reset an existing password' >&2
  exit 1
fi
export APP_PORT="${A1_APP_PORT:-18082}"
python3 - "$APP_PORT" <<'PY'
import socket,sys
with socket.socket() as probe:
    probe.bind(('127.0.0.1',int(sys.argv[1])))
PY
java -jar target/EquipOps-0.0.1-SNAPSHOT.jar > artifacts/a1/server.log 2>&1 &
application_pid=$!
trap 'kill "$application_pid" 2>/dev/null || true; wait "$application_pid" 2>/dev/null || true' EXIT
for attempt in $(seq 1 60); do
  if curl -fsS --max-time 2 "http://127.0.0.1:$APP_PORT/api/auth/csrf" >/dev/null 2>&1; then break; fi
  kill -0 "$application_pid" 2>/dev/null || { echo 'Application startup failed; inspect artifacts/a1/server.log' >&2; exit 1; }
  [[ "$attempt" != 60 ]] || { echo 'Application readiness timed out' >&2; exit 1; }
  sleep 1
done
python3 scripts/a1_smoke.py --base-url "http://127.0.0.1:$APP_PORT" > artifacts/a1/http-results.json
if [[ "${A2_MYSQL_TESTS:-}" == true ]]; then
  mkdir -p artifacts/a2
  A2_BASE_URL="http://127.0.0.1:$APP_PORT" python3 scripts/a2_smoke.py > artifacts/a2/http-results.json
fi
if [[ "${A3_MYSQL_TESTS:-}" == true ]]; then
  mkdir -p artifacts/a3
  A3_BASE_URL="http://127.0.0.1:$APP_PORT" python3 scripts/a3_smoke.py > artifacts/a3/http-results.json
fi
echo 'Backend tests, repeat bootstrap and enabled HTTP smoke checks passed'
