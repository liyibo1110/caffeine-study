package com.github.liyibo1110.caffeine.cache.stats;

/**
 * 不进行任何记录的StatsCounter实现
 * @author liyibo
 * @date 2026-01-06 10:20
 */
enum DisabledStatsCounter implements StatsCounter {
    INSTANCE;

    @Override
    public void recordHits(int count) {}

    @Override
    public void recordMisses(int count) {}

    @Override
    public void recordLoadSuccess(long loadTime) {}

    @Override
    public void recordLoadFailure(long loadTime) {}

    @Override
    public void recordEviction() {}

    @Override
    public CacheStats snapshot() {
        return CacheStats.empty();
    }

    @Override
    public String toString() {
        return this.snapshot().toString();
    }
}
