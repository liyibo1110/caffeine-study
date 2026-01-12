package com.github.liyibo1110.caffeine.cache;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AsyncCacheLoader调用 -> LocalCache中的compute/async调用的桥接器
 * @author liyibo
 * @date 2026-01-12 15:35
 */
abstract class LocalAsyncLoadingCache<K, V>
        implements LocalAsyncCache<K, V>, AsyncLoadingCache<K, V> {
    static final Logger logger = Logger.getLogger(LocalAsyncLoadingCache.class.getName());

    final boolean canBulkLoad;
    final AsyncCacheLoader<K, V> loader;
    LoadingCacheView<K, V> cacheView;

    LocalAsyncLoadingCache(AsyncCacheLoader<? super K, V> loader) {
        this.loader = (AsyncCacheLoader<K, V>) loader;
        this.canBulkLoad = canBulkLoad(loader);
    }

    private static boolean canBulkLoad(AsyncCacheLoader<?, ?> loader) {
        try {
            Class<?> defaultLoaderClass = AsyncCacheLoader.class;
            if(loader instanceof CacheLoader<?, ?>) {
                defaultLoaderClass = CacheLoader.class;
                Method classLoadAll = loader.getClass().getMethod("loadAll", Iterable.class);
                Method defaultLoadAll = CacheLoader.class.getMethod("loadAll", Iterable.class);
                if(!classLoadAll.equals(defaultLoadAll))
                    return true;
            }

            Method classAsyncLoadAll = loader.getClass().getMethod("asyncLoadAll", Iterable.class, Executor.class);
            Method defaultAsyncLoadAll = defaultLoaderClass.getMethod("asyncLoadAll", Iterable.class, Executor.class);
            return !classAsyncLoadAll.equals(defaultAsyncLoadAll);
        } catch (NoSuchMethodException | SecurityException e) {
            logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
            return false;
        }
    }

    @Override
    public CompletableFuture<V> get(K key) {
        return this.get(key, loader::asyncLoad);
    }

    @Override
    public CompletableFuture<Map<K, V>> getAll(Iterable<? extends K> keys) {
        if(this.canBulkLoad)
            return this.getAll(keys, this.loader::asyncLoadAll);
        // 不支持bulk就循环处理
        Map<K, CompletableFuture<V>> result = new LinkedHashMap<>();
        Function<K, CompletableFuture<V>> mappingFunction = this::get;  // adapt
        for(K key : keys) {
            CompletableFuture<V> future = result.computeIfAbsent(key, mappingFunction);
            Objects.requireNonNull(future);
        }
        return this.composeResult(result);
    }

    @Override
    public LoadingCache<K, V> synchronous() {
        return this.cacheView == null ? this.cacheView = new LoadingCacheView<>(this) : this.cacheView;
    }

    /* --------------- 同步Loading版本的视图 --------------- */

    static final class LoadingCacheView<K, V> extends AbstractCacheView implements LoadingCache<K, V> {
        private static final long serialVersionUID = 1L;
        final LocalAsyncLoadingCache<K, V> asyncCache;

        LoadingCacheView(LocalAsyncLoadingCache<K, V> asyncCache) {
            this.asyncCache = Objects.requireNonNull(asyncCache);
        }

        @Override
        LocalAsyncCache asyncCache() {
            return this.asyncCache;
        }

        @Override
        public V get(K key) {
            return resolve(this.asyncCache.get(key));
        }

        @Override
        public Map<K, V> getAll(Iterable<? extends K> keys) {
            return resolve(this.asyncCache.getAll(keys));
        }

        @Override
        public void refresh(K key) {
            Objects.requireNonNull(key);

            long[] writeTime = new long[1];
            CompletableFuture<V> oldFuture = this.asyncCache.cache().getIfPresentQuietly(key, writeTime);
            // value为null，或者value计算出错，立刻再触发1次异步loader
            if(oldFuture == null || (oldFuture.isDone() && oldFuture.isCompletedExceptionally())) {
                this.asyncCache.get(key, this.asyncCache.loader::asyncLoad, false);
                return;
            }else if(!oldFuture.isDone()) { // 没准备好就不要再refresh了
                return;
            }

            // 正常获取到了oldValue
            oldFuture.thenAccept(oldValue -> {
                long now = this.asyncCache.cache().statsTicker().read();
                CompletableFuture<V> refreshFuture = (oldValue == null)
                        ? this.asyncCache.loader.asyncLoad(key, this.asyncCache.cache().executor())
                        : this.asyncCache.loader.asyncReload(key, oldValue, this.asyncCache.cache().executor());
                refreshFuture.whenComplete((newValue, error) -> {
                    long loadTime = this.asyncCache.cache().statsTicker().read() - now;
                    if(error != null) {
                        this.asyncCache.cache().statsCounter().recordLoadFailure(loadTime);
                        if(!(error instanceof CancellationException) && !(error instanceof TimeoutException))
                            logger.log(Level.WARNING, "Exception thrown during refresh", error);
                        return;
                    }

                    // 正常load之后，原子性替换
                    boolean[] discard = new boolean[1];
                    this.asyncCache.cache().compute(key, (k, currentValue) -> {
                        if(currentValue == null) {  // 旧值为null直接替换
                            return newValue == null ? null : refreshFuture;
                        }else if(currentValue == oldFuture) {   // 相等说明这段时间value没有被其它线程替换，可以正式替换
                            long expectedWriteTime = writeTime[0];
                            if(this.asyncCache.cache().hasWriteTime())
                                this.asyncCache.cache().getIfPresentQuietly(key, writeTime);
                            if(writeTime[0] == expectedWriteTime)   // 防止ABA问题
                                return newValue == null ? null : refreshFuture;
                        }
                        discard[0] = true;  // 到这里说明value被其它线程替换了，保持原值不能替换
                        return currentValue;
                    }, false, false, true);

                    /** 这里原作者写的是discard[0]，怀疑是bug，此线程替换值了才应该通知 */
                    if(!discard[0] && this.asyncCache.cache().hasRemovalListener())
                        this.asyncCache.cache().notifyRemoval(key, refreshFuture, RemovalCause.REPLACED);
                    if(newValue == null)
                        this.asyncCache.cache().statsCounter().recordLoadFailure(loadTime);
                    else
                        this.asyncCache.cache().statsCounter().recordLoadSuccess(loadTime);
                });
            });
        }
    }
}
