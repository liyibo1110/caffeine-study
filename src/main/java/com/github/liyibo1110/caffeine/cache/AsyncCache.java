package com.github.liyibo1110.caffeine.cache;

import com.google.errorprone.annotations.CompatibleWith;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Cache接口的异步版本
 * @author liyibo
 * @date 2026-01-06 18:18
 */
public interface AsyncCache<K, V> {
    CompletableFuture<V> getIfPresent(@CompatibleWith("K") Object key);

    CompletableFuture<V> get(K key, Function<? super K, ? extends V> mappingFunction);

    CompletableFuture<V> get(K key, BiFunction<? super K, Executor, CompletableFuture<V>> mappingFunction);

    default CompletableFuture<Map<K, V>> getAll(
            Iterable<? extends K> keys,
            Function<Iterable<? extends K>, Map<K, V>> mappingFunction) {
        throw new UnsupportedOperationException();
    }

    default CompletableFuture<Map<K, V>> getAll(
            Iterable<? extends K> keys,
            BiFunction<Iterable<? extends K>, Executor, CompletableFuture<Map<K, V>>> mappingFunction) {
        throw new UnsupportedOperationException();
    }

    /**
     * 如果异步计算失败，则条目将被自动移除（？？？）
     */
    void put(K key, CompletableFuture<V> valueFuture);

    /**
     * 对映射所做的修改会直接影响到缓存
     */
    ConcurrentMap<K, CompletableFuture<V>> asMap();

    /**
     * 返回同步形式的缓存视图，对其的修改也会直接影响到异步缓存
     */
    Cache<K, V> synchronous();
}
