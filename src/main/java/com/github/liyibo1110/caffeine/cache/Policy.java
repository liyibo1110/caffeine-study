package com.github.liyibo1110.caffeine.cache;

import com.google.errorprone.annotations.CompatibleWith;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * 基于缓存的运行时特性，进行检查和执行低级（low-level）操作的访问点
 * 这些操作都是可选的
 * @author liyibo
 * @date 2026-01-06 12:05
 */
public interface Policy<K, V> {

    /**
     * 是否缓存开启了统计
     */
    boolean isRecordingStats();

    /**
     * 返回key对应的value，如果没有值则返回null
     * 与Cache的getIfPresent不同的是，此方法不会有副作用，如更新统计信息、执行淘汰策略、重置过期时间或触发更新等
     */
    @Nullable
    default V getIfPresentQuietly(@NonNull @CompatibleWith("K") Object key) {
        throw new UnsupportedOperationException();
    }

    /**
     * 返回一个根据最大大小或者最大权重的淘汰策略
     * 如果缓存不是基于大小界限构建的，或不支持这些操作，要返回空的Optional
     */
    @NonNull
    Optional<Eviction<K, V>> eviction();

    /**
     * 返回一个根据闲置时间的过期策略，此策略规定一旦条目创建后，其值最近一次被替换后，或最后一次被访问后，经过了固定时长，则要被移除
     * 所有缓存读写操作，包括Cache.asMap().get(Object)、Cache.asMap().put(K, V)都会重置访问时间
     * 但Cache.asMap的集合视图上的操作则不会重置访问时间
     */
    @NonNull
    Optional<Expiration<K, V>> expireAfterAccess();

    /**
     * 返回一个根据生存时间的过期策略，此策略规定一旦条目创建后，或其值最近一次被替换后，经过了固定时长，则要被移除
     */
    @NonNull
    Optional<Expiration<K, V>> expireAfterWrite();

    /**
     * 返回一个根据可变的过期策略，此策略规定一旦条目的持续时间已过，则要被移除
     */
    @NonNull
    default Optional<VarExpiration<K, V>> expireVariably() {
        return Optional.empty();
    }

    /**
     * 返回一个根据生存时间的刷新策略（注意是刷新，而不是过期淘汰）
     * 此策略规定条目在创建后，或其值最近一次被替换后，经过了固定时长，应自动重新加载
     */
    @NonNull
    Optional<Expiration<K, V>> refreshAfterWrite();

    /**
     * 基于大小的淘汰策略的缓存的低级操作
     */
    interface Eviction<K, V> {
        /**
         * 返回缓存是否受最大大小或者最大权重的控制
         * @return
         */
        boolean isWeighted();

        /**
         * 返回指定key对应的条目权重
         * 如果缓存未使用加权大小限制，或不支持查询条目的权重，则返回空
         */
        @NonNull
        default OptionalInt weightOf(@NonNull K key) {
            // 此版本未实现
            return OptionalInt.empty();
        }

        /**
         * 返回缓存中条目的近似累积权重，如果未使用加权大小边界，则应该返回空
         * @return
         */
        @NonNull
        OptionalLong weightedSize();

        /**
         * 根据缓存的构造方式，返回最大总加权大小或未加权大小
         */
        @NonNegative
        long getMaximum();

        /**
         * 指定缓存的加权最大阈值，如果缓存当前超出了最大值，此操作会立即驱逐条目，直到缓存缩减到适当的大小
         */
        void setMaximum(@NonNegative long maximum);

        /**
         * 返回一个不可修改的缓存快照Map，其遍历顺序未为有序
         * 顺序是最不可能被保留的条目（即最冷）到最热
         * 此顺序由创建此快照的驱逐策略的最佳猜测决定
         */
        @NonNull
        Map<@NonNull K, @NonNull V> coldest(@NonNegative int limit);

        @NonNull
        Map<@NonNull K, @NonNull V> hottest(@NonNegative int limit);
    }

    /**
     * 具有固定过期策略的缓存的低级操作
     * 在3.x版本会改名为FixedExpiration
     */
    interface Expiration<K, V> {
        /**
         * 根据过期策略返回条目的年龄（age），根据缓存对自身条目过期时间上次重置以来，所经过的时间做出的估计
         * 过期策略通过将条目的age与新鲜度（freshness）生命周期相比较，来判断条目是否过时
         * 计算公式为： fresh = freshnessLifetime > age
         * freshnessLifetime = expires - currentTime
         * 此方法在3.x版本可能会被删除
         */
        @NonNull
        OptionalLong ageOf(@NonNull K key, @NonNull TimeUnit unit);

