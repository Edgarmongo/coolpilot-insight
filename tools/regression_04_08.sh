#!/usr/bin/env bash
# SOURCE-04-08 · 回归脚本（一条命令跑完四件事）
#
#   1. 部署回归：5 容器 healthy + Flyway 版本 + ds_source 结构版本
#   2. Java 全量测试
#   3. Python 全量测试
#   4. 跨服务链路：backend 容器 → collection 容器 /v1/registry/snapshot
#
# 用法（仓库根）：
#   bash tools/regression_04_08.sh            # 全跑
#   SKIP_JAVA=1 bash tools/regression_04_08.sh
#
# 为什么要有这个脚本：这些检查此前是分散的手工命令，换个人就复现不出来。
# 脚本化的意义是"可重跑、结论可比"，不是把失败藏起来——任一项失败，
# 末尾的汇总会如实列出并返回非 0。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

COMPOSE="docker compose --env-file .env -f deploy/compose/docker-compose.yml"

# 内部令牌：测试与脚本里的真实往返都要用（.env 被 gitignore，不入库）
TOKEN_VALUE=$(grep '^COLLECTION_INTERNAL_TOKEN=' .env 2>/dev/null | cut -d= -f2-)
PASS=0
FAIL=0
declare -a RESULTS

record() { # record <name> <ok|fail> <detail>
  if [ "$2" = "ok" ]; then
    PASS=$((PASS + 1)); RESULTS+=("✅ $1 — $3")
  else
    FAIL=$((FAIL + 1)); RESULTS+=("❌ $1 — $3")
  fi
  printf '%-42s %s  %s\n' "$1" "$2" "$3"
}

echo "=========== 1. 部署回归 ==========="
UNHEALTHY=$(docker ps --format '{{.Names}}\t{{.Status}}' | grep -E '^cpi-(mysql|flask|collection|backend)' | grep -vc 'healthy')
TOTAL=$(docker ps --format '{{.Names}}' | grep -cE '^cpi-(mysql|flask|collection|backend)$')
if [ "$TOTAL" -eq 4 ] && [ "$UNHEALTHY" -eq 0 ]; then
  record "四容器 healthy" ok "total=$TOTAL unhealthy=0"
else
  record "四容器 healthy" fail "total=$TOTAL unhealthy=$UNHEALTHY"
fi

# frontend 没有 healthcheck（Nginx 静态站），只能验"在跑"
if docker ps --format '{{.Names}}' | grep -qx cpi-frontend; then
  record "前端容器运行中" ok "cpi-frontend Up（无 healthcheck，不宣称 healthy）"
else
  record "前端容器运行中" fail "cpi-frontend 不在运行"
fi

FLYWAY=$(docker exec cpi-mysql mysql -uroot -proot -N -e \
  "SELECT MAX(CAST(version AS UNSIGNED)) FROM coolpilot_insight.flyway_schema_history WHERE success=1;" 2>/dev/null)
if [ -n "${FLYWAY:-}" ] && [ "$FLYWAY" -ge 33 ] 2>/dev/null; then
  record "Flyway 版本" ok "V$FLYWAY"
else
  record "Flyway 版本" fail "got=${FLYWAY:-<empty>} expect>=33"
fi

SCHEMA=$(docker exec cpi-mysql mysql -uroot -proot -N -e \
  "SELECT JSON_UNQUOTE(JSON_EXTRACT(capability_json,'\$.schemaVersion')) FROM coolpilot_insight.ds_source WHERE code='DOUYIN';" 2>/dev/null)
if [ "$SCHEMA" = "2.0" ]; then
  record "ds_source 已是 Desired v2" ok "schemaVersion=2.0"
else
  record "ds_source 已是 Desired v2" fail "schemaVersion=${SCHEMA:-<empty>}"
fi

echo
echo "=========== 2. Java 全量测试 ==========="
if [ "${SKIP_JAVA:-0}" = "1" ]; then
  record "mvn test" fail "被 SKIP_JAVA 跳过（本次结果不可作为回归证据）"
else
  JAVA_LOG=$(mktemp)
  # 部署配置了内部令牌后，真实往返测试必须带令牌（测试读 COLLECTION_INTERNAL_TOKEN）
  ( cd backend && COLLECTION_INTERNAL_TOKEN="$TOKEN_VALUE" \
      mvn -s ../deploy/docker/maven-settings.xml -o test ) > "$JAVA_LOG" 2>&1
  JAVA_RC=$?
  SUMMARY=$(grep -E '^\[INFO\] Tests run: [0-9]+, Failures' "$JAVA_LOG" | tail -1)
  if [ $JAVA_RC -eq 0 ]; then
    record "mvn test" ok "$SUMMARY"
  else
    record "mvn test" fail "$SUMMARY（日志 $JAVA_LOG）"
  fi
fi

