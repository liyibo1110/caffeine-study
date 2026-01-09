package com.github.liyibo1110.caffeine.cache;

import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * 异步操作相关工具
 * @author liyibo
 * @date 2026-01-08 23:45
 */
final class Async {
    static final long ASYNC_EXPIRY = (Long.MAX_VALUE >> 1) + (Long.MAX_VALUE >> 2); // 220年

    private Async() {}

    /**
     * future是否已成功完成
     */
    static boolean isReady(CompletableFuture<?> future) {
        return (future != null) && future.isDone()
                && !future.isCompletedExceptionally()
                && (future.join() != null);
    }

    /**
     * future是否已成功完成，完成则取返回值，否则返回null
     */
    static <V> V getIfReady(CompletableFuture<V> future) {
        return isReady(future) ? future.join() : null;
    }

    /**
     * 直接取值，如完成则返回值，未完成或错误则返回null
     */
    static <V> V getWhenSuccessful(CompletableFuture<V> future) {
        try {
            return (future == null) ? null : future.join();
        } catch (CancellationException | CompletionException e) {
            return null;
        }
    }

    /***
     * RemovalListener的异步实现
     */
    static final class AsyncRemovalListener<K, V> implements RemovalListener<K, CompletableFuture<V>>, Serializable {
        private static final long serialVersionUID = 1L;

        final RemovalListener<K, V> delegate;
        final Executor executor;

        AsyncRemovalListener(RemovalListener<K, V> delegate, Executor executor) {
            this.delegate = Objects.requireNonNull(delegate);
            this.executor = Objects.requireNonNull(executor);
        }

        @Override
        public void onRemoval(K key, CompletableFuture<V> future, RemovalCause cause) {
            if(future != null) {
                future.thenAcceptAsync(value -> {
                    if(value != null)
                        this.delegate.onRemoval(key, value, cause);
                }, executor);
            }
        }

        Object writeReplace() {
            return this.delegate;
        }
    }

    static final class AsyncWeigher<K, V> implements Weigher<K, CompletableFuture<V>>, Serializable {
        private static final long serialVersionUID = 1L;

        final Weigher<K, V> delegate;

        AsyncWeigher(Weigher<K, V> delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public int weigh(K key, CompletableFuture<V> future) {
            return isReady(future) ? this.delegate.weigh(key, future.join()) : 0;
        }

        Object writeReplace() {
            return delegate;
        }
    }

    static final class AsyncExpiry<K, V> implements Expiry<K, CompletableFuture<V>>, Serializable {
        private static final long serialVersionUID = 1L;

        final Expiry<K, V> delegate;

        AsyncExpiry(Expiry<K, V> delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public long expireAfterCreate(K key, CompletableFuture<V> future, long currentTime) {
            if(isReady(future)) {   // 完成了才开始计时
                long duration = this.delegate.expireAfterCreate(key, future.join(), currentTime);
                return Math.min(duration, BoundedLocalCache.MAXIMUM_EXPIRY);
            }
            return ASYNC_EXPIRY;
        }

        @Override
        public long expireAfterUpdate(K key, CompletableFuture<V> future, long currentTime, long currentDuration) {
            if(isReady(future)) {
                long duration = (currentDuration > BoundedLocalCache.MAXIMUM_EXPIRY)
                        ? this.delegate.expireAfterCreate(key, future.join(), currentTime)  // update相当于重新创建，duration会重置
                        : this.delegate.expireAfterUpdate(key, future.join(), currentTime, currentDuration);
                return Math.min(duration, BoundedLocalCache.MAXIMUM_EXPIRY);
            }
            return ASYNC_EXPIRY;
        }

        @Override
        public long expireAfterRead(K key, CompletableFuture<V> future, long currentTime, long currentDuration) {
            if(isReady(future)) {
                long duration = this.delegate.expireAfterRead(key, future.join(), currentTime, currentDuration);
                Math.min(duration, BoundedLocalCache.MAXIMUM_EXPIRY);
            }
            return ASYNC_EXPIRY;
        }

        Object writeReplace() {
            return delegate;
        }
    }
}
