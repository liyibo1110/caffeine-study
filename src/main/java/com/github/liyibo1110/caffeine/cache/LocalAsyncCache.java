package com.github.liyibo1110.caffeine.cache;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.Objects.requireNonNull;

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
        this.cache().statsCounter().recordMissed(proxies.size());
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
                    this.cache().statsCounter().recordMissed(1);
            }else {
                this.cache().replace(key, valueFuture, valueFuture);    // 没有被其它线程换成别的value才替换
                this.cache().statsCounter().recordLoadSuccess(loadTime);
                if(recordMiss)
                    this.cache().statsCounter().recordMissed(1);
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

    /** 以下均为同步视图相关 */
    /*final class CacheView<K, V> extends AbstractCacheView<K, V> {
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

    abstract class AbstractCacheView<K, V> implements Cache<K, V>, Serializable {
        abstract LocalAsyncCache<K, V> asyncCache();
    }*/

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
        public Set<Entry<K, V>> entrySet() {
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
    }
}
