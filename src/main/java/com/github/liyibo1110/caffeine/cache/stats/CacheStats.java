package com.github.liyibo1110.caffeine.cache.stats;

import com.google.errorprone.annotations.Immutable;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * 计数器快照
 * @author liyibo
 * @date 2026-01-05 16:34
 */
@Immutable
public final class CacheStats {
    private static final CacheStats EMPTY_STATS = CacheStats.of(0L, 0L, 0L, 0L,0L, 0L, 0L);
    private final long hitCount;
    private final long missCount;
    private final long loadSuccessCount;
    private final long loadFailureCount;
    private final long totalLoadTime;
    private final long evictionCount;
    private final long evictionWeight;

    @Deprecated
    public CacheStats(@NonNegative long hitCount,
                      @NonNegative long missCount,
                      @NonNegative long loadSuccessCount,
                      @NonNegative long loadFailureCount,
                      @NonNegative long totalLoadTime,
                      @NonNegative long evictionCount) {
        this(hitCount, missCount, loadSuccessCount, loadFailureCount, totalLoadTime, evictionCount, 0L);
    }

    @Deprecated
    public CacheStats(@NonNegative long hitCount,
                      @NonNegative long missCount,
                      @NonNegative long loadSuccessCount,
                      @NonNegative long loadFailureCount,
                      @NonNegative long totalLoadTime,
                      @NonNegative long evictionCount,
                      @NonNegative long evictionWeight) {
        if((hitCount < 0) || (missCount < 0) ||
           (loadSuccessCount < 0) || (loadFailureCount < 0) ||
           (totalLoadTime < 0) || (evictionCount < 0) || (evictionWeight < 0)) {
            throw new IllegalArgumentException();
        }
        this.hitCount = hitCount;
        this.missCount = missCount;
        this.loadSuccessCount = loadSuccessCount;
        this.loadFailureCount = loadFailureCount;
        this.totalLoadTime = totalLoadTime;
        this.evictionCount = evictionCount;
        this.evictionWeight = evictionWeight;
    }

    public static CacheStats of(@NonNegative long hitCount,
                                @NonNegative long missCount,
                                @NonNegative long loadSuccessCount,
                                @NonNegative long loadFailureCount,
                                @NonNegative long totalLoadTime,
                                @NonNegative long evictionCount,
                                @NonNegative long evictionWeight) {
        return new CacheStats(hitCount, missCount,
                loadSuccessCount, loadFailureCount,
                totalLoadTime, evictionCount, evictionWeight);
    }

    @NonNull
    public static CacheStats empty() {
        return EMPTY_STATS;
    }

    @NonNegative
    public long requestCount() {
        return this.saturatedAdd(this.hitCount, this.missCount);
    }

    @NonNegative
    public long hitCount() {
        return hitCount;
    }

    @NonNegative
    public double hitRate() {
        long requestCount = this.requestCount();
        return (requestCount == 0) ? 1.0D : (double)this.hitCount / requestCount;
    }

    @NonNegative
    public long missCount() {
        return missCount;
    }

    @NonNegative
    public double missRate() {
        long requestCount = this.requestCount();
        return (requestCount == 0) ? 1.0D : (double)this.missCount / requestCount;
    }

    @NonNegative
    public long loadCount() {
        return this.saturatedAdd(this.loadSuccessCount, this.loadFailureCount);
    }

    @NonNegative
    public long loadSuccessCount() {
        return this.loadSuccessCount;
    }

    @NonNegative
    public long loadFailureCount() {
        return this.loadFailureCount;
    }

    @NonNegative
    public double loadFailureRate() {
        long totalLoadCount = this.saturatedAdd(this.loadSuccessCount, this.loadFailureCount);
        return (totalLoadCount == 0) ? 0.0D : (double)this.loadFailureCount / totalLoadCount;
    }

    @NonNegative
    public long totalLoadTime() {
        return this.totalLoadTime;
    }

    /**
     * 返回平均load时长
     */
    @NonNegative
    public double averageLoadPenalty() {
        long totalLoadCount = this.saturatedAdd(this.loadSuccessCount, this.loadFailureCount);
        return (totalLoadCount == 0) ? 0.0D : (double)this.totalLoadTime / totalLoadCount;
    }

    @NonNegative
    public long evictionCount() {
        return this.evictionCount;
    }

    @NonNegative
    public long evictionWeight() {
        return this.evictionWeight;
    }

    public CacheStats plus(@NonNull CacheStats other) {
        return CacheStats.of(
            this.saturatedAdd(hitCount, other.hitCount),
            this.saturatedAdd(missCount, other.missCount),
            this.saturatedAdd(loadSuccessCount, other.loadSuccessCount),
            this.saturatedAdd(loadFailureCount, other.loadFailureCount),
            this.saturatedAdd(totalLoadTime, other.totalLoadTime),
            this.saturatedAdd(evictionCount, other.evictionCount),
            this.saturatedAdd(evictionWeight, other.evictionWeight));
    }

    public CacheStats minus(@NonNull CacheStats other) {
        return CacheStats.of(
            Math.max(0L, this.saturatedSubtract(hitCount, other.hitCount)),
            Math.max(0L, this.saturatedSubtract(missCount, other.missCount)),
            Math.max(0L, this.saturatedSubtract(loadSuccessCount, other.loadSuccessCount)),
            Math.max(0L, this.saturatedSubtract(loadFailureCount, other.loadFailureCount)),
            Math.max(0L, this.saturatedSubtract(totalLoadTime, other.totalLoadTime)),
            Math.max(0L, this.saturatedSubtract(evictionCount, other.evictionCount)),
            Math.max(0L, this.saturatedSubtract(evictionWeight, other.evictionWeight)));
    }

    /**
     * 求和，如果上下溢出则返回最大或最小值
     */
    private static long saturatedAdd(long a, long b) {
        long naiveSum = a + b;
        if((a ^ b) < 0 | (a ^ naiveSum) >= 0)   // 没有溢出则直接返回计算结果
            return naiveSum;
        return Long.MAX_VALUE + ((naiveSum >>> (Long.SIZE - 1)) ^ 1);
    }

    private static long saturatedSubtract(long a, long b) {
        long naiveDifference = a - b;
        if((a ^ b) >= 0 | (a ^ naiveDifference) >= 0)
            return naiveDifference;
        return Long.MAX_VALUE + ((naiveDifference >>> (Long.SIZE - 1)) ^ 1);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.hitCount, this.missCount, this.loadSuccessCount,
                this.loadFailureCount, this.totalLoadTime, this.evictionCount, this.evictionWeight);
    }

    @Override
    public boolean equals(Object obj) {
        if(obj == this)
            return true;
        else if(!(obj instanceof CacheStats))
            return false;
        CacheStats other = (CacheStats)obj;
        return this.hitCount == other.hitCount &&
            this.missCount == other.missCount &&
            this.loadSuccessCount == other.loadSuccessCount &&
            this.loadFailureCount == other.loadFailureCount &&
            this.totalLoadTime == other.totalLoadTime &&
            this.evictionCount == other.evictionCount &&
            this.evictionWeight == other.evictionWeight;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + '{'
            + "hitCount=" + this.hitCount + ", "
            + "missCount=" + this.missCount + ", "
            + "loadSuccessCount=" + this.loadSuccessCount + ", "
            + "loadFailureCount=" + this.loadFailureCount + ", "
            + "totalLoadTime=" + this.totalLoadTime + ", "
            + "evictionCount=" + this.evictionCount + ", "
            + "evictionWeight=" + this.evictionWeight
            + '}';
    }
}
