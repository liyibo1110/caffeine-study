package com.github.liyibo1110.caffeine.cache;

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
    V get(K key);

    Map<K, V> getAll(Iterable<? extends K> keys);

    /**
     * 异步地为key加载新值
     * 如果新值加载成功，则会替换旧值，如刷新过程中有异常，则保持旧值不变
     */
    void refresh(K key);
}
