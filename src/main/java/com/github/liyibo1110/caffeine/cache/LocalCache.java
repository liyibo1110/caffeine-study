package com.github.liyibo1110.caffeine.cache;

import com.github.liyibo1110.caffeine.cache.stats.StatsCounter;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 提供线程安全和原子性保证的基于内存的缓存
 * 注意自身是ConcurrentMap的扩展
 * @author liyibo
 * @date 2026-01-06 17:32
 */
interface LocalCache<K, V> extends ConcurrentMap<K, V> {
    boolean isRecordingStats();

    StatsCounter statsCounter();

    boolean hasRemovalListener();

    RemovalListener<K, V> removalListener();

    /**
     * 异步地发送通知给对应的RemovalListener实例
     */
    void notifyRemoval(K key, V value, RemovalCause cause);

    /**
     * 返回cache对应的Executor
     */
    Executor executor();

    /**
     * cache是否要捕获条目的写入时间
     */
    boolean hasWriteTime();

    /**
     * 返回cache对应判断过期的Ticker实例
     */
    Ticker expirationTicker();

    /**
     * 返回cache对应统计操作的Ticker实例
     */
    Ticker statsTicker();

    long estimatedSize();

    V getIfPresent(Object key, boolean recordStats);

    /**
     * 和Cache接口实现的不同之处在于，不会统计信息记录访问，也不使用淘汰策略，而是填充已知的写入时间
     */
    V getIfPresentQuietly(Object key, long[] writeTime);

    Map<K, V> getAllPresent(Iterable<?> keys);

    /**
     * 和Cache接口实现的不同之处在于，允许在插入或更新条目时，不通知写入者
     */
    V put(K key, V value, boolean notifyWriter);

    @Override
    default V compute(K key,
                      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        return this.compute(key, remappingFunction, false, true, true);
    }

    /**
     * 和ConcurrentMap的compute不同之处在于：
     * 接收额外的参数，指示是否根据操作的成功与否来记录未命中和加载统计信息
     */
    V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
              boolean recordMiss, boolean recordLoad, boolean recordLoadFailure);


    /**
     * 和ConcurrentMap的computeIfAbsent不同之处在于：
     * 接收额外的参数，指示如何记录统计信息
     */
    V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
                      boolean recordStats, boolean recordLoad);

    default void invalidateAll(Iterable<?> keys) {
        for(Object key : keys)
            this.remove(key);   // Map接口的
    }

    void cleanUp();

    /**
     * 如果记录功能开启（isRecordingStats方法），则装饰原始的mappingFunction，增加记录统计信息功能
     */
    default <T, R> Function<? super T, ? extends R> statsAware(
            Function<? super T, ? extends R> mappingFunction, boolean recordLoad) {
        if(!this.isRecordingStats())
            return mappingFunction;
        // 开始装饰
        return key -> {
            R value;
            this.statsCounter().recordMisses(1);    // 只要进来了说明之前就是未命中
            long startTime = this.statsTicker().read();
            try {
                value = mappingFunction.apply(key); // 调用原来的function
            } catch (RuntimeException | Error e) {
                this.statsCounter().recordLoadFailure(this.statsTicker().read() - startTime);
                throw e;
            }
            long loadTime = this.statsTicker().read() - startTime;
            if(recordLoad) {
                if(value == null)
                    this.statsCounter().recordLoadFailure(loadTime);
                else
                    this.statsCounter().recordLoadSuccess(loadTime);
            }
            return value;
        };
    }

    default <T, U, R> BiFunction<? super T, ? super U, ? extends R> statsAware(
            BiFunction<? super T, ? super U, ? extends R> remappingFunction) {
        return this.statsAware(remappingFunction, true, true, true);
    }

    default <T, U, R> BiFunction<? super T, ? super U, ? extends R> statsAware(
            BiFunction<? super T, ? super U, ? extends R> remappingFunction,
            boolean recordMiss, boolean recordLoad, boolean recordLoadFailure) {
        if(!this.isRecordingStats())
            return remappingFunction;
        // 开始装饰
        return (t, u) -> {
            R result;
            if((u == null) && recordMiss)
                this.statsCounter().recordMisses(1);
            long startTime = this.statsTicker().read();
            try {
                result = remappingFunction.apply(t, u);
            } catch (RuntimeException | Error e) {
                if(recordLoadFailure)
                    this.statsCounter().recordLoadFailure(this.statsTicker().read() - startTime);
                throw e;
            }
            long loadTime = this.statsTicker().read() - startTime;
            if(recordLoad) {
                if(result == null)
                    this.statsCounter().recordLoadFailure(loadTime);
                else
                    this.statsCounter().recordLoadSuccess(loadTime);
            }
            return result;
        };
    }
}
