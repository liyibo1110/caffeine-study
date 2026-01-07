package com.github.liyibo1110.caffeine.cache;

import com.google.errorprone.annotations.CompatibleWith;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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

    @Nullable
    CompletableFuture<V> getIfPresent(@NonNull @CompatibleWith("K") Object key);

    @NonNull
    CompletableFuture<V> get(@NonNull K key,
                             @NonNull Function<? super K, ? extends V> mappingFunction);

    @NonNull
    CompletableFuture<V> get(@NonNull K key,
                             @NonNull BiFunction<? super K, Executor, ? extends V> mappingFunction);


    @NonNull
    default CompletableFuture<Map<K, V>> getAll(
            @NonNull Iterable<? extends @NonNull K> keys,
            @NonNull Function<Iterable<? extends @NonNull K>, @NonNull Map<K, V>> mappingFunction) {
        throw new UnsupportedOperationException();
    }

    @NonNull
    default CompletableFuture<Map<K, V>> getAll(
            @NonNull Iterable<? extends @NonNull K> keys,
            @NonNull BiFunction<Iterable<? extends @NonNull K>, Executor, @NonNull Map<K, V>> mappingFunction) {
        throw new UnsupportedOperationException();
    }

    /**
     * 如果异步计算失败，则条目将被自动移除（？？？）
     */
    void put(@NonNull K key, @NonNull CompletableFuture<V> valueFuture);

    /**
     * 对映射所做的修改会直接影响到缓存
     */
    @NonNull
    ConcurrentMap<@NonNull K, @NonNull CompletableFuture<V>> asMap();

    /**
     * 返回同步形式的缓存视图，对其的修改也会直接影响到异步缓存
     */
    @NonNull
    Cache<K, V> synchronous();
}
