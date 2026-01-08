package com.github.liyibo1110.caffeine.cache;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * 查出CompletableFuture<V>后的取值收尾工作，即委托LocalCache进行cache操作（replace/remove）
     */
    default void handleCompletion(K key, CompletableFuture<V> valueFuture, long startTime, boolean recordMiss) {
        AtomicBoolean completed = new AtomicBoolean();
        valueFuture.whenComplete((value, error) -> {
            // 工程保护代码
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
}
