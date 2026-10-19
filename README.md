# Cache Coalescing POC

Java 21 / Spring Boot demonstration of per-key request coalescing with Caffeine and an H2 in-memory database.

## Run

```sh
mvn spring-boot:run
```

The application listens on port 8080. The demo record is `product:demo`; its simulated database load takes 300 ms by default and the cache TTL is 10 seconds.

Open [http://localhost:8080/](http://localhost:8080/) for the live dashboard. It polls stats while the burst runs and graphs the repository load count. Since H2 is an in-memory database, this shows application-level read attempts rather than an external database's CPU/disk metrics.

## Demonstrate the 1,000-caller burst

```sh
curl -X POST http://localhost:8080/api/demo/burst
```

The endpoint evicts the demo key, prepares 1,000 virtual-thread callers behind a start gate, then releases them together to call the same cache service method used by the item endpoint. A successful run should add one database load. The coalesced waiter count should be close to 999; exact scheduling can affect diagnostic counters. Counters are cumulative until `POST /api/demo/reset`; each burst response also includes its own `runStats` delta.

To compare against the stampede without coalescing:

```sh
curl -X POST http://localhost:8080/api/demo/burst-without-coalescing
```

This evicts the demo key and launches 1,000 callers directly against the repository, bypassing both cache and single-flight coordination. It should add 1,000 database loads. The simulated delay makes this endpoint intentionally slower and creates 1,000 concurrent repository loads; use it only for the local POC.

Other endpoints:

- `GET /api/items/product:demo` — fetch the demo item.
- `POST /api/demo/reset` — evict the demo item and reset counters.
- `GET /api/demo/stats` — read current counters.

Configuration is in `src/main/resources/application.properties` (`demo.cache-ttl-seconds`, `demo.database-delay-ms`). The in-flight registry and cache are process-local; multiple application instances can each load once.
