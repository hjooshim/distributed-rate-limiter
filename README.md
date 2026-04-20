# Distributed Rate Limiter

`distributed-rate-limiter` is a Spring Boot reference project for endpoint-level rate limiting with pluggable algorithms. It combines an annotation-driven programming model (`@RateLimit`), a centralized AOP enforcement layer, and both local and Redis-backed strategies so you can compare simple in-process throttling with distributed enforcement.

## Features

| Capability | Details |
| --- | --- |
| Programming model | `@RateLimit` on controller methods, enforced by `RateLimitAspect` |
| Local strategy | `FIXED_WINDOW` using `ConcurrentHashMap` and atomic counters |
| Distributed strategies | `TOKEN_BUCKET` and `SLIDING_WINDOW` backed by Redis Lua scripts |
| Identity model | Per-client, per-endpoint limits with principal, forwarded-header, and remote-address resolution |
| Failure behavior | Structured `429 Too Many Requests` responses and fail-closed `503 Service Unavailable` for unavailable distributed backends |

## Supported Algorithms

| Algorithm | Storage | Behavior | Best fit |
| --- | --- | --- | --- |
| `FIXED_WINDOW` | Local memory | Fast and simple, but allows boundary bursts at window rollover | Single-node or low-complexity throttling |
| `TOKEN_BUCKET` | Redis hash | Allows short bursts while enforcing an average rate over time | APIs that should tolerate brief spikes |
| `SLIDING_WINDOW` | Redis sorted set | Enforces a true rolling window with stricter request accounting | Distributed workloads that need tighter fairness |

## Quick Start

### Requirements

- Java 21 or newer
- Docker only if you want to run Redis locally or execute the full Redis-backed test suite

### Run the application

```bash
./mvnw spring-boot:run
```

If port `8080` is already in use on your machine, run on a different port:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

### Exercise the local fixed-window endpoint

```bash
for i in 1 2 3 4; do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/strict
done
```

Expected behavior:

- `/api/strict` allows the first 3 requests in 10 seconds, then returns `429`

## Redis-Backed Mode

Start Redis locally:

```bash
docker run --rm -p 6379:6379 redis:7.4-alpine
```

Then start the application as usual:

```bash
./mvnw spring-boot:run
```

Try the Redis-backed demo endpoints:

```bash
for i in 1 2; do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/token-bucket/primary
done
```

```bash
for i in 1 2 3 4; do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/sliding-window/primary
done
```

## Testing

Run a fast local subset that does not require Docker:

```bash
./mvnw -Dtest=RateLimitIntegrationTest,ClientIdentityIntegrationTest,RateLimitBackendFailureIntegrationTest test
```

Run the full test suite:

```bash
./mvnw test
```

Notes:

- the focused local subset passes without Docker
- the full suite includes Redis-backed tests that use Testcontainers, so Docker must be available
- when Docker is unavailable, Redis-backed tests fail during container startup rather than application logic

## Architecture

```text
HTTP request
    -> controller method annotated with @RateLimit
    -> RateLimitAspect
    -> ClientIdentityResolver
    -> StrategyRegistry
    -> selected strategy
         -> LocalFixedWindowStrategy
         -> RedisTokenBucketStrategy
         -> RedisSlidingWindowStrategy
    -> allow request or return 429/503
```

## Limitations

- fixed window is intentionally simple and still has the classic boundary-burst tradeoff
- distributed strategies depend on Redis availability and fail closed when the backend is unreachable
- the current OTLP exporter configuration is optional; if you do not configure it for local development, you may see exporter warning logs
