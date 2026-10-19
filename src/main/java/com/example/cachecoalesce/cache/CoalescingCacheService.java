package com.example.cachecoalesce.cache;

import com.example.cachecoalesce.data.Item;
import com.example.cachecoalesce.data.ItemRepository;
import com.example.cachecoalesce.metrics.DemoStats;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.LongAdder;

@Service
public class CoalescingCacheService {
    private final ItemRepository repository;
    private final Cache<String, Item> cache;
    private final ConcurrentHashMap<String, CompletableFuture<Item>> inFlight = new ConcurrentHashMap<>();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder coalescedWaiters = new LongAdder();

    public CoalescingCacheService(ItemRepository repository,
                                  @Value("${demo.cache-ttl-seconds:10}") long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("demo.cache-ttl-seconds must be greater than zero");
        }
        this.repository = repository;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(ttlSeconds)).maximumSize(10_000).build();
    }

    public Item get(String key) {
        Item cached = cache.getIfPresent(key);
        if (cached != null) {
            cacheHits.increment();
            return cached;
        }
        cacheMisses.increment();

        CompletableFuture<Item> myFuture = new CompletableFuture<>();
        CompletableFuture<Item> existing = inFlight.putIfAbsent(key, myFuture);
        if (existing != null) {
            coalescedWaiters.increment();
            return await(existing);
        }

        try {
            // A prior loader may have populated the cache between our first lookup and registration.
            // Suppose Thread B was waiting because of OS & thread A already updated cache from DB
            // Thread B never created it's future or ran putIfAbsent()
            // So it will try to behave as a leader now
            // But we already have data in cache, so need to check in cache again
            Item populated = cache.getIfPresent(key);
            if (populated != null) {
                myFuture.complete(populated);
                return populated;
            }
            Optional<Item> loaded = repository.findByKey(key);
            Item value = loaded.orElseThrow(() -> new ItemNotFoundException(key));
            cache.put(key, value);
            myFuture.complete(value);
            return value;
        } catch (RuntimeException e) {
            myFuture.completeExceptionally(e);
            throw e;
        } finally {
            // removes the completed or failed operation.
            // The next cache miss can start a fresh fetch.
            inFlight.remove(key, myFuture);
        }
    }

    /** Direct database path used only to demonstrate the stampede without coalescing. */
    public Item getDirectlyFromDatabase(String key) {
        return repository.findByKeyWithoutCoalescing(key)
                .orElseThrow(() -> new ItemNotFoundException(key));
    }

    private Item await(CompletableFuture<Item> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for coalesced load", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("Coalesced load failed", cause);
        }
    }

    public void evict(String key) { cache.invalidate(key); }

    public void reset(String key) {
        cache.invalidate(key);
        cacheHits.reset();
        cacheMisses.reset();
        coalescedWaiters.reset();
        repository.resetDatabaseLoads();
    }

    public DemoStats stats() {
        return new DemoStats(cacheHits.sum(), cacheMisses.sum(), coalescedWaiters.sum(),
                repository.getDatabaseLoads(), repository.getActiveDatabaseLoads(), inFlight.size(), cache.estimatedSize());
    }

    public static class ItemNotFoundException extends RuntimeException {
        public ItemNotFoundException(String key) { super("No item found for key: " + key); }
    }
}
