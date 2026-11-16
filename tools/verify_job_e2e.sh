#!/bin/sh
# SOURCE-06 · 真实作业一次（在 cpi-collection 容器内执行）
# docker exec -i cpi-collection sh -s < tools/verify_job_e2e.sh
set -u
T="X-Internal-Token: ${INTERNAL_TOKEN}"
BASE=http://127.0.0.1:5001
JOB=${JOB_ID:-e2e-manual}
VIDEO=${VIDEO_ID:-7703721523349239951}
MAX=${MAX_ITEMS:-5}

curl -s -X POST "$BASE/v1/jobs" -H "$T" -H 'Content-Type: application/json' \
  -d "{\"jobId\":\"$JOB\",\"connectorCode\":\"DOUYIN_PUBLIC\",\"capabilityCode\":\"COMMENT\",\"accessMode\":\"PUBLIC\",\"externalContentId\":\"$VIDEO\",\"maxItems\":$MAX}" > /dev/null

i=0
while [ $i -lt 40 ]; do
  curl -s -H "$T" "$BASE/v1/jobs/$JOB" > /tmp/job.json
  grep -qE '"status": ?"(SUCCEEDED|FAILED|PARTIAL|CANCELLED)"' /tmp/job.json && break
  i=$((i + 1))
  sleep 3
done

python3 - <<'PY'
import json
d = json.load(open("/tmp/job.json"))
print("status        =", d["status"])
print("collected     =", d["collected"])
print("finishedReason=", d.get("finishedReason"))
print("errorCode     =", d.get("errorCode"))
print("errorMessage  =", d.get("errorMessage"))
print("--- logTail ---")
for line in d.get("logTail") or []:
    print("  ", line)
PY
