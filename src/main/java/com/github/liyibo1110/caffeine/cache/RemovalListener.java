package com.github.liyibo1110.caffeine.cache;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * 当条目被移除时，要触发的监听器
 * 实现类应避免执行阻塞调用或在共享资源上进行同步
 * @author liyibo
 * @date 2026-01-06 11:38
 */
@FunctionalInterface
public interface RemovalListener<K, V> {

    /**
     * 当条目被移除时，会触发这个方法，并附带RemovalCause
     */
    void onRemoval(@Nullable K key, @Nullable V value, @NonNull RemovalCause cause);
}
