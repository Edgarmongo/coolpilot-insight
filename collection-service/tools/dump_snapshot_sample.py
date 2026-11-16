import json
import sys

sys.path.insert(0, '/app/collection-service')

from app.registry.bootstrap import build_registry

snap = build_registry().snapshot()
envelope = {
    "code": 0,
    "msg": "ok",
    "data": snap,
    "error": None,
    "meta": {"traceId": "sample"},
}
print(json.dumps(envelope, ensure_ascii=False, indent=2))
