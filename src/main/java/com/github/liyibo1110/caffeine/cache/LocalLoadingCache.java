package com.github.liyibo1110.caffeine.cache;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CacheLoader调用 -> LocalCache中的compute/async调用的桥接器
 * @author liyibo
 * @date 2026-01-07 14:39
 */
interface LocalLoadingCache<K, V> extends LocalManualCache<K, V>, LoadingCache<K, V> {
    Logger logger = Logger.getLogger(LocalLoadingCache.class.getName());

    CacheLoader<? super K, V> cacheLoader();

    Function<K, V> mappingFunction();

    Function<Iterable<? extends K>, Map<K, V>> bulkMappingFunction();

    @Override
    default V get(K key) {
        return this.cache().computeIfAbsent(key, mappingFunction());
    }

    @Override
    default Map<K, V> getAll(Iterable<? extends K> keys) {
        Function<Iterable<? extends K>, Map<K, V>> mappingFunction = this.bulkMappingFunction();
        return (mappingFunction == null)
                ? this.loadSequentially(keys)
                : this.getAll(keys, mappingFunction);
    }

    /**
     * 串行调用mappingFunction生成value
     * 如果bulkMappingFunction返回null则会调用此串行版本的load
     */
    default Map<K, V> loadSequentially(Iterable<? extends K> keys) {
        Set<K> uniqueKeys = new LinkedHashSet<>();
        for(K key : keys)   // keys去重，个人理解是一种优化
            uniqueKeys.add(key);

        int count = 0;
        Map<K, V> result = new LinkedHashMap<>(uniqueKeys.size());
        try {
            for(K key : uniqueKeys) {
                count++;
                V value = this.get(key);    // 开始load
                if(value != null)
                    result.put(key, value);
            }
        } catch (Throwable t) {
            // 记录失败后面的所有数量
            this.cache().statsCounter().recordMissed(uniqueKeys.size() - count);
            throw t;
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    default void refresh(K key) {
        Objects.requireNonNull(key);

        long[] writeTime = new long[1];
        long startTime = this.cache().statsTicker().read();
        V oldValue = this.cache().getIfPresentQuietly(key, writeTime);
        CompletableFuture<V> refreshFuture = (oldValue == null)
                ? this.cacheLoader().asyncLoad(key, this.cache().executor())
                : this.cacheLoader().asyncReload(key, oldValue, this.cache().executor());
        refreshFuture.whenComplete((newValue, error) -> {
            long loadTime = this.cache().statsTicker().read() - startTime;
            if(error != null) {
                if(!(error instanceof CancellationException) && !(error instanceof TimeoutException))
                    this.logger.log(Level.WARNING, "Exception thrown during refresh", error);
                this.cache().statsCounter().recordLoadFailure(loadTime);
                return;
            }
            // 成功，进入最重要的原子更新区，即里面的判断和替换，是原子性的
            boolean[] discard = new boolean[1]; // 解决lambda里不能修改外部变量值的限制
            this.cache().compute(key, (k, currentValue) -> {
                if(currentValue == null) {  // refresh期间key没了
                    return newValue;
                }else if(currentValue == oldValue) {    // 理想的状况，value从refresh开始到现在，值没有变，但是里面还要再确认1次
                    long expectedWriteTime = writeTime[0];
                    if(this.cache().hasWriteTime()) // 再获取一次key的writeTime，防止ABA问题
                        this.cache().getIfPresentQuietly(key, writeTime);
                    if(writeTime[0] == expectedWriteTime)
                        return newValue;
                }
                // 其它情况都要丢弃refresh的value（可能是有线程put新值，key被替换，或者写时间不匹配等等）
                discard[0] = true;
                return currentValue;
            }, false, false, true);

            // 收尾
            if(discard[0] && this.cache().hasRemovalListener())
                this.cache().notifyRemoval(key, newValue, RemovalCause.REPLACED);
            if(newValue == null)
                this.cache().statsCounter().recordLoadFailure(loadTime);
            else
                this.cache().statsCounter().recordLoadSuccess(loadTime);
        });
    }

    /**
     * 将CacheLoader的实现实例，转换成Function类型
     * 目的是塞进参数为mappingFunction的方法里使用
     */
    static <K, V> Function<K, V> newMappingFunction(CacheLoader<? super K, V> cacheLoader) {
        return key -> {
            try {
                return cacheLoader.load(key);
            } catch (RuntimeException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // catch InterruptedException会清掉线程的中断标志，需要补回来
                throw new CompletionException(e);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        };
    }

    static <K, V> Function<Iterable<? extends K>, Map<K, V>> newBulkMappingFunction(CacheLoader<? super K, V> cacheLoader) {
        if(!hasLoadAll(cacheLoader))
            return null;
        return keysToLoad -> {
            try {
                Map<K, V> loaded = (Map<K, V>)cacheLoader.loadAll(keysToLoad);
                return loaded;
            } catch (RuntimeException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // catch InterruptedException会清掉线程的中断标志，需要补回来
                throw new CompletionException(e);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        };
    }

    /**
     * 返回指定的CacheLoader是否具备批量加载功能
     */
    static boolean hasLoadAll(CacheLoader<?, ?> loader) {
        try {
            // 用户实现了自己的loadAll，才会返回true
            Method classLoadAll = loader.getClass().getMethod("loadAll", Iterable.class);
            Method defaultLoadAll = CacheLoader.class.getMethod("loadAll", Iterable.class);
            return !classLoadAll.equals(defaultLoadAll);
        } catch (NoSuchMethodException | SecurityException e) {
            logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
            return false;
        }
    }
}
