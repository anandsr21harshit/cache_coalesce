package com.example.cachecoalesce.data;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class ItemRepository {
    private final JdbcTemplate jdbcTemplate;
    private final long simulatedDelayMs;
    private final AtomicLong databaseLoads = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger activeDatabaseLoads = new java.util.concurrent.atomic.AtomicInteger();

    public ItemRepository(JdbcTemplate jdbcTemplate,
                          @Value("${demo.database-delay-ms:300}") long simulatedDelayMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.simulatedDelayMs = simulatedDelayMs;
    }

    public Optional<Item> findByKey(String key) {
        databaseLoads.incrementAndGet();
        return queryByKey(key);
    }

    /** Loads the item directly, intentionally bypassing cache/single-flight for the comparison demo. */
    public Optional<Item> findByKeyWithoutCoalescing(String key) {
        databaseLoads.incrementAndGet();
        return queryByKey(key);
    }

    private Optional<Item> queryByKey(String key) {
        activeDatabaseLoads.incrementAndGet();
        try {
            if (simulatedDelayMs > 0) {
                try {
                    Thread.sleep(simulatedDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during simulated database delay", e);
                }
            }
            return jdbcTemplate.query("select item_key, item_value from items where item_key = ?",
                    rs -> rs.next() ? Optional.of(new Item(rs.getString(1), rs.getString(2))) : Optional.empty(), key);
        } finally {
            activeDatabaseLoads.decrementAndGet();
        }
    }

    public long getDatabaseLoads() {
        return databaseLoads.get();
    }

    public int getActiveDatabaseLoads() { return activeDatabaseLoads.get(); }

    public void resetDatabaseLoads() {
        databaseLoads.set(0);
    }
}
