#!/bin/sh
# SOURCE-06 · 作业派发拒绝路径验证（不触发真实采集）
# 在 cpi-collection 容器内执行：docker exec -i cpi-collection sh -s < tools/verify_jobs_dispatch.sh
set -u
BASE=http://127.0.0.1:5001
T="X-Internal-Token: ${INTERNAL_TOKEN}"

hit() { # hit <desc> <expected_http> <expected_code> <json>
  desc=$1; want_http=$2; want_code=$3; body=$4
  out=$(curl -s -w '\n%{http_code}' -X POST "$BASE/v1/jobs" -H "$T" \
        -H 'Content-Type: application/json' -d "$body")
  http=$(printf '%s' "$out" | tail -1)
  json=$(printf '%s' "$out" | sed '$d')
  code=$(printf '%s' "$json" | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("code") or "")
except Exception: print("")')
  if [ "$http" = "$want_http" ] && [ "$code" = "$want_code" ]; then
    echo "✅ $desc  http=$http code=$code"
  else
    echo "❌ $desc  期望 http=$want_http/$want_code，实际 http=$http code=$code  body=$json"
  fi
}

echo "== 派发拒绝路径 =="
hit "缺 connectorCode"        400 PARAM_INVALID \
    '{"externalContentId":"123"}'
hit "未知接入"                404 CONNECTOR_NOT_FOUND \
    '{"connectorCode":"NOPE","capabilityCode":"COMMENT","externalContentId":"123"}'
hit "STUB 默认拒绝"           403 STUB_NOT_ALLOWED \
    '{"connectorCode":"STUB_PUBLIC","capabilityCode":"COMMENT","externalContentId":"123"}'
hit "访问模式不支持"          409 ACCESS_MODE_UNSUPPORTED \
    '{"connectorCode":"DOUYIN_PUBLIC","capabilityCode":"COMMENT","accessMode":"COOKIE","externalContentId":"123"}'
hit "能力未注册"              409 CAPABILITY_NOT_REGISTERED \
    '{"connectorCode":"DOUYIN_PUBLIC","capabilityCode":"AUTHOR_PROFILE","externalContentId":"123"}'

echo
echo "== 查询 =="
for path in /v1/jobs/nope /v1/jobs/nope/result; do
  printf '%-22s %s\n' "$path" "$(curl -s -o /dev/null -w '%{http_code}' -H "$T" "$BASE$path")"
done
printf '%-22s %s\n' "POST /cancel" \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "$T" "$BASE/v1/jobs/nope/cancel")"
