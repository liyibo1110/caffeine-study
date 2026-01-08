package com.github.liyibo1110.caffeine.cache;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * 根据key，异步地计算或检索值，以用于填充LoadingCache或AsyncLoadingCache
 * 大多数实现只需要实现load方法即可，其它方法根据实际需要再重写
 * @author liyibo
 * @date 2026-01-07 10:52
 */
@FunctionalInterface
public interface CacheLoader<K, V> extends AsyncCacheLoader<K, V> {

    /**
     * 计算或检索key对应的value
     */
    V load(K key) throws Exception;

    default Map<K, V> loadAll(Iterable<? extends K> keys) throws Exception {
        throw new UnsupportedOperationException();
    }

    /**
     * 异步计算或检索key对应的value
     */
    @Override
    default CompletableFuture<V> asyncLoad(K key, Executor executor) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(executor);

        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.load(key);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    default CompletableFuture<Map<K, V>> asyncLoadAll(Iterable<? extends K> keys, Executor executor) {
        Objects.requireNonNull(keys);
        Objects.requireNonNull(executor);

        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.loadAll(keys);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * 计算或检索key对应的替换值，如果未找到替换值，且计算结果为null，将移除该条目
     * 当Caffeine.refreshAfterWrite刷新现有cache条目时，或调用了LoadingCache.refresh方法，会调用本方法
     * 此方法抛出的异常都会被记录，然后被忽略
     */
    default V reload(K key, V oldValue) throws Exception {
        return this.load(key);
    }

    @Override
    default CompletableFuture<V> asyncReload(K key, V oldValue, Executor executor) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(executor);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.reload(key, oldValue);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }
}
