package com.github.liyibo1110.caffeine.cache.stats;

import com.github.liyibo1110.caffeine.cache.RemovalCause;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.concurrent.atomic.LongAdder;

/**
 * 线程安全的StatsCounter实现
 * @author liyibo
 * @date 2026-01-06 10:51
 */
public class ConcurrentStatsCounter implements StatsCounter {
    private final LongAdder hitCount;
    private final LongAdder missCount;
    private final LongAdder loadSuccessCount;
    private final LongAdder loadFailureCount;
    private final LongAdder totalLoadTime;
    private final LongAdder evictionCount;
    private final LongAdder evictionWeight;

    public ConcurrentStatsCounter() {
        // 默认都是0
        this.hitCount = new LongAdder();
        this.missCount = new LongAdder();
        this.loadSuccessCount = new LongAdder();
        this.loadFailureCount = new LongAdder();
        this.totalLoadTime = new LongAdder();
        this.evictionCount = new LongAdder();
        this.evictionWeight = new LongAdder();
    }

    @Override
    public void recordHits(int count) {
        this.hitCount.add(count);
    }

    @Override
    public void recordMissed(int count) {
        this.missCount.add(count);
    }

    @Override
    public void recordLoadSuccess(long loadTime) {
        this.loadSuccessCount.increment();
        this.totalLoadTime.add(loadTime);
    }

    @Override
    public void recordLoadFailure(long loadTime) {
        this.loadFailureCount.increment();
        this.totalLoadTime.add(loadTime);
    }

    @Override
    public void recordEviction() {
        this.evictionCount.increment();
    }

    @Override
    public void recordEviction(int weight) {
        this.evictionCount.increment();
        this.evictionWeight.add(weight);
    }

    public void recordEviction(int weight, RemovalCause cause) {
        this.evictionCount.increment();
        this.evictionWeight.add(weight);
    }

    @Override
    public CacheStats snapshot() {
        return CacheStats.of(
            this.negativeToMaxValue(this.hitCount.sum()),
            this.negativeToMaxValue(this.missCount.sum()),
            this.negativeToMaxValue(this.loadSuccessCount.sum()),
            this.negativeToMaxValue(this.loadFailureCount.sum()),
            this.negativeToMaxValue(this.totalLoadTime.sum()),
            this.negativeToMaxValue(this.evictionCount.sum()),
            this.negativeToMaxValue(this.evictionWeight.sum()));
    }

    /**
     * 将指定的StatsCounter里面的记录值累加进来
     * @param other
     */
    public void incrementBy(@NonNull StatsCounter other) {
        CacheStats snapshot = other.snapshot();
        this.hitCount.add(snapshot.hitCount());
        this.missCount.add(snapshot.missCount());
        this.loadSuccessCount.add(snapshot.loadSuccessCount());
        this.loadFailureCount.add(snapshot.loadFailureCount());
        this.totalLoadTime.add(snapshot.totalLoadTime());
        this.evictionCount.add(snapshot.evictionCount());
        this.evictionWeight.add(snapshot.evictionWeight());
    }

    private static long negativeToMaxValue(long value) {
        return (value >= 0) ? value : Long.MAX_VALUE;
    }

    @Override
    public String toString() {
        return this.snapshot().toString();
    }
}
