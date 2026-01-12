package com.github.liyibo1110.caffeine.cache;

import com.github.liyibo1110.caffeine.cache.stats.StatsCounter;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

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
        this.data = new ConcurrentHashMap<>(builder.getInitialCapacity());
        this.statsCounter = builder.getStatsCounterSupplier().get();
        this.removalListener = builder.getRemovalListener(async);
        this.isRecordingStats = builder.isRecordingStats();
        this.writer = builder.getCacheWriter(async);
        this.executor = builder.getExecutor();
        this.ticker = builder.getTicker();
    }

    @Override
    public boolean hasWriteTime() {
        return false;
    }

    /* --------------- Cache接口相关实现 --------------- */

    @Override
    public V getIfPresent(Object key, boolean recordStats) {
        V value = this.data.get(key);
        if(recordStats) {
            if(value == null)
                this.statsCounter.recordMisses(1);
            else
                this.statsCounter.recordHits(1);
        }
        return value;
    }

    @Override
    public V getIfPresentQuietly(Object key, long[] writeTime) {
        return this.data.get(key);
    }

    @Override
    public long estimatedSize() {
        return this.data.mappingCount();
    }

    @Override
    public Map<K, V> getAllPresent(Iterable<?> keys) {
        Set<Object> uniqueKeys = new LinkedHashSet<>();
        for(Object key : keys)
            uniqueKeys.add(key);

        int misses = 0;
        Map<Object, Object> result = new LinkedHashMap<>(uniqueKeys.size());
        for(Object key : keys) {
            Object value = this.data.get(key);
            if(value == null)
                misses++;
            else
                result.put(key, value);
        }
        this.statsCounter.recordMisses(misses);
        this.statsCounter.recordHits(result.size());
        Map<K, V> castedResult = (Map<K, V>)result;
        return Collections.unmodifiableMap(castedResult);
    }

    @Override
    public void cleanUp() {}

    @Override
    public StatsCounter statsCounter() {
        return this.statsCounter;
    }

    @Override
    public boolean hasRemovalListener() {
        return this.removalListener != null;
    }

    @Override
    public RemovalListener<K, V> removalListener() {
        return this.removalListener;
    }

    @Override
    public void notifyRemoval(K key, V value, RemovalCause cause) {
        Objects.requireNonNull(this.removalListener(), "Notification should be guarded with a check");
        this.executor.execute(() -> removalListener().onRemoval(key, value, cause));
    }

    @Override
    public boolean isRecordingStats() {
        return this.isRecordingStats;
    }

    @Override
    public Executor executor() {
        return this.executor;
    }

    @Override
    public Ticker expirationTicker() {
        return Ticker.disabledTicker();
    }

    @Override
    public Ticker statsTicker() {
        return this.ticker;
    }

    /* --------------- JDK8 Map接口相关实现 --------------- */

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        this.data.forEach(action);
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        K[] notificationKey = (K[]) new Object[1];
        V[] notificationValue = (V[]) new Object[1];

        this.data.replaceAll((key, value) -> {
            // 对于上一次的replace，尝试做出removal通知（replaced类型）
            if(notificationKey[0] != null) {
                this.notifyRemoval(notificationKey[0], notificationValue[0], RemovalCause.REPLACED);
                notificationKey[0] = null;
                notificationValue[0] = null;
            }
            V newValue = requireNonNull(function.apply(key, value));
            if(newValue != value)
                this.writer.write(key, newValue);
            if(this.hasRemovalListener() && newValue != value) {
                notificationKey[0] = key;
                notificationValue[0] = value;
            }
            return newValue;
        });
        // 处理最后一个
        if(notificationKey[0] != null)
            this.notifyRemoval(notificationKey[0], notificationValue[1], RemovalCause.REPLACED);
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
                             boolean recordStats, boolean recordLoad) {
        Objects.requireNonNull(mappingFunction);
        // 先正常获取一次
        V value = this.data.get(key);
        if(value != null) {
            if(recordStats)
                this.statsCounter.recordHits(1);
            return value;
        }

        boolean[] missed = new boolean[1];
        value = this.data.computeIfAbsent(key, k -> {
            missed[0] = true;
            return recordStats
                    ? this.statsAware(mappingFunction, recordLoad).apply(key)
                    : mappingFunction.apply(key);
        });
        // key存在则记录命中
        if(!missed[0] && recordStats)
            this.statsCounter.recordHits(1);
        return value;
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        if(!this.data.containsKey(key))
            return null;

        V[] oldValue = (V[]) new Object[1];
        RemovalCause[] cause = new RemovalCause[1];
        V nv = this.data.computeIfPresent(key, (K k, V value) -> {
            var function = this.statsAware(remappingFunction, false, true, true);
            V newValue = function.apply(k, value);
            cause[0] = (newValue == null) ? RemovalCause.EXPLICIT : RemovalCause.REPLACED;
            if(this.hasRemovalListener() && newValue != value)
                oldValue[0] = value;
            return newValue;
        });
        if(oldValue[0] != null)
            this.notifyRemoval(key, oldValue[0], cause[0]);
        return nv;
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
                     boolean recordMiss, boolean recordLoad, boolean recordLoadFailure) {
        Objects.requireNonNull(remappingFunction);
        return this.remap(key, this.statsAware(remappingFunction, recordMiss, recordLoad, recordLoadFailure));
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Objects.requireNonNull(value);
        return this.remap(key, (k, oldValue) ->
                oldValue == null ? value : this.statsAware(remappingFunction).apply(oldValue, value));
    }

    /**
     * 不进行统计行为的Map.compute实现
     */
    V remap(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        V[] oldValue = (V[]) new Object[1];
        RemovalCause[] cause = new RemovalCause[1];
        V nv = this.data.compute(key, (K k, V value) -> {
            V newValue = remappingFunction.apply(k, value);
            if(value == null && newValue == null)
                return null;

            cause[0] = (newValue == null) ? RemovalCause.EXPLICIT : RemovalCause.REPLACED;
            if(this.hasRemovalListener() && newValue != value)
                oldValue[0] = value;
            return newValue;
        });
        if(oldValue[0] != null)
            this.notifyRemoval(key, oldValue[0], cause[0]);
        return nv;
    }

    /* --------------- ConcurrentMap接口相关实现 --------------- */

    @Override
    public boolean isEmpty() {
        return this.data.isEmpty();
    }

    @Override
    public int size() {
        return this.data.size();
    }

    @Override
    public void clear() {
        if(!this.hasRemovalListener() && this.writer == CacheWriter.disabledWriter()) {
            this.data.clear();
            return;
        }
        // 要通知，就只能退化到循环处理
        for(K key : this.data.keySet())
            this.remove(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return this.data.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return this.data.containsValue(value);
    }

    @Override
    public V get(Object key) {
        return this.getIfPresent(key, false);
    }

    @Override
    public V put(K key, V value) {
        return put(key, value, true);
    }

    @Override
    public V put(K key, V value, boolean notifyWriter) {
        Objects.requireNonNull(value);
        V[] oldValue = (V[]) new Object[1];
        if(this.writer == CacheWriter.disabledWriter() || !notifyWriter) {
            oldValue[0] = this.data.put(key, value);
        }else {
            this.data.compute(key, (k, v) -> {
               if(value != v)
                   this.writer.write(key, value);
               oldValue[0] = v;
               return value;
            });
        }

        if(this.hasRemovalListener() && oldValue[0] != null && oldValue[0] != value)
            this.notifyRemoval(key, oldValue[0], RemovalCause.REPLACED);
        return oldValue[0];
    }

    @Override
    public V putIfAbsent(K key, V value) {
        Objects.requireNonNull(value);
        boolean[] wasAbsent = new boolean[1];
        V val = this.data.computeIfAbsent(key, k -> {
            this.writer.write(key, value);
            wasAbsent[0] = true;
            return value;
        });
        return wasAbsent[0] ? null : val;   // 保留原始接口方法语义
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        if(!hasRemovalListener() && this.writer == CacheWriter.disabledWriter()) {
            this.data.putAll(map);
            return;
        }
        map.forEach(this::put);
    }

    @Override
    public V remove(Object key) {
        K castedKey = (K)key;
        V[] oldValue = (V[]) new Object[1];
        if(this.writer == CacheWriter.disabledWriter()) {
            oldValue[0] = this.data.remove(key);
        }else {
            this.data.computeIfPresent(castedKey, (k, v) -> {
                this.writer.delete(castedKey, v, RemovalCause.EXPLICIT);
                oldValue[0] = v;
                return null;
            });
        }
        if(this.hasRemovalListener() && oldValue[0] != null)
            this.notifyRemoval(castedKey, oldValue[0], RemovalCause.EXPLICIT);
        return oldValue[0];
    }

    @Override
    public boolean remove(Object key, Object value) {
        if(value == null) {
            Objects.requireNonNull(key);
            return false;
        }

        K castedKey = (K)key;
        V[] oldValue = (V[]) new Object[1];
        this.data.computeIfPresent(castedKey, (k, v) -> {
            if(v.equals(value)) {
                oldValue[0] = v;
                return null;
            }
            return v;
        });

        boolean removed = oldValue[0] != null;
        if(this.hasRemovalListener() && removed)
            this.notifyRemoval(castedKey, oldValue[0], RemovalCause.EXPLICIT);
        return removed;
    }

    @Override
    public V replace(K key, V value) {
        Objects.requireNonNull(value);
        V[] oldValue = (V[]) new Object[1];
        data.computeIfPresent(key, (k, v) -> {
            if(value != v)
                this.writer.write(key, value);
            oldValue[0] = v;
            return value;
        });
        if(this.hasRemovalListener() && oldValue[0] != null && oldValue[0] != value)
            this.notifyRemoval(key, oldValue[0], RemovalCause.REPLACED);    // 这里oldValue[0]原本作者写的是value，应该是写错了
        return oldValue[0];
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        Objects.requireNonNull(oldValue);
        Objects.requireNonNull(newValue);
        V[] prev = (V[]) new Object[1];
        data.computeIfPresent(key, (k, v) -> {
            if(v != oldValue) {
                if(newValue != v)
                    this.writer.write(key, newValue);
                prev[0] = v;
                return newValue;
            }
            return v;
        });

        boolean replaced = prev[0] != null;
        if(this.hasRemovalListener() && replaced && prev[0] != newValue)
            this.notifyRemoval(key, prev[0], RemovalCause.REPLACED);    // 这里oldValue[0]原本作者写的是value，应该是写错了
        return replaced;
    }

    @Override
    public boolean equals(Object o) {
        return this.data.equals(o);
    }

    @Override
    public int hashCode() {
        return this.data.hashCode();
    }

    @Override
    public String toString() {
        return this.data.toString();
    }

    @Override
    public Set<K> keySet() {
        final Set<K> ks = this.keySet;
        return ks == null ? this.keySet = new KeySetView<>(this) : ks;
    }

    @Override
    public Collection<V> values() {
        final Collection<V> vs = this.values;
        return vs == null ? this.values = new ValuesView<>(this) : vs;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        final Set<Entry<K, V>> es = this.entrySet;
        return es == null ? this.entrySet = new EntrySetView<>(this) : es;
    }

    /**
     * KeySet的实现
     */
    static final class KeySetView<K> extends AbstractSet<K> {
        final UnboundedLocalCache<K, ?> cache;

        KeySetView(UnboundedLocalCache<K, ?> cache) {
            this.cache = Objects.requireNonNull(cache);
        }

        @Override
        public boolean isEmpty() {
            return this.cache.isEmpty();
        }

        @Override
        public int size() {
            return this.cache.size();
        }

        @Override
        public void clear() {
            this.cache.clear();
        }

        @Override
        public boolean contains(Object obj) {
            return this.cache.containsKey(obj);
        }

        @Override
        public boolean remove(Object obj) {
            return this.cache.remove(obj) != null;
        }

        @Override
        public Iterator<K> iterator() {
            return new KeyIterator<>(this.cache);
        }

        @Override
        public Spliterator<K> spliterator() {
            return this.cache.data.keySet().spliterator();
        }
    }

    /**
     * key的迭代器
     */
    static final class KeyIterator<K> implements Iterator<K> {
        final UnboundedLocalCache<K, ?> cache;
        final Iterator<K> iter;
        K current;

        KeyIterator(UnboundedLocalCache<K, ?> cache) {
            this.cache = Objects.requireNonNull(cache);
            this.iter = cache.data.keySet().iterator(); // 还是借用ConCurrentHashMap的直接实现
        }

        @Override
        public boolean hasNext() {
            return this.iter.hasNext();
        }

        @Override
        public K next() {
            this.current = this.iter.next();
            return this.current;
        }

        @Override
        public void remove() {
            if(this.current == null)
                throw new IllegalStateException();
            this.cache.remove(this.current);
            this.current = null;
        }
    }

    static final class ValuesView<K, V> extends AbstractCollection<V> {
        final UnboundedLocalCache<K, V> cache;

        ValuesView(UnboundedLocalCache<K, V> cache) {
            this.cache = Objects.requireNonNull(cache);
        }

        @Override
        public boolean isEmpty() {
            return this.cache.isEmpty();
        }

        @Override
        public int size() {
            return this.cache.size();
        }

        @Override
        public void clear() {
            this.cache.clear();
        }

        @Override
        public boolean contains(Object obj) {
            return this.cache.containsValue(obj);
        }

        @Override
        public boolean removeIf(Predicate<? super V> filter) {
            Objects.requireNonNull(filter);
            boolean removed = false;
            for(var entry : this.cache.data.entrySet()) {
                if(filter.test(entry.getValue()))
                    removed |= this.cache.remove(entry.getKey(), entry.getValue());
            }
            return removed;
        }

        @Override
        public Iterator<V> iterator() {
            return new ValueIterator<>(this.cache);
        }

        @Override
        public Spliterator<V> spliterator() {
            return this.cache.data.values().spliterator();
        }
    }

    /**
     * value的迭代器
     */
    static final class ValueIterator<K, V> implements Iterator<V> {
        final UnboundedLocalCache<K, V> cache;
        final Iterator<Entry<K, V>> iter;
        Entry<K, V> entry;

        ValueIterator(UnboundedLocalCache<K, V> cache) {
            this.cache = Objects.requireNonNull(cache);
            this.iter = cache.data.entrySet().iterator(); // 还是借用ConCurrentHashMap的直接实现
        }

        @Override
        public boolean hasNext() {
            return this.iter.hasNext();
        }

        @Override
        public V next() {
            this.entry = this.iter.next();
            return this.entry.getValue();
        }

        @Override
        public void remove() {
            if(this.entry == null)
                throw new IllegalStateException();
            this.cache.remove(this.entry.getKey());
            this.entry = null;
        }
    }

    /**
     * EntrySet的实现
     */
    static final class EntrySetView<K, V> extends AbstractSet<Entry<K, V>> {
        final UnboundedLocalCache<K, V> cache;

        EntrySetView(UnboundedLocalCache<K, V> cache) {
            this.cache = Objects.requireNonNull(cache);
        }

        @Override
        public boolean isEmpty() {
            return this.cache.isEmpty();
        }

        @Override
        public int size() {
            return this.cache.size();
        }

        @Override
        public void clear() {
            this.cache.clear();
        }

        @Override
        public boolean contains(Object obj) {
            if(!(obj instanceof Entry<?, ?>))
                return false;
            Entry<?, ?> entry = (Entry<?, ?>)obj;
            Object key = entry.getKey();
            Object value = entry.getValue();
            if(key == null || value == null)
                return false;
            V cachedValue = this.cache.get(key);
            return cachedValue != null && cachedValue.equals(value);
        }

        @Override
        public boolean remove(Object obj) {
            if(!(obj instanceof Entry<?, ?>))
                return false;
            Entry<?, ?> entry = (Entry<?, ?>)obj;
            return this.cache.remove(entry.getKey(), entry.getValue());
        }

        @Override
        public boolean removeIf(Predicate<? super Entry<K, V>> filter) {
            Objects.requireNonNull(filter);
            boolean removed = false;
            for(var entry : this.cache.data.entrySet()) {
                if(filter.test(entry))
                    removed |= this.cache.remove(entry.getKey(), entry.getValue());
            }
            return removed;
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator<>(this.cache);
        }

        @Override
        public Spliterator<Entry<K, V>> spliterator() {
            return new EntrySpliterator<>(this.cache);
        }
    }

    /**
     * Entry的迭代器
     */
    static final class EntryIterator<K, V> implements Iterator<Entry<K, V>> {
        final UnboundedLocalCache<K, V> cache;
        final Iterator<Entry<K, V>> iter;
        Entry<K, V> entry;

        EntryIterator(UnboundedLocalCache<K, V> cache) {
            this.cache = Objects.requireNonNull(cache);
            this.iter = cache.data.entrySet().iterator(); // 还是借用ConCurrentHashMap的直接实现
        }

        @Override
        public boolean hasNext() {
            return this.iter.hasNext();
        }

        @Override
        public Entry<K, V> next() {
            this.entry = this.iter.next();
            return new WriteThroughEntry<>(this.cache, this.entry.getKey(), this.entry.getValue());
        }

        @Override
        public void remove() {
            if(this.entry == null)
                throw new IllegalStateException();
            this.cache.remove(this.entry.getKey());
            this.entry = null;
        }
    }

    /**
     * Entry的拆分后并行迭代器
     */
    static final class EntrySpliterator<K, V> implements Spliterator<Entry<K, V>> {
        final Spliterator<Entry<K, V>> spliterator;
        final UnboundedLocalCache<K, V> cache;

        EntrySpliterator(UnboundedLocalCache<K, V> cache, Spliterator<Entry<K, V>> spliterator) {
            this.spliterator = Objects.requireNonNull(spliterator);
            this.cache = Objects.requireNonNull(cache);
        }

        EntrySpliterator(UnboundedLocalCache<K, V> cache) {
            this(cache, cache.data.entrySet().spliterator());
        }

        @Override
        public void forEachRemaining(Consumer<? super Entry<K, V>> action) {
            Objects.requireNonNull(action);
            this.spliterator.forEachRemaining(entry -> {
                var e = new WriteThroughEntry<>(this.cache, entry.getKey(), entry.getValue());
                action.accept(e);
            });
        }

        @Override
        public boolean tryAdvance(Consumer<? super Entry<K, V>> action) {
            Objects.requireNonNull(action);
            return this.spliterator.tryAdvance(entry -> {
                var e = new WriteThroughEntry<>(this.cache, entry.getKey(), entry.getValue());
                action.accept(e);
            });
        }

        @Override
        public Spliterator<Entry<K, V>> trySplit() {
            Spliterator<Entry<K, V>> split = this.spliterator.trySplit();
            return split == null ? null : new EntrySpliterator<>(this.cache, split);
        }

        @Override
        public long estimateSize() {
            return this.spliterator.estimateSize();
        }

        @Override
        public int characteristics() {
            return this.spliterator.characteristics();
        }
    }

    /* --------------- 以下为ManualCache相关的具体实现（即非LoadingCache） --------------- */
    static class UnboundedLocalManualCache<K, V> implements LocalManualCache<K, V>, Serializable {
        private static final long serialVersionUID = 1;
        final UnboundedLocalCache<K, V> cache;
        Policy<K, V> policy;

        UnboundedLocalManualCache(Caffeine<K, V> builder) {
            this.cache = new UnboundedLocalCache<>(builder, false);
        }

        @Override
        public LocalCache<K, V> cache() {
            return this.cache;
        }

        @Override
        public Policy<K, V> policy() {
            return this.policy == null
                    ? this.policy = new UnboundedPolicy<>(this.cache, Function.identity())
                    : this.policy;
        }

        private void readObject(ObjectInputStream stream) throws InvalidObjectException {
            throw new InvalidObjectException("Proxy required");
        }

        Object writeReplace() {
            SerializationProxy<K, V> proxy = new SerializationProxy<>();
            proxy.isRecordingStats = this.cache.isRecordingStats;
            proxy.removalListener = this.cache.removalListener;
            proxy.ticker = this.cache.ticker;
            proxy.writer = this.cache.writer;
            return proxy;
        }
    }

    /**
     * 无界cache的Policy实现
     */
    static final class UnboundedPolicy<K, V> implements Policy<K, V> {
        final UnboundedLocalCache<K, V> cache;
        final Function<V, V> transformer;

        UnboundedPolicy(UnboundedLocalCache<K, V> cache, Function<V, V> transformer) {
            this.transformer = transformer;
            this.cache = cache;
        }

        @Override
        public boolean isRecordingStats() {
            return this.cache.isRecordingStats;
        }

        @Override
        public V getIfPresentQuietly(Object key) {
            return this.transformer.apply(this.cache.data.get(key));
        }

        @Override
        public Optional<Eviction<K, V>> eviction() {
            return Optional.empty();    // 无界版本不存在
        }

        @Override
        public Optional<Expiration<K, V>> expireAfterAccess() {
            return Optional.empty();    // 无界版本不存在
        }

        @Override
        public Optional<Expiration<K, V>> expireAfterWrite() {
            return Optional.empty();    // 无界版本不存在
        }

        @Override
        public Optional<Expiration<K, V>> refreshAfterWrite() {
            return Optional.empty();    // 无界版本不存在
        }
    }

    /* --------------- 以下为LoadingCache相关的具体实现（即value可以根据执行loader自动加载） --------------- */
    static final class UnboundedLocalLoadingCache<K, V> extends UnboundedLocalManualCache<K, V>
            implements LocalLoadingCache<K, V> {
        private static final long serialVersionUID = 1;

        final Function<K, V> mappingFunction;
        final CacheLoader<? super K, V> loader;
        final Function<Iterable<? extends K>, Map<K, V>> bulkMappingFunction;

        UnboundedLocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
            super(builder);
            this.loader = loader;
            this.mappingFunction = LocalLoadingCache.newMappingFunction(loader);
            this.bulkMappingFunction = LocalLoadingCache.newBulkMappingFunction(loader);
        }

        @Override
        public CacheLoader<? super K, V> cacheLoader() {
            return this.loader;
        }

        @Override
        public Function<K, V> mappingFunction() {
            return this.mappingFunction;
        }

        @Override
        public Function<Iterable<? extends K>, Map<K, V>> bulkMappingFunction() {
            return this.bulkMappingFunction;
        }

        @Override
        Object writeReplace() {
            @SuppressWarnings("unchecked")
            SerializationProxy<K, V> proxy = (SerializationProxy<K, V>)super.writeReplace();
            proxy.loader = this.loader;
            return proxy;
        }

        private void readObject(ObjectInputStream stream) throws InvalidObjectException {
            throw new InvalidObjectException("Proxy required");
        }
    }

    /* --------------- 以下为AsyncCache相关的具体实现 --------------- */

    static final class UnboundedLocalAsyncCache<K, V> implements LocalAsyncCache<K, V>, Serializable {
        private static final long serialVersionUID = 1;

        final UnboundedLocalCache<K, CompletableFuture<V>> cache;
        ConcurrentMap<K, CompletableFuture<V>> mapView;
        CacheView<K, V> cacheView;
        Policy<K, V> policy;

        UnboundedLocalAsyncCache(Caffeine<K, V> builder) {
            this.cache = new UnboundedLocalCache<>((Caffeine<K, CompletableFuture<V>>)builder, true);
        }

        @Override
        public LocalCache<K, CompletableFuture<V>> cache() {
            return this.cache;
        }

        @Override
        public ConcurrentMap<K, CompletableFuture<V>> asMap() {
            return this.mapView == null ? this.mapView = new AsyncAsMapView<>(this) : this.mapView;
        }

        @Override
        public Cache<K, V> synchronous() {
            return this.cacheView == null ? this.cacheView = new CacheView<>(this) : this.cacheView;
        }

        @Override
        public Policy<K, V> policy() {
            UnboundedLocalCache<K, V> castedCache = (UnboundedLocalCache<K, V>)this.cache;
            Function<CompletableFuture<V>, V> transformer = Async::getIfReady;  // 适配转换
            Function<V, V> castedTransformer = (Function<V, V>)transformer;
            return this.policy == null
                ? this.policy = new UnboundedPolicy<>(castedCache, castedTransformer)
                : this.policy;
        }

        private void readObject(ObjectInputStream stream) throws InvalidObjectException {
            throw new InvalidObjectException("Proxy required");
        }

        Object writeReplace() {
            SerializationProxy<K, V> proxy = new SerializationProxy<>();
            proxy.isRecordingStats = this.cache.isRecordingStats;
            proxy.removalListener = this.cache.removalListener;
            proxy.ticker = this.cache.ticker;
            proxy.writer = this.cache.writer;
            proxy.async = true;
            return proxy;
        }
    }

    /* --------------- 以下为AsyncLoadingCache相关的具体实现 --------------- */

    static final class UnboundedLocalAsyncLoadingCache<K, V> extends LocalAsyncLoadingCache<K, V> implements Serializable {
        private static final long serialVersionUID = 1;
        final UnboundedLocalCache<K, CompletableFuture<V>> cache;

        @Nullable ConcurrentMap<K, CompletableFuture<V>> mapView;
        @Nullable Policy<K, V> policy;

        UnboundedLocalAsyncLoadingCache(Caffeine<K, V> builder, AsyncCacheLoader<? super K, V> loader) {
            super(loader);
            cache = new UnboundedLocalCache<>((Caffeine<K, CompletableFuture<V>>) builder, true);
        }

        @Override
        public LocalCache<K, CompletableFuture<V>> cache() {
            return this.cache;
        }

        @Override
        public ConcurrentMap<K, CompletableFuture<V>> asMap() {
            return this.mapView == null ? this.mapView = new AsyncAsMapView<>(this) : this.mapView;
        }

        @Override
        public Policy<K, V> policy() {
            UnboundedLocalCache<K, V> castedCache = (UnboundedLocalCache<K, V>)this.cache;
            Function<CompletableFuture<V>, V> transformer = Async::getIfReady;
            Function<V, V> castedTransformer = (Function<V, V>)transformer;
            return this.policy == null
                    ? this.policy = new UnboundedPolicy<>(castedCache, castedTransformer)
                    : this.policy;
        }

        private void readObject(ObjectInputStream stream) throws InvalidObjectException {
            throw new InvalidObjectException("Proxy required");
        }

        Object writeReplace() {
            SerializationProxy<K, V> proxy = new SerializationProxy<>();
            proxy.isRecordingStats = this.cache.isRecordingStats();
            proxy.removalListener = this.cache.removalListener();
            proxy.ticker = this.cache.ticker;
            proxy.writer = this.cache.writer;
            proxy.loader = this.loader;
            proxy.async = true;
            return proxy;
        }
    }
}
