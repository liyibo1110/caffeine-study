package com.github.liyibo1110.caffeine.cache;

import com.github.liyibo1110.caffeine.cache.stats.CacheStats;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author liyibo
 * @date 2026-01-07 17:58
 */
public interface LocalAsyncCache<K, V> extends AsyncCache<K, V> {
    Logger logger = Logger.getLogger(LocalAsyncCache.class.getName());

    LocalCache<K, CompletableFuture<V>> cache();

    Policy<K, V> policy();

    @Override
    default CompletableFuture<V> getIfPresent(Object key) {
        return this.cache().getIfPresent(key, true);
    }

    @Override
    default CompletableFuture<V> get(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        // 适配器：Function<K, V> -> BiFunction<K, Executor, CompletableFuture<V>>
        return this.get(key, (ignoredKey, executor) -> CompletableFuture.supplyAsync(() -> mappingFunction.apply(key), executor));
    }

    @Override
    default CompletableFuture<V> get(K key, BiFunction<? super K, Executor, CompletableFuture<V>> mappingFunction) {
        return this.get(key, mappingFunction, true);
    }

    /**
     * 委托给LocalCache.computeIfAbsent干活
     */
    default CompletableFuture<V> get(K key, BiFunction<? super K, Executor, CompletableFuture<V>> mappingFunction,
                                     boolean recordStats) {
        long startTime = this.cache().statsTicker().read();
        CompletableFuture<V>[] result = new CompletableFuture[1];
        CompletableFuture<V> future = this.cache().computeIfAbsent(key, k -> {
            result[0] = mappingFunction.apply(key, this.cache().executor());
            return Objects.requireNonNull(result[0]);
        }, recordStats, false);
        if(result[0] != null)
            this.handleCompletion(key, result[0], startTime, false);
        return future;
    }

    @Override
    default CompletableFuture<Map<K, V>> getAll(Iterable<? extends K> keys,
                                                BiFunction<Iterable<? extends K>, Executor, CompletableFuture<Map<K, V>>> mappingFunction) {
        Objects.requireNonNull(keys);
        Objects.requireNonNull(mappingFunction);

        Map<K, CompletableFuture<V>> futures = new LinkedHashMap<>();   // 所有的keys，不管cache里面有没有
        Map<K, CompletableFuture<V>> proxies = new HashMap<>();     // cache里没有，需要动态异步加载生成的value

        for(K key : keys) {
            if(futures.containsKey(key))
                continue;
            CompletableFuture<V> future = this.cache().getIfPresent(key, false);    // 先看cache里有没有
            if(future == null) {
                CompletableFuture<V> proxy = new CompletableFuture<>(); // 先占位
                future = this.cache().putIfAbsent(key, proxy);  // 保证并发安全，还得再看有没有
                if(future == null) {    // 再没有，说明真的没有了，要加载
                    future = proxy;
                    proxies.put(key, proxy);
                }
            }
            futures.put(key, future);
        }
        this.cache().statsCounter().recordMisses(proxies.size());
        this.cache().statsCounter().recordHits(futures.size() - proxies.size());
        if(proxies.isEmpty()) // 全部命中则收尾
            this.composeResult(futures);

        AsyncBulkCompleter<K, V> completer = new AsyncBulkCompleter<>(this.cache(), proxies);
        try {
            mappingFunction.apply(proxies.keySet(), this.cache().executor()).whenComplete(completer);
            // 注意这里future里面的实例，可能同时也在proxies里面，所以completer里面的操作可以改变future里面的值
            return this.composeResult(futures);
        } catch (Throwable t) {
            completer.accept(null, t);
            throw t;
        }
    }

    /**
     * 返回单一的CompletableFuture，它会等待所有依赖的内部Future都完成，并在成功后回访组合映射
     * 如果有Future失败，且在cache中，则会被移除
     */
    default CompletableFuture<Map<K, V>> composeResult(Map<K, CompletableFuture<V>> futures) {
        if(futures.isEmpty())
            return CompletableFuture.completedFuture(Collections.emptyMap());

        CompletableFuture<?>[] array = futures.values().toArray(new CompletableFuture[0]);
        return CompletableFuture.allOf(array).thenApply(ignored -> {
            Map<K, V> result = new LinkedHashMap<>(futures.size());
            futures.forEach((key, future) -> {
                V value = future.getNow(null);
                if(value != null)
                    result.put(key, value);
            });
            return Collections.unmodifiableMap(result);
        });
    }

