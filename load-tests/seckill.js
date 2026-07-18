import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://app:8080').replace(/\/$/, '');
const VUS = Number.parseInt(__ENV.VUS || '100', 10);
const VOUCHER_ID = Number.parseInt(__ENV.VOUCHER_ID || '9100', 10);
const PHONE_OFFSET = Number.parseInt(__ENV.PHONE_OFFSET || '0', 10);
const LOGIN_CODE = __ENV.LOGIN_CODE || '123456';
const LOGIN_BATCH_SIZE = Number.parseInt(__ENV.LOGIN_BATCH_SIZE || '50', 10);

const seckillRequests = new Counter('seckill_requests');
const seckillAccepted = new Counter('seckill_accepted');
const seckillOutOfStock = new Counter('seckill_out_of_stock');
const seckillUnexpected = new Counter('seckill_unexpected');
const seckillHttpSuccess = new Rate('seckill_http_success');
const seckillClassified = new Rate('seckill_classified');
const seckillRequestDuration = new Trend('seckill_request_duration', true);
const seckillRequestStartedAt = new Trend('seckill_request_started_at_ms');
const seckillRequestCompletedAt = new Trend('seckill_request_completed_at_ms');

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    seckill_burst: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    seckill_http_success: ['rate==1'],
    seckill_classified: ['rate==1'],
  },
};

function phoneFor(index) {
  const suffix = PHONE_OFFSET + index;
  if (suffix < 0 || suffix > 99999999) {
    fail(`Phone suffix is outside the supported range: ${suffix}`);
  }
  return `139${String(suffix).padStart(8, '0')}`;
}

function parseBusinessResponse(response, operation) {
  if (response.status !== 200) {
    fail(`${operation} returned HTTP ${response.status}: ${response.body}`);
  }

  let payload;
  try {
    payload = response.json();
  } catch (error) {
    fail(`${operation} returned invalid JSON: ${response.body}`);
  }
  if (payload.code !== 1) {
    fail(`${operation} failed: ${payload.msg || response.body}`);
  }
  return payload;
}

export function setup() {
  const tokens = new Array(VUS);

  for (let start = 0; start < VUS; start += LOGIN_BATCH_SIZE) {
    const end = Math.min(start + LOGIN_BATCH_SIZE, VUS);
    const codeRequests = [];
    for (let index = start; index < end; index += 1) {
      const phone = phoneFor(index);
      codeRequests.push({
        method: 'POST',
        url: `${BASE_URL}/user/user/code?phone=${encodeURIComponent(phone)}`,
        params: { tags: { endpoint: 'login-code' } },
      });
    }

    const codeResponses = http.batch(codeRequests);
    codeResponses.forEach((response, batchIndex) => {
      parseBusinessResponse(response, `Send login code for user ${start + batchIndex}`);
    });

    const loginRequests = [];
    for (let index = start; index < end; index += 1) {
      loginRequests.push({
        method: 'POST',
        url: `${BASE_URL}/user/user/login`,
        body: JSON.stringify({ phone: phoneFor(index), code: LOGIN_CODE }),
        params: {
          headers: { 'Content-Type': 'application/json' },
          tags: { endpoint: 'login' },
        },
      });
    }

    const loginResponses = http.batch(loginRequests);
    loginResponses.forEach((response, batchIndex) => {
      const payload = parseBusinessResponse(
        response,
        `Login user ${start + batchIndex}`,
      );
      const token = payload.data && payload.data.token;
      if (!token) {
        fail(`Login user ${start + batchIndex} returned no token`);
      }
      tokens[start + batchIndex] = token;
    });
  }

  return { tokens };
}

