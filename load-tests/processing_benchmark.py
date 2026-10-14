"""Measure completed jobs, not HTTP request rate, against a running Atlas Jobs API."""

import argparse
import base64
import concurrent.futures
import json
import os
import platform
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--jobs", type=int, default=20)
    parser.add_argument("--submitters", type=int, default=8)
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--worker-count", type=int, required=True)
    args = parser.parse_args()
    if args.jobs < 1 or args.submitters < 1 or args.worker_count < 1:
        parser.error("jobs, submitters, and worker-count must be positive")

    user = os.environ.get("ATLAS_SECURITY_USER", "admin")
    password = os.environ.get("ATLAS_SECURITY_PASSWORD", "admin123")
    auth = base64.b64encode(f"{user}:{password}".encode()).decode()

    def request(method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(
            args.url.rstrip("/") + path,
            data=data,
            method=method,
            headers={"Authorization": "Basic " + auth, "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=10) as response:
            return json.load(response)

    def submit(index):
        job = request("POST", "/jobs", {
            "jobType": "SLEEP_JOB",
            "payload": json.dumps({"sleepSeconds": 1}),
            "maxAttempts": 1,
        })
        return job["jobId"]

    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.submitters) as pool:
        ids = list(pool.map(submit, range(args.jobs)))
    submitted = time.monotonic()
    unfinished = set(ids)
    outcomes = {}
    deadline = started + args.timeout
    while unfinished and time.monotonic() < deadline:
        for job_id in tuple(unfinished):
            status = request("GET", "/jobs/" + job_id)["status"]
            if status in {"SUCCEEDED", "DEAD_LETTERED"}:
                outcomes[job_id] = status
                unfinished.remove(job_id)
        if unfinished:
            time.sleep(0.5)

    finished = time.monotonic()
    succeeded = sum(status == "SUCCEEDED" for status in outcomes.values())
    result = {
        "workerCount": args.worker_count,
        "jobs": args.jobs,
        "jobType": "SLEEP_JOB",
        "sleepSeconds": 1,
        "submitters": args.submitters,
        "submittedInSeconds": round(submitted - started, 3),
        "elapsedSeconds": round(finished - started, 3),
        "successfulCompletionsPerSecond": round(succeeded / (finished - started), 3),
        "succeeded": succeeded,
        "deadLettered": sum(status == "DEAD_LETTERED" for status in outcomes.values()),
        "timedOut": len(unfinished),
        "system": platform.platform(),
        "cpuCount": os.cpu_count(),
    }
    print(json.dumps(result, indent=2))
    if unfinished or succeeded != args.jobs:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