    /**
     * 查出CompletableFuture<V>后的取值收尾工作，即委托LocalCache进行cache操作（replace/remove）
     * 作用是让CompletableFuture完成实际cache替换工作并统计（实际干活的，之前都是只提交了异步，并不一定有最终结果）
     */
    default void handleCompletion(K key, CompletableFuture<V> valueFuture, long startTime, boolean recordMiss) {
        AtomicBoolean completed = new AtomicBoolean();
        valueFuture.whenComplete((value, error) -> {
            // 防止并发多次回调
            if(!completed.compareAndSet(false, true))
                return;
            long loadTime = this.cache().statsTicker().read() - startTime;
            if(value == null) {
                if((error != null) && !(error instanceof CancellationException) && !(error instanceof TimeoutException))
                    logger.log(Level.WARNING, "Exception thrown during asynchronous load", error);
                this.cache().remove(key, valueFuture);
                this.cache().statsCounter().recordLoadFailure(loadTime);
                if(recordMiss)
                    this.cache().statsCounter().recordMisses(1);
            }else {
                this.cache().replace(key, valueFuture, valueFuture);    // 没有被其它线程换成别的value才替换
                this.cache().statsCounter().recordLoadSuccess(loadTime);
                if(recordMiss)
                    this.cache().statsCounter().recordMisses(1);
            }
        });
    }

    /**
     * 异步批量loader的收尾协调器（整个Caffeine里相对最复杂的async组件）
     */
    final class AsyncBulkCompleter<K, V> implements BiConsumer<Map<K, V>, Throwable> {
        private final LocalCache<K, CompletableFuture<V>> cache;
        private final Map<K, CompletableFuture<V>> proxies;
        private final long startTime;

        AsyncBulkCompleter(LocalCache<K, CompletableFuture<V>> cache, Map<K, CompletableFuture<V>> proxies) {
            this.cache = cache;
            this.proxies = proxies;
            this.startTime = cache.statsTicker().read();
        }

        /** 执行到了这个方法里，说明已经将proxies里面的key异步地加载完毕了（因为是在whenComplete被调用的） */
        @Override
        public void accept(Map<K, V> result, Throwable error) {
            long loadTime = this.cache.statsTicker().read() - startTime;
            if(result == null) {
                if(error == null)
                    error = new NullMapCompletionException();
                // 从cache中都删除
                for(var entry : proxies.entrySet()) {
                    this.cache.remove(entry.getKey(), entry.getValue());
                    entry.getValue().obtrudeException(error);
                }
                this.cache.statsCounter().recordLoadFailure(loadTime);
                if(!(error instanceof CancellationException) && !(error instanceof TimeoutException))
                    logger.log(Level.WARNING, "Exception thrown during asynchronous load", error);
            }else { // 加载成功，修改cache本身
                this.fillProxies(result);
                this.addNewEntries(result);
                this.cache.statsCounter().recordLoadSuccess(loadTime);
            }
        }

        /**
         * 用计算结果填充proxies
         */
        private void fillProxies(Map<K, V> result) {
            this.proxies.forEach((key, future) -> {
                V value = result.get(key);
                future.obtrudeValue(value);
                if(value == null)
                    this.cache.remove(key, future);
                else
                    this.cache.replace(key, future, future);
            });
        }

        /**
         * 将算出得任何未被请求的额外条目添加到cache中（也就是说result出来的数目，要大于原始proxies的数目）
         * 目前不清楚什么操作会导致这个问题，没有深入考虑，简单倾向于并发操作的后果
         */
        private void addNewEntries(Map<K, V> result) {
            if(this.proxies.size() == result.size())
                return;
            // result有额外的entry
            result.forEach((key, value) -> {
                if(!this.proxies.containsKey(key))
                    this.cache.put(key, CompletableFuture.completedFuture(value));
            });
        }

