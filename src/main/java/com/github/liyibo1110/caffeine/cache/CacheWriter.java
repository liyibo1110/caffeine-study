package com.github.liyibo1110.caffeine.cache;

/**
 * 条目被显式创建、修改或删除时，cache会通知此Writer的实现
 * 但条目被load或计算时，不会通知Writer
 * @author liyibo
 * @date 2026-01-07 14:14
 */
@Deprecated
public interface CacheWriter<K, V> {

    /**
     * 将value写入外部资源（external resource）
     */
    void write(K key, V value);

    /**
     * 从外部资源删除key对应的value
     */
    void delete(K key, V value, RemovalCause cause);

    static <K, V> CacheWriter<K, V> disabledWriter() {
        CacheWriter<K, V> writer = (CacheWriter<K, V>)DisabledWriter.INSTANCE;
        return writer;
    }
}

enum DisabledWriter implements CacheWriter<Object, Object> {
    INSTANCE;

    @Override
    public void write(Object key, Object value) {}

    @Override
    public void delete(Object key, Object value, RemovalCause cause) {}
}
