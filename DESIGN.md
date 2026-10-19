# Cache Request Coalescing POC — Design Spec

## 1. Goal

Demonstrate protection against a cache stampede: after a cache entry expires, 1,000 concurrent requests for the same key should cause exactly one database load. The other requests wait for that load and receive the same value. The result is then cached for subsequent requests.

This is a single-process, in-memory proof of concept. It does not claim coordination across multiple application instances.

## 2. Technology

- Java 21
- Spring Boot 3.x
- Spring Web for a small HTTP API
- Spring JDBC (or Spring Data JPA) with H2 in-memory database
- Caffeine in-memory cache with configurable TTL
- JUnit 5 and Spring test support for concurrency verification
- Maven or Gradle (choose Maven for the initial POC unless the repository establishes another convention)

## 3. Scope and assumptions

- One logical cache key is used for the main demonstration, such as `product:demo`.
- The data source is an H2 table containing a demo record. A repository method reads that record and increments a load counter so the coalescing behavior is observable.
- Cache is local to one JVM; no Redis, distributed locks, messaging, or cluster coordination.
- The demonstration should be repeatable: an operator can expire/clear the key and trigger the 1,000-request burst again.
- Requests are concurrent within the application. A separate load generator is optional; a test is the authoritative demonstration.

## 4. Proposed request flow

```text
HTTP request for key
        |
        v
Read cache ---- hit ----> return cached value
        |
      miss
        v
Join/create in-flight load for key
        |
  existing future? ---- yes ----> wait for its result
        |
        no (this request is leader)
        v
Read from H2 once -> populate cache -> complete shared future
        |
        v
All waiting requests receive the value
```

### Coalescing mechanism

Use a `ConcurrentHashMap<CacheKey, CompletableFuture<Value>>` as a per-key in-flight registry. On a miss, use `computeIfAbsent` to establish one future and let only the thread that created it perform the database load. Other callers attach to / wait on that future. Once the load finishes, cache the value, complete the future, and remove the in-flight entry.

Implementation must ensure cleanup occurs on both success and failure. On failure, complete the shared future exceptionally and remove it so a later request can retry. Do not leave a failed or completed future in the registry indefinitely. Coordinate cache lookup and in-flight registration carefully to avoid a race where a caller sees a miss just as the leader populates the cache and removes its future; recheck the cache after becoming leader (or use an equivalent atomic loading pattern).

Waiting should not block the Servlet request threads in a way that prevents the leader from running. For a simple POC, a shared future with blocking `get/join` is acceptable if the request executor is sized for the 1,000-request scenario and the leader performs its load on the winning request thread. Document this limitation. An asynchronous controller/executor can be used if the demo needs to avoid tying up request threads.

## 5. API and demo controls