        static final class NullMapCompletionException extends CompletionException {
            private static final long serialVersionUID = 1L;
            public NullMapCompletionException() {
                super("null map", null);
            }
        }
    }

    /** 以下均为异步视图相关 */
    final class AsyncAsMapView<K, V> implements ConcurrentMap<K, CompletableFuture<V>> {
        final LocalAsyncCache<K, V> asyncCache;

        AsyncAsMapView(LocalAsyncCache<K, V> asyncCache) {
            this.asyncCache = Objects.requireNonNull(asyncCache);
        }

        @Override
        public boolean isEmpty() {
            return this.asyncCache.cache().isEmpty();
        }

        @Override
        public int size() {
            return this.asyncCache.cache().size();
        }

        @Override
        public void clear() {
            this.asyncCache.cache().clear();
        }

        @Override
        public boolean containsKey(Object key) {
            return this.asyncCache.cache().containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return this.asyncCache.cache().containsValue(value);
        }

        @Override
        public CompletableFuture<V> get(Object key) {
            return this.asyncCache.cache().get(key);
        }

        @Override
        public CompletableFuture<V> putIfAbsent(K key, CompletableFuture<V> value) {
            CompletableFuture<V> prior = this.asyncCache.cache().putIfAbsent(key, value);
            long startTime = this.asyncCache.cache().statsTicker().read();
            if(prior == null)
                this.asyncCache.handleCompletion(key, value, startTime, false);
            return prior;
        }

        @Override
        public CompletableFuture<V> put(K key, CompletableFuture<V> value) {
            CompletableFuture<V> prior = this.asyncCache.cache().put(key, value);
            long startTime = this.asyncCache.cache().statsTicker().read();
            if(prior == null)
                this.asyncCache.handleCompletion(key, value, startTime, false);
            return prior;
        }

        public void putAll(Map<? extends K, ? extends CompletableFuture<V>> map) {
            map.forEach(this::put);
        }

        @Override
        public CompletableFuture<V> replace(K key, CompletableFuture<V> value) {
            CompletableFuture<V> prior = this.asyncCache.cache().replace(key, value);
            long startTime = this.asyncCache.cache().statsTicker().read();
            if(prior != null)
                this.asyncCache.handleCompletion(key, value, startTime, /* recordMiss */ false);
            return prior;
        }

        @Override
        public boolean replace(K key, CompletableFuture<V> oldValue, CompletableFuture<V> newValue) {
            boolean replaced = this.asyncCache.cache().replace(key, oldValue, newValue);
            long startTime = this.asyncCache.cache().statsTicker().read();
            if(replaced)
                this.asyncCache.handleCompletion(key, newValue, startTime, false);
            return replaced;
        }

        @Override
        public CompletableFuture<V> remove(Object key) {
            return this.asyncCache.cache().remove(key);
        }

        @Override
        public boolean remove(Object key, Object value) {
            return this.asyncCache.cache().remove(key, value);
        }

        @Override
        public CompletableFuture<V> computeIfAbsent(K key,
                          Function<? super K, ? extends CompletableFuture<V>> mappingFunction) {
            Objects.requireNonNull(mappingFunction);
            CompletableFuture<V>[] result = new CompletableFuture[1];
            long startTime = this.asyncCache.cache().statsTicker().read();
            CompletableFuture<V> future = this.asyncCache.cache().computeIfAbsent(key, k -> {
                result[0] = mappingFunction.apply(k);
                return result[0];
            }, false, false);
            if(result[0] == null) { // 为null说明已有value，因为mappingFunction压根就不会被执行
                if((future != null) && this.asyncCache.cache().isRecordingStats()) {
                    future.whenComplete((r, e) -> {
                       if((r != null) && (e == null))
                           this.asyncCache.cache().statsCounter().recordHits(1);
                    });
                }
            }else {
                this.asyncCache.handleCompletion(key, result[0], startTime, true);
            }
            return future;
        }

