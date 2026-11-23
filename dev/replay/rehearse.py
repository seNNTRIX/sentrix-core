"""Submit three measured periods from the linux-v1 catalog delay recording to the local rehearsal stack."""
import json
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

BASE = "http://127.0.0.1:5177"
MODEL = "http://127.0.0.1:8001"
DEMO = Path(__file__).resolve().parents[3] / "sentrix-model-server/demo"
CASE = "lv1_linux-v1-delay-front-product-r02"
NAME = "실제 실험 기록 재생 · Linux Online Boutique 반복 2"


def api(url, body=None):
    payload = None if body is None else json.dumps(body).encode()
    with urlopen(Request(url, data=payload, headers={"Content-Type": "application/json"}), timeout=30) as response:
        return json.load(response)


def key(label):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "linuxv1-b-fold2/" + CASE + "/" + label))


def main():
    health = api(MODEL + "/health")
    calibration = json.loads((DEMO / "model-b-fold2/calibration.json").read_text())
    if (health["onlineFeatureStatus"] != "FILE_INPUT_ONLY" or health["threshold"] != calibration["threshold"]
            or health["modelVersion"] != calibration["model_version"]):
        raise RuntimeError("재생 모델 및 리눅스 임계값 설정을 확인하세요.")
    root = BASE + "/api/v1/environments"
    items = api(root)["items"]
    if not items:
        items = [api(root, {"name": NAME, "namespace": "sentrix"})]
    if len(items) != 1 or (items[0]["id"], items[0]["name"], items[0]["namespace"]) != (1, NAME, "sentrix"):
        raise RuntimeError("재생 전용 환경이 아닙니다.")
    cases = json.loads((DEMO / "replay-cases.json").read_text())["cases"]
    case = next(c for c in cases if c["case_id"] == CASE)
    times = next(c for c in json.loads((DEMO / "demo-times-b-fold2.json").read_text()) if c["case_id"] == CASE)
    incident = api(root + "/1/incidents", {
        "requestKey": key("incident"), "service": "frontend",
        "title": "실제 실험 기록 재생 · 상품 조회 호출 지연", "occurredAt": case["onset"],
        "description": "반복 2는 모델 학습에서 제외했습니다. 리눅스에서 기록한 호출 지연 실험입니다. 분석 작업을 선택하면 당시 30초 구간의 모델 판정과 근거를 확인할 수 있습니다."
    })
    jobs = root + "/1/incidents/" + incident["id"] + "/rca-jobs"
    # First pair of recorded windows with both starts strictly after removal.
    files = {int(p.stem) for p in (DEMO / "replay/1").glob("*.json")}
    last = datetime.fromisoformat(case["last_window_end"].replace("Z", "+00:00")).timestamp()
    recovered = min(t for t in files if case["removal_unix"] < t - 40 and t <= last and t - 10 in files)
    stages = [("장애 전", times["before"]["windowEnd_unix"]), ("장애 중", times["during"]["windowEnd_unix"]), ("제거 후", recovered)]
    results = []
    for label, end in stages:
        instant = datetime.fromtimestamp(end, timezone.utc).isoformat()
        job = api(jobs, {"requestKey": key(instant), "windowEnd": instant})
        job_url = jobs + "/" + job["id"]
        deadline = time.monotonic() + 120
        while job["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
            time.sleep(1)
            job = api(job_url)
        if job["status"] != "SUCCEEDED":
            raise RuntimeError(f"{label}: {job['status']} {job['reason']}")
        result = api(job_url + "/result")
        if result["jobId"] != job["id"] or datetime.fromisoformat(result["windowEnd"].replace("Z", "+00:00")).timestamp() != end:
            raise RuntimeError("분석 구간 불일치")
        results.append({"stage": label, "url": f"{BASE}/#B4-Incident?incidentId={incident['id']}&jobId={job['id']}", "result": result})
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
