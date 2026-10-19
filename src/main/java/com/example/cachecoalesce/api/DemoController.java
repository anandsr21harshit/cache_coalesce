package com.example.cachecoalesce.api;

import com.example.cachecoalesce.cache.CoalescingCacheService;
import com.example.cachecoalesce.data.Item;
import com.example.cachecoalesce.metrics.DemoStats;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/demo")
public class DemoController {
    private static final String DEMO_KEY = "product:demo";
    private static final int REQUEST_COUNT = 1_000;
    private final CoalescingCacheService cacheService;
    private final AtomicBoolean burstRunning = new AtomicBoolean();

    public DemoController(CoalescingCacheService cacheService) { this.cacheService = cacheService; }

    @PostMapping("/reset")
    public DemoStats reset() {
        cacheService.reset(DEMO_KEY);
        return cacheService.stats();
    }

    @GetMapping("/stats")
    public DemoStats stats() { return cacheService.stats(); }

    @PostMapping("/burst")
    public BurstResponse burst() {
        return runBurst(true);
    }

    @PostMapping("/burst-without-coalescing")
    public BurstResponse burstWithoutCoalescing() {
        return runBurst(false);
    }

    private BurstResponse runBurst(boolean coalesce) {
        if (!burstRunning.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A burst is already running");
        }
        long started = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
            CountDownLatch start = new CountDownLatch(1);
            try {
                // Each run starts cold, but cumulative counters remain intact until /reset is called.
                DemoStats before = cacheService.stats();
                cacheService.evict(DEMO_KEY);
                List<Future<Item>> futures = java.util.stream.IntStream.range(0, REQUEST_COUNT)
                        .mapToObj(i -> executor.submit(() -> {
                            ready.countDown();
                            if (!start.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("Burst start timed out");
                            return coalesce ? cacheService.get(DEMO_KEY) : cacheService.getDirectlyFromDatabase(DEMO_KEY);
                        })).toList();
                if (!ready.await(10, TimeUnit.SECONDS)) {
                    throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Not all burst callers became ready");
                }
                start.countDown();
                Set<Item> values = new HashSet<>();
                int succeeded = 0;
                for (Future<Item> future : futures) {
                    values.add(future.get(15, TimeUnit.SECONDS));
                    succeeded++;
                }
                DemoStats totals = cacheService.stats();
                return new BurstResponse(coalesce ? "coalesced" : "direct-database", REQUEST_COUNT, succeeded,
                        values.size(), values.stream().findFirst().orElse(null),
                        Duration.ofNanos(System.nanoTime() - started).toMillis(), totals, difference(totals, before));
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Burst simulation failed: " + e.getMessage(), e);
            } finally {
                start.countDown();
                executor.shutdownNow();
                burstRunning.set(false);
            }
        }
    }

    private DemoStats difference(DemoStats after, DemoStats before) {
        return new DemoStats(after.cacheHits() - before.cacheHits(), after.cacheMisses() - before.cacheMisses(),
                after.coalescedWaiters() - before.coalescedWaiters(),
                after.databaseLoads() - before.databaseLoads(), after.activeRepositoryLoads(),
                after.inFlightLoads(), after.cacheEntries());
    }

    public record BurstResponse(String mode, int requested, int succeeded, int distinctResults, Item result,
                                long elapsedMs, DemoStats totals, DemoStats runStats) { }
}
