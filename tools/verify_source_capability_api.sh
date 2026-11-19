#!/bin/sh
# 真实容器验证（SOURCE-04-06）。在 cpi-backend 容器内执行。
#
# 登录方式：管理员口令不可知（哨兵初始化已完成），因此注册一个临时用户，
# 在库中授予它 ADMIN 角色，验证完毕后删除。这样既不猜测既有口令，
# 也不改变任何既有用户与数据源的语义状态。
set -u
BASE=http://127.0.0.1:8080
CODE=DOUYIN
URL="$BASE/api/v1/source/sources/$CODE/capabilities"
PHONE=19900000009
PASS='Verify#2026cap'

echo "== 1) 未登录 GET =="
curl -s -o /tmp/r1.json -w "http=%{http_code}\n" "$URL"

echo "== 2) 未登录 PUT =="
curl -s -o /tmp/r2.json -w "http=%{http_code}\n" -X PUT "$URL" \
  -H 'Content-Type: application/json' -d '{}'

echo "== 3) 注册临时验证用户 =="
curl -s -X POST "$BASE/api/user/register" -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$PHONE\",\"password\":\"$PASS\",\"confirmPassword\":\"$PASS\"}" | head -c 120
echo

echo "== 4) 授予 ADMIN 角色（由 verify 脚本外层完成） =="

echo "== 5) 登录 =="
LOGIN=$(curl -s -X POST "$BASE/api/user/login" -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$PHONE\",\"password\":\"$PASS\"}")
TOKEN=$(printf '%s' "$LOGIN" | python3 -c 'import sys,json;d=json.load(sys.stdin);print((d.get("data") or {}).get("token") or "")')
if [ -z "$TOKEN" ]; then
  echo "LOGIN_FAILED: $(printf '%s' "$LOGIN" | head -c 200)"
  exit 1
fi
echo "token_length=${#TOKEN}"

echo "== 6) 已登录 GET 三态 =="
curl -s -o /tmp/r3.json -w "http=%{http_code}\n" -H "Authorization: $TOKEN" "$URL"
python3 - <<'PY'
import json
d = json.load(open('/tmp/r3.json'))['data']
print('desiredSchemaVersion =', d.get('desiredSchemaVersion'))
print('desiredError         =', d.get('desiredError'))
print('snapshotState        =', d.get('snapshotState'))
print('snapshotId           =', (d.get('snapshotId') or '')[:20])
print('observed connectors  =', [c['connectorCode'] for c in d.get('observed') or []])
print('desired              =', [(c['code'], c['desiredStatus']) for c in d.get('desired') or []])
print('effective            =', [(e['code'], e['effectiveStatus'], e['effectiveReason'], e['executable'])
                                 for e in d.get('effective') or []])
PY

echo "== 7) 写 v1（应 400） =="
curl -s -o /tmp/r4.json -w "http=%{http_code}\n" -X PUT "$URL" -H "Authorization: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"schemaVersion":"1.0","capabilities":[]}'
python3 -c "import json;d=json.load(open('/tmp/r4.json'));print('message =', (d.get('message') or '')[:140])"

echo "== 8) 写非法能力码（应 400） =="
curl -s -o /tmp/r5.json -w "http=%{http_code}\n" -X PUT "$URL" -H "Authorization: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"schemaVersion":"2.0","capabilities":[{"sourceCode":"DOUYIN","connectorCode":"DOUYIN_PUBLIC","code":"COMMENT_V2","category":"COMMENT","accessModes":["PUBLIC"],"desiredStatus":"ENABLED"}]}'
python3 -c "import json;d=json.load(open('/tmp/r5.json'));print('message =', (d.get('message') or '')[:200])"

echo "== 9) 写合法 v2（语义与迁移结果一致，不改变业务状态） =="
curl -s -o /tmp/r6.json -w "http=%{http_code}\n" -X PUT "$URL" -H "Authorization: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"schemaVersion":"2.0","capabilities":[{"sourceCode":"DOUYIN","connectorCode":"DOUYIN_PUBLIC","code":"COMMENT","category":"COMMENT","accessModes":["PUBLIC","AUTHENTICATED"],"desiredStatus":"ENABLED"},{"sourceCode":"DOUYIN","connectorCode":"DOUYIN_PUBLIC","code":"CONTENT_DETAIL","category":"CONTENT","accessModes":["PUBLIC"],"desiredStatus":"ENABLED"},{"sourceCode":"DOUYIN","connectorCode":"DOUYIN_PUBLIC","code":"KEYWORD_SEARCH","category":"SEARCH","accessModes":["PUBLIC"],"desiredStatus":"DISABLED"}]}'
python3 - <<'PY'
import json
d = json.load(open('/tmp/r6.json'))['data']
print('after write desired   =', [(c['code'], c['desiredStatus']) for c in d.get('desired') or []])
print('after write effective =', [(e['code'], e['effectiveStatus'], e['effectiveReason'])
                                 for e in d.get('effective') or []])
PY
