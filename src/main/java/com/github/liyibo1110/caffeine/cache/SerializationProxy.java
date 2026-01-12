package com.github.liyibo1110.caffeine.cache;

import org.checkerframework.checker.units.qual.K;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * 序列化cache的配置，并在反序列化时使用Caffeine将其重新构建为Cache、LoadingCache或AsyncLoadingCache
 * 原来cache中的数据不会被保留
 * @author liyibo
 * @date 2026-01-12 15:06
 */
public class SerializationProxy<K, V> implements Serializable {
    private static final long serialVersionUID = 1;

    boolean async;
    boolean weakKeys;
    boolean weakValues;
    boolean softValues;
    boolean isRecordingStats;
    long refreshAfterWriteNanos;
    long expiresAfterWriteNanos;
    long expiresAfterAccessNanos;
    long maximumSize = Caffeine.UNSET_INT;
    long maximumWeight = Caffeine.UNSET_INT;

    Ticker ticker;
    Expiry<?, ?> expiry;
    Weigher<?, ?> weigher;
    CacheWriter<?, ?> writer;
    AsyncCacheLoader<?, ?> loader;
    RemovalListener<?, ?> removalListener;

    /**
     * 根据实例中的属性，重新构建Caffeine实例并填充需要的配置
     */
    Caffeine<Object, Object> recreateCaffeine() {
        Caffeine<Object, Object> b = Caffeine.newBuilder();
        if(this.ticker != null)
            b.ticker(this.ticker);
        if(this.isRecordingStats)
            b.recordStats();
        if(this.maximumSize != Caffeine.UNSET_INT)
            b.maximumSize(this.maximumSize);
        if(this.weigher != null) {
            b.maximumWeight(this.maximumWeight);
            b.weigher((Weigher<Object, Object>)this.weigher);
        }
        if(this.expiry != null)
            b.expireAfter(this.expiry);
        if(this.expiresAfterWriteNanos > 0)
            b.expireAfterWrite(this.expiresAfterWriteNanos, TimeUnit.NANOSECONDS);
        if(this.expiresAfterAccessNanos > 0)
            b.expireAfterAccess(this.expiresAfterAccessNanos, TimeUnit.NANOSECONDS);
        if(this.refreshAfterWriteNanos > 0)
            b.refreshAfterWrite(this.refreshAfterWriteNanos, TimeUnit.NANOSECONDS);
        if(this.weakKeys)
            b.weakKeys();
        if(this.weakValues)
            b.weakValues();
        if(this.softValues)
            b.softValues();
        if(this.removalListener != null)
            b.removalListener((RemovalListener<Object, Object>)this.removalListener);
        if(this.writer != null && this.writer != CacheWriter.disabledWriter()) {
            if(this.writer instanceof Caffeine.CacheWriterAdapter)
                b.evictionListener(((Caffeine.CacheWriterAdapter<?, ?>)this.writer).delegate);
            else
                b.writer((CacheWriter<Object, Object>)this.writer);
        }
        return b;
    }

    Object readResolve() {
        Caffeine<Object, Object> builder = this.recreateCaffeine();
        if(this.async) {
            if(loader == null)
                return builder.buildAsync();    // 说明是普通的AsyncCache，不带Loading
            AsyncCacheLoader<K, V> cacheLoader = (AsyncCacheLoader<K, V>)this.loader;
            return builder.buildAsync(cacheLoader);
        }

        if(this.loader == null)
            return builder.build(); // 说明是Cache，不带Loading

        CacheLoader<K, V> cacheLoader = (CacheLoader<K, V>)this.loader;
        return builder.build(cacheLoader);  // 说明是LoadingCache
    }
}
