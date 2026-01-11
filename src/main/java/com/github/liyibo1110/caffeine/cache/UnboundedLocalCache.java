package com.github.liyibo1110.caffeine.cache;

import com.github.liyibo1110.caffeine.cache.stats.StatsCounter;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * LocalCache的同步无界实现版本（最简单基本的基础实现）
 * @author liyibo
 * @date 2026-01-10 14:07
 */
final class UnboundedLocalCache<K, V> implements LocalCache<K, V> {
    final RemovalListener<K, V> removalListener;
    /** 实际的底层容器 */
    final ConcurrentHashMap<K, V> data;
    final StatsCounter statsCounter;
    final boolean isRecordingStats;
    final CacheWriter<K, V> writer;
    final Executor executor;
    final Ticker ticker;

    transient Set<K> keySet;
    transient Collection<V> values;
    transient Set<Entry<K, V>> entrySet;

    UnboundedLocalCache(Caffeine<? super K, ? super V> builder, boolean async) {

    }

    @Override
    public boolean isRecordingStats() {
        return false;
    }

    @Override
    public StatsCounter statsCounter() {
        return null;
    }

    @Override
    public boolean hasRemovalListener() {
        return false;
    }

    @Override
    public RemovalListener<K, V> removalListener() {
        return null;
    }

    @Override
    public void notifyRemoval(K key, V value, RemovalCause cause) {

    }

    @Override
    public Executor executor() {
        return null;
    }

    @Override
    public boolean hasWriteTime() {
        return false;
    }

    @Override
    public Ticker expirationTicker() {
        return null;
    }

    @Override
    public Ticker statsTicker() {
        return null;
    }

    @Override
    public long estimatedSize() {
        return 0;
    }

    @Override
    public V getIfPresent(Object key, boolean recordStats) {
        return null;
    }

    @Override
    public V getIfPresentQuietly(Object key, long[] writeTime) {
        return null;
    }

    @Override
    public Map<K, V> getAllPresent(Iterable<?> keys) {
        return null;
    }

    @Override
    public V put(K key, V value, boolean notifyWriter) {
        return null;
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction, boolean recordMiss, boolean recordLoad, boolean recordLoadFailure) {
        return null;
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction, boolean recordStats, boolean recordLoad) {
        return null;
    }

    @Override
    public void cleanUp() {

    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean containsKey(Object key) {
        return false;
    }

    @Override
    public boolean containsValue(Object value) {
        return false;
    }

    @Override
    public V get(Object key) {
        return null;
    }

    @Override
    public V put(K key, V value) {
        return null;
    }

    @Override
    public V remove(Object key) {
        return null;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {

    }

    @Override
    public void clear() {

    }

    @Override
    public Set<K> keySet() {
        return null;
    }

    @Override
    public Collection<V> values() {
        return null;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return null;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return null;
    }

    @Override
    public boolean remove(Object key, Object value) {
        return false;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        return false;
    }

    @Override
    public V replace(K key, V value) {
        return null;
    }
}