        @Override
        public CompletableFuture<V> computeIfPresent(K key,
                                                     BiFunction<? super K, ? super CompletableFuture<V>, ? extends CompletableFuture<V>> remappingFunction) {
            Objects.requireNonNull(remappingFunction);
            CompletableFuture<V>[] result = new CompletableFuture[1];
            long startTime = this.asyncCache.cache().statsTicker().read();
            this.asyncCache.cache().compute(key, (k, oldValue) -> {
                result[0] = (oldValue == null) ? null : remappingFunction.apply(k, oldValue);
                return result[0];
            }, false, false, false);

            if(result[0] != null)
                this.asyncCache.handleCompletion(key, result[0], startTime, false);
            return result[0];
        }

        @Override
        public CompletableFuture<V> compute(K key,
                                            BiFunction<? super K, ? super CompletableFuture<V>, ? extends CompletableFuture<V>> remappingFunction) {
            Objects.requireNonNull(remappingFunction);
            CompletableFuture<V>[] result = new CompletableFuture[1];
            long startTime = this.asyncCache.cache().statsTicker().read();
            this.asyncCache.cache().compute(key, (k, oldValue) -> {
                result[0] = remappingFunction.apply(k, oldValue);
                return result[0];
            }, false, false, false);

            if(result[0] != null)
                this.asyncCache.handleCompletion(key, result[0], startTime, false);
            return result[0];
        }

        @Override
        public CompletableFuture<V> merge(K key, CompletableFuture<V> value,
                                          BiFunction<? super CompletableFuture<V>, ? super CompletableFuture<V>,
                                                  ? extends CompletableFuture<V>> remappingFunction) {
            Objects.requireNonNull(value);
            Objects.requireNonNull(remappingFunction);
            CompletableFuture<V>[] result = new CompletableFuture[1];
            long startTime = this.asyncCache.cache().statsTicker().read();
            this.asyncCache.cache().compute(key, (k, oldValue) -> {
                result[0] = (oldValue == null) ? value : remappingFunction.apply(oldValue, value);
                return result[0];
            }, false, false, false);

            if(result[0] != null)
                this.asyncCache.handleCompletion(key, result[0], startTime, false);
            return result[0];
        }

        @Override
        public Set<K> keySet() {
            return this.asyncCache.cache().keySet();
        }

        @Override
        public Collection<CompletableFuture<V>> values() {
            return this.asyncCache.cache().values();
        }

        @Override
        public Set<Entry<K, CompletableFuture<V>>> entrySet() {
            return this.asyncCache.cache().entrySet();
        }

        @Override public boolean equals(Object obj) {
            return this.asyncCache.cache().equals(obj);
        }
        @Override public int hashCode() {
            return this.asyncCache.cache().hashCode();
        }
        @Override public String toString() {
            return this.asyncCache.cache().toString();
        }
    }

    /** 以下与同步视图相关 */
    final class CacheView<K, V> extends AbstractCacheView<K, V> {
        private static final long serialVersionUID = 1L;

        final LocalAsyncCache<K, V> asyncCache;

        CacheView(LocalAsyncCache<K, V> asyncCache) {
            this.asyncCache = Objects.requireNonNull(asyncCache);
        }

        @Override
        LocalAsyncCache<K, V> asyncCache() {
            return this.asyncCache;
        }
    }

    /**
     * 同步视图通用模板方法
     */
    abstract class AbstractCacheView<K, V> implements Cache<K, V>, Serializable {
        transient AsMapView<K, V> asMapView;

        abstract LocalAsyncCache<K, V> asyncCache();

        @Override
        public @Nullable V getIfPresent(Object key) {
            // 注意这个方法是不阻塞的，没有算好的value就是返回null
            CompletableFuture<V> future = this.asyncCache().cache().getIfPresent(key, true);
            return Async.getIfReady(future);
        }

