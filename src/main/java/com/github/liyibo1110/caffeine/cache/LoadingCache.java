package com.github.liyibo1110.caffeine.cache;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * 在Cache的基础上，值是由缓存自动加载并存储的
 * @author liyibo
 * @date 2026-01-06 18:13
 */
public interface LoadingCache<K, V> extends Cache<K, V> {

    /**
     * 根据key返回对应的value，如有必要，将会从CacheLoader.load(Object)中获取value
     */
    @Nullable V get(@NonNull K key);

    @NonNull Map<@NonNull K, @NonNull V> getAll(@NonNull Iterable<? extends @NonNull K> keys);

    /**
     * 异步地为key加载新值
     * 如果新值加载成功，则会替换旧值，如刷新过程中有异常，则保持旧值不变
     */
    void refresh(@NonNull K key);
}