export default function (data) {
  const token = data.tokens[__VU - 1];
  if (!token) {
    fail(`No token was prepared for VU ${__VU}`);
  }

  const requestStartedAt = Date.now();
  const response = http.post(
    `${BASE_URL}/user/voucher-order/seckill/${VOUCHER_ID}`,
    null,
    {
      headers: { authentication: token },
      tags: { endpoint: 'seckill' },
    },
  );
  const requestCompletedAt = Date.now();

  let payload = {};
  try {
    payload = response.json();
  } catch (error) {
    // The checks below report malformed responses without aborting other VUs.
  }

  const httpSuccess = response.status === 200;
  const hasOrderId = /"data"\s*:\s*\d+/.test(response.body || '');
  const accepted = httpSuccess && payload.code === 1 && hasOrderId;
  const outOfStock = httpSuccess && payload.code === 0 && payload.msg === 'Out of stock';
  const classified = accepted || outOfStock;
  seckillRequests.add(1);
  seckillHttpSuccess.add(httpSuccess);
  seckillClassified.add(classified);
  seckillAccepted.add(accepted ? 1 : 0);
  seckillOutOfStock.add(outOfStock ? 1 : 0);
  seckillUnexpected.add(classified ? 0 : 1);
  seckillRequestDuration.add(response.timings.duration);
  seckillRequestStartedAt.add(requestStartedAt);
  seckillRequestCompletedAt.add(requestCompletedAt);

  check(response, {
    'seckill returned HTTP 200': () => httpSuccess,
    'seckill response was classified': () => classified,
  });
}

export function handleSummary(data) {
  const metricNames = [
    'seckill_requests',
    'seckill_accepted',
    'seckill_out_of_stock',
    'seckill_unexpected',
    'seckill_http_success',
    'seckill_classified',
    'seckill_request_duration',
    'seckill_request_started_at_ms',
    'seckill_request_completed_at_ms',
  ];
  const metrics = {};
  metricNames.forEach((name) => {
    if (data.metrics[name]) {
      metrics[name] = data.metrics[name].values || data.metrics[name];
    }
  });

  [
    'seckill_requests',
    'seckill_accepted',
    'seckill_out_of_stock',
    'seckill_unexpected',
  ].forEach((name) => {
    if (metrics[name]) {
      metrics[name] = { count: metrics[name].count };
    }
  });
  if (metrics.seckill_request_started_at_ms) {
    metrics.seckill_request_started_at_ms = {
      min: metrics.seckill_request_started_at_ms.min,
    };
  }
  if (metrics.seckill_request_completed_at_ms) {
    metrics.seckill_request_completed_at_ms = {
      max: metrics.seckill_request_completed_at_ms.max,
    };
  }

  const requests = metrics.seckill_requests ? metrics.seckill_requests.count : 0;
  const accepted = metrics.seckill_accepted ? metrics.seckill_accepted.count : 0;
  const outOfStock = metrics.seckill_out_of_stock
    ? metrics.seckill_out_of_stock.count
    : 0;
  const unexpected = metrics.seckill_unexpected ? metrics.seckill_unexpected.count : 0;
  const p95 = metrics.seckill_request_duration
    ? metrics.seckill_request_duration['p(95)']
    : 0;
  const burstStartMs = metrics.seckill_request_started_at_ms
    ? metrics.seckill_request_started_at_ms.min
    : 0;
  const burstEndMs = metrics.seckill_request_completed_at_ms
    ? metrics.seckill_request_completed_at_ms.max
    : 0;
  const burstWindowMs = Math.max(0, burstEndMs - burstStartMs);
  const seckillRequestsPerSecond = burstWindowMs > 0
    ? requests / (burstWindowMs / 1000)
    : 0;
  const summaryPath = __ENV.SUMMARY_PATH || '/results/k6-summary.json';

  return {
    stdout: `seckill requests=${requests} accepted=${accepted} out_of_stock=${outOfStock} unexpected=${unexpected} p95_ms=${p95}\n`,
    [summaryPath]: JSON.stringify({
      configuration: {
        baseUrl: BASE_URL,
        vus: VUS,
        voucherId: VOUCHER_ID,
      },
      derived: {
        burstWindowMs,
        seckillRequestsPerSecond,
      },
      metrics,
    }, null, 2),
  };
}
