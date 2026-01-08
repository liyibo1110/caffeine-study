package com.github.liyibo1110.caffeine.cache;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

/**
 * LoadingCache接口的异步版本
 * @author liyibo
 * @date 2026-01-07 10:22
 */
public interface AsyncLoadingCache<K, V> extends AsyncCache<K, V> {
    CompletableFuture<V> get(K key);

    CompletableFuture<Map<K, V>> getAll(Iterable<? extends K> keys);

    @Override
    default ConcurrentMap<K, CompletableFuture<V>> asMap() {
        throw new UnsupportedOperationException();
    }

    @Override
    LoadingCache<K, V> synchronous();
}
