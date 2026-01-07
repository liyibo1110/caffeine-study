package com.github.liyibo1110.caffeine.cache;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * 计算条目何时过期
 * @author liyibo
 * @date 2026-01-06 11:52
 */
public interface Expiry<K, V> {

    /**
     * 指定一旦条目创建后的持续时间已过，应自动从缓存中移除
     * 如果想让条目不过期，可以设置一个很长的期限
     * @return 条目的生存期，单位是纳秒
     */
    long expireAfterCreate(@NonNull K key, @NonNull V value, long currentTime);

    /**
     * 指定一旦条目值被替换后的持续时间已过，应自动从缓存中移除
     * 如果想让条目不过期，可以设置一个很长的期限
     * @param currentDuration 当前的持续时间（应该就是从创建或者上一次修改之后经过的时间），单位是纳秒
     * @return 条目的生存期，单位是纳秒
     */
    long expireAfterUpdate(@NonNull K key, @NonNull V value, long currentTime,
                           @NonNegative long currentDuration);

    /**
     * 指定条目在最后一次读取后又经过了一段时间，应自动从缓存中移除
     * 如果想让条目不过期，可以设置一个很长的期限
     * @param currentDuration 当前的持续时间（应该就是从创建或者上一次修改之后经过的时间），单位是纳秒
     * @return 条目的生存期，单位是纳秒
     */
    long expireAfterRead(@NonNull K key, @NonNull V value, long currentTime,
                         @NonNegative long currentDuration);
}