        @Override
        public Map<K, V> getAllPresent(Iterable<?> keys) {
            // key去重
            Set<Object> uniqueKeys = new LinkedHashSet<>();
            for(Object key : keys)
                uniqueKeys.add(key);

            int misses = 0;
            Map<Object, Object> result = new LinkedHashMap<>();
            for(Object key : uniqueKeys) {
                CompletableFuture<V> future = this.asyncCache().cache().get(key);
                Object value = Async.getIfReady(future);
                if(value == null)
                    misses++;
                else
                    result.put(key, value);
            }
            this.asyncCache().cache().statsCounter().recordMisses(misses);
            this.asyncCache().cache().statsCounter().recordHits(result.size());
            Map<K, V> castedResult = (Map<K, V>)result;
            return Collections.unmodifiableMap(castedResult);
        }

        @Override
        public V get(K key, Function<? super K, ? extends V> mappingFunction) {
            return resolve(this.asyncCache().get(key, mappingFunction));
        }

        @Override
        public Map<K, V> getAll(Iterable<? extends K> keys, Function<Iterable<? extends K>, Map<K, V>> mappingFunction) {
            return resolve(this.asyncCache().getAll(keys, mappingFunction));
        }

        protected static <T> T resolve(CompletableFuture<T> future) throws Error {
            try {
                return future.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof AsyncBulkCompleter.NullMapCompletionException) {
                    throw new NullPointerException(e.getCause().getMessage());
                } else if (e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                } else if (e.getCause() instanceof Error) {
                    throw (Error)e.getCause();
                }
                throw new CompletionException(e.getCause());
            } catch (InterruptedException e) {
                throw new CompletionException(e);
            }
        }

        @Override
        public void put(K key, V value) {
            Objects.requireNonNull(value);
            this.asyncCache().cache().put(key, CompletableFuture.completedFuture(value));
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> map) {
            map.forEach(this::put);
        }

        @Override
        public void invalidate(Object key) {
            this.asyncCache().cache().remove(key);
        }

        @Override
        public void invalidateAll(Iterable<?> keys) {
            this.asyncCache().cache().invalidateAll(keys);
        }

        @Override
        public void invalidateAll() {
            this.asyncCache().cache().clear();
        }

        @Override
        public long estimatedSize() {
            return this.asyncCache().cache().estimatedSize();
        }

        @Override
        public CacheStats stats() {
            return this.asyncCache().cache().statsCounter().snapshot();
        }

        @Override
        public void cleanUp() {
            this.asyncCache().cache().cleanUp();
        }

        @Override
        public Policy<K, V> policy() {
            return this.asyncCache().policy();
        }

