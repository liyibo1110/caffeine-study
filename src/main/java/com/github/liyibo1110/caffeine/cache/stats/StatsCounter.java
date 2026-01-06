package com.github.liyibo1110.caffeine.cache.stats;

import com.github.liyibo1110.caffeine.cache.RemovalCause;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * 缓存运行期间累积统计数据，供Cache.stats方法来呈现
 * @author liyibo
 * @date 2026-01-05 15:53
 */
public interface StatsCounter {

    /**
     * 命中了缓存被调用
     */
    void recordHits(@NonNegative int count);

    /**
     * 未命中缓存时被调用
     */
    void recordMissed(@NonNegative int count);

    /**
     * 记录新条目成功被加载（例如Cache.get或Map.computeIfAbsent），且加载成功时被调用
     * 和recordMisses不同，此方法应仅由加载线程来调用
     * @param loadTime 缓存计算或者检索新值所花费的纳秒数
     */
    void recordLoadSuccess(@NonNegative long loadTime);

    /**
     * 记录新条目加载失败（例如Cache.get或Map.computeIfAbsent），但抛出异常或加载方法返回null时，应调用此方法
     * 和recordMisses不同，此方法应仅由加载线程来调用
     * @param loadTime 在发现新值不存在或者抛出异常之前，缓存计算或者检索新值所花费的纳秒数
     */
    void recordLoadFailure(@NonNegative long loadTime);

    /**
     * 条目被移除时被调用
     */
    @Deprecated
    void recordEviction();

    @Deprecated
    default void recordEviction(@NonNegative int weight) {
        recordEviction();
    }

    /**
     * 3.x版本将会改成abstract
     */
    default void recordEviction(@NonNegative int weight, RemovalCause cause) {
        recordEviction(weight);
    }

    /**
     * 返回当前计数器快照实例
     */
    @NonNull
    CacheStats snapshot();

    /**
     * 返回DisabledStatsCounter计数器的实例
     */
    static @NonNull StatsCounter disabledStatsCounter() {

    }

    /**
     * 返回GuardedStatsCounter计数器的实例
     */
    static @NonNull StatsCounter guardedStatsCounter(@NonNull StatsCounter statsCounter) {

    }
}
