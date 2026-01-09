package com.github.liyibo1110.caffeine.cache;

/**
 * @author liyibo
 * @date 2026-01-09 10:08
 */
abstract class BoundedLocalCache<K, V> {
    static final long MAXIMUM_EXPIRY = (Long.MAX_VALUE >> 1); // 150年
}
