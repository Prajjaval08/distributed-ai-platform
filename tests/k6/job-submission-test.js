import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const jobCreationDuration = new Trend('job_creation_duration');

const TOKEN = __ENV.TOKEN;

export const options = {
  scenarios: {
    ramp_diagnostic: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
  { duration: '10s', target: 20 },
  { duration: '20s', target: 20 },
  { duration: '5s', target: 0 },
         ],
      exec: 'submitJob',
    },
  },

  thresholds: {
    http_req_duration: ['p(95)<1000'],
    errors: ['rate<0.05'],
  },
};

export function setup() {
  if (!TOKEN) {
    throw new Error(
      'TOKEN is missing. Set it with: $env:TOKEN="your-jwt"'
    );
  }
}

export function submitJob() {
  const payload = JSON.stringify({
  prompt: `E2E-TEST-20VU-${__VU}-${__ITER}`,
    model: 'image-model-v1',
    priority: 'NORMAL',
    parameters: {},
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${TOKEN}`,
      'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`,
    },
  };

  const res = http.post(
    'http://localhost/api/v1/jobs',
    payload,
    params
  );

  // Print the actual error status and response body.
  if (res.status !== 202) {
    console.error(
      `VU=${__VU} ITER=${__ITER} STATUS=${res.status} BODY=${res.body}`
    );
  }

  let body = {};

  try {
    body = res.json();
  } catch (error) {
    // Response was not JSON.
  }

  const success = check(res, {
    'status is 202': (r) => r.status === 202,
    'has jobId': () => body.jobId !== undefined,
  });

  errorRate.add(!success);
  jobCreationDuration.add(res.timings.duration);

  sleep(0.5);
}