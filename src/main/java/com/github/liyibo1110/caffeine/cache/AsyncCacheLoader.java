package com.github.liyibo1110.caffeine.cache;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 根据key，异步地计算或检索值，以用于填充AsyncLoadingCache
 * 大多数实现只需要实现asyncLoad方法即可，其它方法根据实际需要再重写
 * @author liyibo
 * @date 2026-01-07 10:32
 */
@FunctionalInterface
public interface AsyncCacheLoader<K, V> {

    /**
     * 异步计算或检索key对应的value
     */
    CompletableFuture<V> asyncLoad(K key, Executor executor);

    default CompletableFuture<Map<K, V>> asyncLoadAll(
            Iterable<? extends K> keys, Executor executor) {
        throw new UnsupportedOperationException();
    }

    /**
     * 异步计算或检索key对应的替换值，如果未找到替换值，且计算结果为null，将移除该条目
     * 当Caffeine.refreshAfterWrite刷新现有cache条目时，或调用了LoadingCache.refresh方法，会调用本方法
     * 此方法抛出的异常都会被记录，然后被忽略
     */
    default CompletableFuture<V> asyncReload(K key, V oldValue, Executor executor) {
        return this.asyncLoad(key, executor);
    }
}