Suggested endpoints:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/items/{key}` | Return cached or database-loaded item; requests coalesce on a miss. |
| `POST` | `/api/demo/reset` | Clear the demo key/cache and reset observable counters. |
| `POST` | `/api/demo/burst` | Evict the demo key, launch 1,000 concurrent calls through the cache service, and return the result summary and stats. |
| `POST` | `/api/demo/burst-without-coalescing` | Launch the same 1,000 callers directly against H2, bypassing cache and in-flight coordination, as a stampede baseline. |
| `GET` | `/api/demo/stats` | Return cache hits, misses, coalesced waiters, DB load count, and current in-flight count. |

The endpoint should return the same data to all callers. Keep demo controls clearly isolated from the core cache service; optionally guard reset endpoints with a development profile.

### How `/api/demo/burst` simulates 1,000 requests

This endpoint is an in-process concurrency harness. It simulates 1,000 independent callers of the cache service; it does not open 1,000 HTTP connections to the application.

1. Clear/evict the selected demo key and reset the POC counters before the run.
2. Submit 1,000 tasks to a dedicated, bounded demo executor. Each task calls the same public cache-service method used by `GET /api/items/{key}`.
3. Have each task wait on a shared `CountDownLatch` start gate. After all tasks are ready (or a preparation timeout is reached), release the gate so they contend for the cold key together.
4. The first caller becomes the loader; the rest join the in-flight future and wait for its result. A configurable simulated DB delay (for example, 300 ms) keeps the load in flight long enough for callers to coalesce.
5. Collect task results with a bounded overall timeout. Return the number of successful callers, distinct result values, elapsed time, and the stats counters. On timeout or task failure, return a clear error summary and ensure executor resources are not leaked.

Use a dedicated executor sized to allow the intended simultaneous callers to reach the start gate; do not use the same small pool to both run the endpoint and all its waiting tasks. Bound the executor queue and reject overlapping burst runs (or otherwise cap them) so repeated demo calls cannot create unbounded work. The endpoint should be development/demo-only because it intentionally creates a large amount of concurrent work. A separate external HTTP load test can later verify server-thread and network behavior, but it is not required to demonstrate the single-flight service logic.

Expected response for a successful burst: 1,000 successful calls, one distinct result, one database load, and approximately 999 coalesced waiters. Exact miss/waiter counters may vary slightly with scheduling and the counter definitions; the database-load count is the key invariant.

### Suggested response shape for stats

```json
{
  "cacheHits": 0,
  "cacheMisses": 1000,
  "coalescedWaiters": 999,
  "databaseLoads": 1,
  "inFlightLoads": 0
}
```

Counter definitions should be explicit: a miss is an initial cache miss observed by a request; a coalesced waiter is a request that joined an already-running load; a database load is an actual repository invocation. These counters are diagnostic and need not use a production metrics framework in the POC.

## 6. Cache behavior

- Configure a short TTL (for example, 10 seconds) for quick manual demonstrations, configurable through application properties.
- Provide explicit eviction/reset for deterministic tests and demos; do not rely only on sleeping until TTL expiry.
- Use the same cache key normalization for lookups, in-flight coordination, and eviction.
- Decide whether null/missing records are cached. For the POC, return a not-found response and do not cache null unless explicitly needed.

## 7. Database behavior

- H2 runs in-memory and is initialized with one demo row using schema/data scripts or migration tooling.
- Add a small configurable delay in the repository/service demo load (for example, 200–500 ms) to make concurrent overlap visible. Keep it injectable/disableable and label it as simulation delay, not database work.
- Count actual repository loads at the repository boundary or immediately around the query so the `databaseLoads == 1` assertion measures real load attempts.

## 8. Concurrency demonstration

The primary demonstration should launch 1,000 callers behind a start gate (e.g. `CountDownLatch`) against the same expired/cleared key. Avoid sequential calls and avoid starting requests gradually; the barrier makes the burst reproducible. Ensure the caller pool can run the intended concurrency without deadlock. For an HTTP-level demonstration, configure the server request executor accordingly or use a client/load generator with concurrency 1,000; the in-process service test is simpler and deterministic.

Expected outcome after a cold burst:

- 1,000 callers complete successfully with identical item data.
- Exactly 1 actual database load.
- 999 callers join the in-flight load (assuming one leader).
- The cache contains the result; a subsequent request is a cache hit and does not query H2.

## 9. Failure and edge-case behavior

- If the database load fails, every caller waiting on that load observes the same failure; the in-flight entry is cleared so a later request can retry.
- A caller interruption should not cancel the shared load for all other callers. Restore the interrupt flag and translate the interruption at the API boundary.
- A configurable wait/load timeout may be added to prevent unbounded waits; timeout policy must not accidentally cancel shared work needed by other requests.
- Coalescing applies per key: requests for different keys may load independently.
- Expiry while a load is already in progress must not start a second load for that key.

## 10. Suggested package structure

```text
com.example.cachecoalesce
  api/                 ItemController, DemoController, response DTOs
  cache/               CoalescingCacheService, in-flight coordination
  data/                ItemRepository, H2 schema/data
  config/              Cache and executor configuration
  metrics/             POC counters/stats DTO
```

Keep the single-flight logic in a service that can be tested without HTTP. The controller should not implement caching or synchronization.

## 11. Acceptance criteria

1. Application starts on Java 21 with H2 and the in-memory cache configured.
2. A cold request loads the record once and caches it.
3. Concurrent same-key misses share one database load.
4. A 1,000-caller burst returns the same value to all callers and reports exactly one DB load.
5. A subsequent request is served from cache.
6. After TTL expiry or explicit eviction, the next burst again results in one DB load.
7. A failed load does not poison the in-flight registry; a later request can retry.
8. Stats make cache hits, misses, waiters, and database loads visible.

## 12. Known limitation

The in-flight map is JVM-local. If the service runs on multiple instances, each instance can elect its own leader and each can hit the database once. Distributed coalescing would require shared coordination (for example, a distributed cache/lock) and is outside this POC.

## 13. Implementation sequence (after design approval)

1. Create the Spring Boot project and H2 demo data.
2. Implement the repository and observable load counter/simulation delay.
3. Implement the cache service and per-key in-flight future coordination.
4. Add API and reset/stats controls.
5. Add a deterministic concurrent test using a start barrier and assert one database load.
6. Document how to run the app and reproduce the 1,000-request demo.
