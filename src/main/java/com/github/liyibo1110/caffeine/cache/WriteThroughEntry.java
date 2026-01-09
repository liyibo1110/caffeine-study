package com.github.liyibo1110.caffeine.cache;

import java.util.AbstractMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

/**
 * 写穿透版本的Entry，特点如同其名字，对Entry的写入会直接影响到原始的缓存视图
 * @author liyibo
 * @date 2026-01-09 22:40
 */
final class WriteThroughEntry<K, V> extends AbstractMap.SimpleEntry<K, V> {
    static final long serialVersionUID = 1;
    /** 原始的cache视图（可写） */
    private final ConcurrentMap<K, V> map;

    public WriteThroughEntry(ConcurrentMap<K, V> map, K key, V value) {
        super(key, value);
        this.map = Objects.requireNonNull(map);
    }

    @Override
    public V setValue(V value) {
        this.map.put(this.getKey(), value);
        return super.setValue(value);
    }

    Object writeReplace() {
        return new AbstractMap.SimpleEntry<>(this);
    }
}
