# Seckill Load Test Report

Generated: 2026-07-18 15:37:01 +08:00

## Scope

This benchmark measures the authenticated HTTP request that atomically reserves Redis stock and publishes a Redis Stream message. Seckill req/s is calculated over the burst window from the first request start to the final response completion, excluding login setup. Database persistence is asynchronous, so drain time and cross-store consistency are reported separately.

## Environment

- Host OS: Windows 10.0.19045
- CPU: 11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz (8 cores / 16 logical processors)
- Memory: 31.6 GB
- Docker Engine: 28.5.1
- Application runtime: Eclipse Temurin 17 container
- MySQL: 8.0 container
- Redis: 7 Alpine container
- k6: 0.54.0 container
- Topology: all services run on one Docker Desktop host; results are not production capacity claims
- Warm-up: 50 virtual users on a separate voucher; excluded from the table

## Results

| Concurrent users | Initial stock | Accepted | Sold out | Seckill req/s | Avg (ms) | P95 (ms) | P99 (ms) | Max (ms) | Async drain (s) | Consistency |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :---: |
| 100 | 50 | 50 | 50 | 666.67 | 93.42 | 128.52 | 129.18 | 130.14 | 1.78 | PASS |
| 500 | 250 | 250 | 250 | 998 | 115.84 | 267.97 | 408.74 | 432.42 | 2.92 | PASS |
| 1000 | 500 | 500 | 500 | 1098.9 | 57.08 | 118.72 | 132.41 | 183.11 | 8.2 | PASS |

## Consistency Checks

Every scenario sends approximately twice as many concurrent users as available stock and must satisfy all of these invariants:

- accepted requests = initial stock; remaining requests are classified as sold out
- database orders = distinct purchasing users = initial stock
- MySQL stock = Redis stock = 0 after the stream drains
- Redis buyer set exactly matches the database purchasing-user set
- Redis Stream pending count = 0 and consumer-group lag = 0
- dead-letter stream length = 0

## Reproduce

    powershell -ExecutionPolicy Bypass -File .\load-tests\run-seckill-load-test.ps1

Raw k6 summaries are stored in docs/performance/raw/.

## Interpretation

The request latency measures Redis Lua admission and Stream publishing, not synchronous MySQL insertion. Async drain is the additional time from the end of the k6 burst until every accepted order is persisted and acknowledged. Results are local single-machine measurements and should be compared only on equivalent hardware and Docker settings.