        @NonNull
        default Optional<Duration> ageOf(@NonNull K key) {
            OptionalLong duration = this.ageOf(key, TimeUnit.NANOSECONDS);
            return duration.isPresent()
                    ? Optional.of(Duration.ofNanos(duration.getAsLong()))
                    : Optional.empty();
        }

        /**
         * 返回一个固定的持续时间，用来判断条目是否因超过此时间而自动移除（意思就是还有多久就会要过期了）
         * 如果条目的age小于这个时间，则认为是新鲜的，否则即为超时
         * 过期策略决定了条目的age何时被重置
         * 此方法在3.x版本可能会被删除
         */
        @NonNegative
        long getExpiresAfter(@NonNull TimeUnit unit);

        @NonNull
        default Duration getExpiresAfter() {
            return Duration.ofNanos(this.getExpiresAfter(TimeUnit.NANOSECONDS));
        }

        void setExpiresAfter(@NonNegative long duration, @NonNull TimeUnit unit);

        default void setExpiresAfter(@NonNull Duration duration) {
            this.setExpiresAfter(duration.toNanos(), TimeUnit.NANOSECONDS);
        }

        /**
         * 返回一个不可修改的缓存快照Map，其遍历顺序未为有序
         * 顺序是最旧（最可能过期）到最新（最不可能过期）
         * 此顺序由创建此快照的驱逐策略的最佳猜测决定
         */
        @NonNull
        Map<@NonNull K, @NonNull V> oldest(@NonNegative int limit);

        @NonNull
        Map<@NonNull K, @NonNull V> youngest(@NonNegative int limit);
    }

    /**
     * 具有固可变过期策略的缓存的低级操作
     */
    interface VarExpiration<K, V> {

        /**
         * 返回条目自动移除前的持续时间（意思就是还有多久就会要过期了），过期策略决定了条目的持续时间何时会重置
         * 此方法在3.x版本可能会被删除
         */
        @NonNull
        OptionalLong getExpiresAfter(@NonNull K key, @NonNull TimeUnit unit);

        @NonNull
        default Optional<Duration> getExpiresAfter(@NonNull K key) {
            OptionalLong duration = this.getExpiresAfter(key, TimeUnit.NANOSECONDS);
            return duration.isPresent()
                    ? Optional.of(Duration.ofNanos(duration.getAsLong()))
                    : Optional.empty();
        }

        void setExpiresAfter(@NonNull K key, @NonNegative long duration, @NonNull TimeUnit unit);

        default void setExpiresAfter(@NonNull K key, @NonNull Duration duration) {
            this.setExpiresAfter(key, duration.toNanos(), TimeUnit.NANOSECONDS);
        }

        /**
         * 如果指定的key未有值，则写入新值
         * 与Map.putIfAbsent不同之处在于，它使用指定的写入持续时间来替换了配置的过期时间，如果条目已存在，则持续时间不改变，同时返回true，而不是value
         * 此方法在3.x版本可能会被删除
         */
        default boolean putIfAbsent(@NonNull K key, @NonNull V value,
                                    @NonNegative long duration, @NonNull TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        default boolean putIfAbsent(@NonNull K key, @NonNull V value, @NonNegative Duration duration) {
            return this.putIfAbsent(key, value, duration.toNanos(), TimeUnit.NANOSECONDS);
        }

        /**
         * 如果已有value，则替换
         * 与Cache.put不同之处在于，使用了指定的持续时间来替代配置的过期时间
         */
        default void put(@NonNull K key, @NonNull V value,
                         @NonNegative long duration, @NonNull TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        default void put(@NonNull K key, @NonNull V value, @NonNull Duration duration) {
            this.put(key, value, duration.toNanos(), TimeUnit.NANOSECONDS);
        }

        @NonNull
        Map<@NonNull K, @NonNull V> oldest(@NonNegative int limit);

        @NonNull
        Map<@NonNull K, @NonNull V> youngest(@NonNegative int limit);
    }
}