echo
echo "=========== 3. Python 全量测试 ==========="
PY_LOG=$(mktemp)
( cd collection-service && python -m pytest tests -q ) > "$PY_LOG" 2>&1
PY_RC=$?
PY_SUMMARY=$(tail -1 "$PY_LOG")
if [ $PY_RC -eq 0 ]; then
  record "pytest" ok "$PY_SUMMARY"
else
  record "pytest" fail "$PY_SUMMARY（日志 $PY_LOG）"
fi

# 清洗脚本的异常脱敏（FIX-02）：与 collection-service 分开跑，依赖不同
AI_LOG=$(mktemp)
( cd ai-service && python -m pytest tests -q ) > "$AI_LOG" 2>&1
AI_RC=$?
AI_SUMMARY=$(tail -1 "$AI_LOG")
if [ $AI_RC -eq 0 ]; then
  record "pytest (ai-service)" ok "$AI_SUMMARY"
else
  record "pytest (ai-service)" fail "$AI_SUMMARY（日志 $AI_LOG）"
fi

echo
echo "=========== 4. 跨服务链路 ==========="
# 采集服务的快照端点要求内部令牌（配置了 COLLECTION_INTERNAL_TOKEN 时）
SNAP_CODE=$(docker exec cpi-backend sh -c \
  'curl -s -o /tmp/s.json -w "%{http_code}" http://collection:5001/v1/registry/snapshot -H "X-Internal-Token: $COLLECTION_INTERNAL_TOKEN"')
if [ "$SNAP_CODE" = "200" ]; then
  SNAP_ID=$(docker exec cpi-backend python3 -c \
    "import json;print(json.load(open('/tmp/s.json'))['data']['snapshotId'][:20])" 2>/dev/null)
  record "backend → collection 快照" ok "http=200 snapshotId=$SNAP_ID"
else
  record "backend → collection 快照" fail "http=$SNAP_CODE"
fi

API_CODE=$(curl -s -o /dev/null -w '%{http_code}' \
  http://127.0.0.1:8080/api/v1/source/sources/DOUYIN/capabilities)
if [ "$API_CODE" = "401" ]; then
  record "能力接口未登录必须 401" ok "http=401"
else
  record "能力接口未登录必须 401" fail "http=$API_CODE"
fi

# SPA 深链接：刷新 /sources 必须回 index.html，否则页面只在"从菜单点进去"时能用
DEEP=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:5173/sources)
if [ "$DEEP" = "200" ]; then
  record "前端深链接 /sources" ok "http=200（Nginx fallback 生效）"
else
  record "前端深链接 /sources" fail "http=$DEEP（刷新会 404）"
fi

echo
echo "=========== 5. 生产采集链路（不发起真实采集） ==========="
# 只验证模块可导入、CSV 落盘目录是共享挂载。
# 真实采集（python comment.py <id> 20）需要 Chrome 与平台风控配合，
# 且会写入生产 CSV，不在自动回归里跑——见 SOURCE-04-08-REGRESSION.md §5。
IMPORT_OUT=$(docker exec cpi-backend python3 -c "
import sys; sys.path.insert(0,'/app/collection-service/app/adapters/douyin')
import comment, collector
print('SAVE_DIR=' + collector.SAVE_DIR)
" 2>&1 | tail -1)
if echo "$IMPORT_OUT" | grep -q 'SAVE_DIR=/app/collection-service/datas'; then
  record "采集链路可导入" ok "$IMPORT_OUT"
else
  record "采集链路可导入" fail "$IMPORT_OUT"
fi

# 产出格式是生产契约：BOM + 13 列，一个都不能变。
# 这里用一次真实的落盘（写 /tmp，不碰生产目录）来验证，而不是读代码"看起来没改"。
CSV_CHECK=$(docker exec cpi-backend python3 -c "
import sys, os
sys.path.insert(0, '/app/collection-service/app/adapters/douyin')
import collector
p = '/tmp/regr_csv_check.csv'
if os.path.exists(p): os.remove(p)
collector.ensure_header(p)
raw = open(p, 'rb').read()
print('BOM=' + ('yes' if raw.startswith(b'\xef\xbb\xbf') else 'no'))
print('COLS=' + str(raw.decode('utf-8-sig').strip().count(',') + 1))
os.remove(p)
" 2>&1 | tr '\n' ' ')
if echo "$CSV_CHECK" | grep -q 'BOM=yes' && echo "$CSV_CHECK" | grep -q 'COLS=13'; then
  record "CSV 产出格式（BOM + 13 列）" ok "$CSV_CHECK"
else
  record "CSV 产出格式（BOM + 13 列）" fail "$CSV_CHECK"
fi

echo
echo "=========== 汇总 ==========="
for line in "${RESULTS[@]}"; do echo "$line"; done
echo "通过 $PASS / 失败 $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
