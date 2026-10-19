package com.example.cachecoalesce.metrics;

public record DemoStats(long cacheHits, long cacheMisses, long coalescedWaiters,
                        long databaseLoads, int activeRepositoryLoads, int inFlightLoads, long cacheEntries) {
}