        @Override
        public ConcurrentMap<K, V> asMap() {
            return this.asMapView == null ? asMapView = new AsMapView<>(this.asyncCache().cache()) : this.asMapView;
        }
    }

    final class AsMapView<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
        final LocalCache<K, CompletableFuture<V>> delegate;

        Collection<V> values;
        Set<Entry<K, V>> entries;

        AsMapView(LocalCache<K, CompletableFuture<V>> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isEmpty() {
            return this.delegate.isEmpty();
        }

        @Override
        public int size() {
            return this.delegate.size();
        }

        @Override
        public void clear() {
            this.delegate.clear();
        }

        @Override
        public boolean containsKey(Object key) {
            return delegate.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            Objects.requireNonNull(value);
            for(CompletableFuture<V> valueFuture : this.delegate.values()) {
                if(value.equals(Async.getIfReady(valueFuture)))
                    return true;
            }
            return false;
        }

        @Override
        public V get(Object key) {
            return Async.getIfReady(this.delegate.get(key));
        }

        /**
         * 注意这里的absent指的是，有value且value已被计算出具体值了，计算中的也算absent
         */
        @Override
        public V putIfAbsent(K key, V value) {
            Objects.requireNonNull(value);
            while(true) {   // 一直循环，直到value算出了结果
                CompletableFuture<V> priorFuture = this.delegate.get(key);
                if(priorFuture != null) {
                    if(!priorFuture.isDone()) { // 有value但没算完，一定要等算完再继续，因为不确定最终结果，无法判断是否absent
                        Async.getWhenSuccessful(priorFuture);
                        continue;
                    }
                    // 算出结果了
                    V prior = Async.getWhenSuccessful(priorFuture);
                    if(prior != null)  // 有value说明不是absent，直接返回旧value
                        return prior;
                }

                // 符合absent，尝试原子性写入
                boolean[] added = { false };    // 当前线程是否可以真写入
                CompletableFuture<V> computed = this.delegate.compute(key, (k, valueFuture) -> {
                    added[0] = (valueFuture == null)    // value没有，可以写
                            || (valueFuture.isDone() && (Async.getIfReady(valueFuture) == null));   // value == null，也可以写
                    return added[0] ? CompletableFuture.completedFuture(value) : valueFuture;
                }, false, false, false);

                if(added[0]) {  // 如果是我写的，按照Map.putIfAbsent的原始语义，要返回null
                    return null;
                }else { // 其它线程写的，要等结果
                    V prior = Async.getWhenSuccessful(computed);
                    if(prior != null)
                        return prior;
                }
            }
        }

        @Override
        public V put(K key, V value) {
            Objects.requireNonNull(value);
            CompletableFuture<V> old = this.delegate.put(key, CompletableFuture.completedFuture(value));
            return Async.getWhenSuccessful(old);
        }

        @Override
        public V remove(Object key) {
            CompletableFuture<V> old = this.delegate.remove(key);
            return Async.getWhenSuccessful(old);
        }

        @Override
        public boolean remove(Object key, Object value) {
            Objects.requireNonNull(key);
            if(value == null)
                return false;

            K castedKey = (K)key;
            boolean[] done = { false };
            boolean[] removed = { false };
            while(true) {
                CompletableFuture<V> future = this.delegate.get(key);
                if(future == null || future.isCompletedExceptionally())
                    return false;
                // value计算中，阻塞等待
                Async.getWhenSuccessful(future);
                this.delegate.compute(castedKey, (k, oldFuture) -> {
                    if(oldFuture == null) { // value最终结果是null，原样返回
                        done[0] = true;
                        return null;
                    }else if(oldFuture.isDone()) {  // 重点：上面已经阻塞等待过了，这里又有可能被其它线程提交新value了，要原样返回
                        return oldFuture;
                    }
                    done[0] = true;
                    V oldValue = Async.getIfReady(oldFuture);   // 终于取出旧value了
                    removed[0] = value.equals(oldValue);    // 这个线程能不能删
                    return oldValue == null || removed[0] ? null : oldFuture;
                }, false, false, true);

                if(done[0])
                    return removed[0];
            }
        }

        @Override
        public V replace(K key, V value) {
            Objects.requireNonNull(value);

            V[] oldValue = (V[])new Object[1];
            boolean[] done = { false }; // 有效判定，即得到了replace的最终结论，不需要再循环了
            while(true) {
                CompletableFuture<V> future = this.delegate.get(key);
                if(future == null || future.isCompletedExceptionally()) // 没有value或计算错误则返回null
                    return null;
                // 等待旧值算完
                Async.getWhenSuccessful(future);
                this.delegate.compute(key, (k, oldFuture) -> {
                    if(oldFuture == null) { // 计算结果value是null，则写入null
                        done[0] = true;
                        return null;
                    }else if(!oldFuture.isDone()) { // 计算结果value又重新计算中了，value不变
                        return oldFuture;
                    }
                    // 到这里说明确实能拿到算完的value了
                    done[0] = true;
                    oldValue[0] = Async.getIfReady(oldFuture);  // 再次检查旧值是否计算完毕，没算完就返回null
                    return oldValue[0] == null ? null : CompletableFuture.completedFuture(value);
                }, false, false, false);

                if(done[0])
                    return oldValue[0];
            }
        }

        @Override
        public boolean replace(K key, V oldValue, V newValue) {
            Objects.requireNonNull(oldValue);
            Objects.requireNonNull(newValue);

            boolean[] done = { false };
            boolean[] replaced = { false };
            while(true) {
                CompletableFuture<V> future = this.delegate.get(key);
                if(future == null || future.isCompletedExceptionally()) // 没有value或计算错误则返回false
                    return false;
                // 等待旧值算完
                Async.getWhenSuccessful(future);
                this.delegate.compute(key, (k, oldFuture) -> {
                    if(oldFuture == null) {
                        done[0] = true;
                        return null;
                    }else if(!oldFuture.isDone()) {
                        return oldFuture;
                    }

                    done[0] = true;
                    replaced[0] = oldValue.equals(Async.getIfReady(oldFuture)); // 检查oldValue是否一致
                    return replaced[0] ? CompletableFuture.completedFuture(newValue) : oldFuture;
                }, false, false, false);

                if(done[0])
                    return replaced[0];
            }
        }

        @Override
        public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
            Objects.requireNonNull(mappingFunction);

            while(true) {
                CompletableFuture<V> priorFuture = this.delegate.get(key);
                if(priorFuture != null) {
                    if(!priorFuture.isDone()) { // 没算完就等
                        Async.getWhenSuccessful(priorFuture);
                        continue;
                    }

                    V prior = Async.getWhenSuccessful(priorFuture);
                    if(prior != null) { // 有正常的旧值，命中缓存，退出不操作
                        this.delegate.statsCounter().recordHits(1);
                        return prior;
                    }
                }

                // 写入新value
                CompletableFuture<V>[] future = new CompletableFuture[1];
                CompletableFuture<V> computed = this.delegate.compute(key, (k, valueFuture) -> {
                    // 其它线程抢先写入并算完value了，不做修改
                    if(valueFuture != null && valueFuture.isDone() && Async.getIfReady(valueFuture) != null)
                        return valueFuture;

                    V newValue = this.delegate.statsAware(mappingFunction, true).apply(key);
                    if(newValue == null)
                        return null;
                    future[0] = CompletableFuture.completedFuture(newValue);
                    return future[0];
                }, false, false, false);

                V result = Async.getWhenSuccessful(computed);
                if(computed == future[0] || result != null) // 再次检查
                    return result;
            }
        }

        @Override
        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            Objects.requireNonNull(remappingFunction);

            V[] newValue = (V[])new Object[1];
            while(true) {
                Async.getWhenSuccessful(this.delegate.get(key));
                CompletableFuture<V> valueFuture = this.delegate.computeIfPresent(key, (k, oldFuture) -> {
                    if(!oldFuture.isDone())
                        return oldFuture;

                    V oldValue = Async.getIfReady(oldFuture);
                    if(oldValue == null)
                        return null;

                    // oldValue有值了，再更新
                    newValue[0] = remappingFunction.apply(key, oldValue);
                    return newValue[0] == null ? null : CompletableFuture.completedFuture(newValue[0]);
                });

                if(newValue[0] != null)
                    return newValue[0];
                else if(valueFuture == null)
                    return null;
            }
        }

        @Override
        public @Nullable V compute(K key,
                                   BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            Objects.requireNonNull(remappingFunction);

            V[] newValue = (V[]) new Object[1];
            while(true) {
                Async.getWhenSuccessful(this.delegate.get(key));
                CompletableFuture<V> valueFuture = this.delegate.compute(key, (k, oldFuture) -> {
                    if(oldFuture != null && !oldFuture.isDone())    // 有value但是没算完，就继续算，不替换
                        return oldFuture;

                    V oldValue = Async.getIfReady(oldFuture);
                    var func = this.delegate.statsAware(remappingFunction,
                            false, true, true);
                    newValue[0] = func.apply(key, oldValue);
                    return newValue[0] == null ? null : CompletableFuture.completedFuture(newValue[0]);
                }, false, false, false);

                if (newValue[0] != null)
                    return newValue[0];
                else if (valueFuture == null)
                    return null;
            }
        }

        @Override
        public @Nullable V merge(K key, V value,
                                 BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            Objects.requireNonNull(value);
            Objects.requireNonNull(remappingFunction);

            CompletableFuture<V> newFuture = CompletableFuture.completedFuture(value);
            boolean[] merged = { false };
            while(true) {
                Async.getWhenSuccessful(this.delegate.get(key));    // 先等稳定
                CompletableFuture<V> mergedFuture = this.delegate.merge(key, newFuture, (oldFuture, valueFuture) -> {
                    if(oldFuture != null && !oldFuture.isDone())    // 有value但是没算完，就继续算，不merge
                        return oldFuture;

                    merged[0] = true;
                    V oldValue = Async.getIfReady(oldFuture);
                    if(oldValue == null)    // 旧的value是null，则直接用新value
                        return valueFuture;
                    // 开始merge
                    V mergedValue = remappingFunction.apply(oldValue, value);
                    if(mergedValue == null)
                        return null;
                    else if(mergedValue == oldValue)
                        return oldFuture;
                    else if(mergedValue == value)
                        return valueFuture;

                    return CompletableFuture.completedFuture(mergedValue);
                });

                if(merged[0] || mergedFuture == newFuture)
                    return Async.getWhenSuccessful(mergedFuture);
            }
        }

        @Override
        public Set<K> keySet() {
            return this.delegate.keySet();
        }

        @Override
        public Collection<V> values() {
            return this.values == null ? (this.values = new Values()) : this.values;
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            return this.entries == null ? (this.entries = new EntrySet()) : this.entries;
        }

        /**
         * 自定义的集合，用来当作values方法的返回value容器
         */
        private final class Values extends AbstractCollection<V> {
            @Override
            public boolean isEmpty() {
                return AsMapView.this.isEmpty();
            }

            @Override
            public int size() {
                return AsMapView.this.size();
            }

            @Override
            public boolean contains(Object obj) {
                return AsMapView.this.containsValue(obj);
            }

            @Override
            public void clear() {
                AsMapView.this.clear();
            }

            @Override
            public Iterator<V> iterator() {
                return new Iterator<>() {
                    Iterator<Entry<K, V>> iter = entrySet().iterator();

                    @Override
                    public boolean hasNext() {
                        return this.iter.hasNext();
                    }

                    @Override
                    public V next() {
                        return this.iter.next().getValue();
                    }

                    @Override
                    public void remove() {
                        this.iter.remove();
                    }
                };
            }
        }

        private final class EntrySet extends AbstractSet<Entry<K, V>> {
            @Override
            public boolean isEmpty() {
                return AsMapView.this.isEmpty();
            }

            @Override
            public int size() {
                return AsMapView.this.size();
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
                V cachedValue = AsMapView.this.get(key);
                return cachedValue != null && cachedValue.equals(value);
            }

            @Override
            public boolean remove(Object obj) {
                if(!(obj instanceof Entry<?, ?>))
                    return false;
                Entry<?, ?> entry = (Entry<?, ?>)obj;
                return AsMapView.this.remove(entry.getKey(), entry.getValue());
            }

            @Override
            public void clear() {
                AsMapView.this.clear();
            }

            @Override
            public Iterator<Entry<K, V>> iterator() {
                return new Iterator<>() {
                    // 应该是由LocalCache的具体实现类来提供
                    Iterator<Entry<K, CompletableFuture<V>>> iter = delegate.entrySet().iterator();
                    /** 下一个可用元素 */
                    Entry<K, V> cursor;
                    /** 刚刚返回给用户的Entry对应的key（remove会用到） */
                    K removalKey;

                    @Override
                    public boolean hasNext() {
                        // 异步背景下，要循环遍历，直到找到下一个计算完成且值不为null的才行
                        while(this.cursor == null && this.iter.hasNext()) {
                            var entry = this.iter.next();
                            V value = Async.getIfReady(entry.getValue());
                            if(value != null)
                                this.cursor = new WriteThroughEntry<>(AsMapView.this, entry.getKey(), value);
                        }
                        return this.cursor != null;
                    }

                    @Override
                    public Entry<K, V> next() {
                        if(!this.hasNext())
                            throw new NoSuchElementException();
                        K key = this.cursor.getKey();
                        Entry<K, V> entry = this.cursor;
                        this.removalKey = key;
                        this.cursor = null;
                        return entry;
                    }

                    @Override
                    public void remove() {
                        Caffeine.requireState(this.removalKey != null);
                        delegate.remove(this.removalKey);
                        this.removalKey = null;
                    }
                };
            }


        }
    }
}
