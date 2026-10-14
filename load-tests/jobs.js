import http from "k6/http";
import { check } from "k6";
import encoding from "k6/encoding";

const BASE_URL = __ENV.BASE_URL || "http://host.docker.internal:8080";
const USER = __ENV.ATLAS_SECURITY_USER || "admin";
const PASSWORD = __ENV.ATLAS_SECURITY_PASSWORD || "admin123";

export const options = {
  scenarios: {
    submit_jobs: {
      executor: "constant-arrival-rate",
      rate: 150,
      timeUnit: "1s",
      duration: "3m",
      preAllocatedVUs: 200,
      maxVUs: 1000
    }
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(50)<200", "p(95)<500", "p(99)<1000"]
  },
  discardResponseBodies: true
};

export default function () {
  const payload = JSON.stringify({ sleepSeconds: 1 });
  const body = JSON.stringify({
    jobType: "SLEEP_JOB",
    payload: payload,
    maxAttempts: 3
  });

  const res = http.post(`${BASE_URL}/jobs`, body, {
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Basic ${encoding.b64encode(`${USER}:${PASSWORD}`)}`,
      "Idempotency-Key": `${__VU}-${__ITER}-${Date.now()}`
    }
  });

  check(res, { "201 Created": r => r.status === 201 });
}
