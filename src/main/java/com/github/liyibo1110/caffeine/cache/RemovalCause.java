package com.github.liyibo1110.caffeine.cache;

/**
 * 一个cache条目被移除的原因
 * @author liyibo
 * @date 2026-01-05 16:16
 */
public enum RemovalCause {
    /**
     * 被用户手动地移除，涉及如下方法：
     * <ul>
     *   <li>{@link Cache#invalidate}</li>
     *   <li>{@link Cache#invalidateAll(Iterable)}</li>
     *   <li>{@link Cache#invalidateAll()}</li>
     *   <li>{@link java.util.Map#remove}</li>
     *   <li>{@link java.util.Map#computeIfPresent}</li>
     *   <li>{@link java.util.Map#compute}</li>
     *   <li>{@link java.util.Map#merge}</li>
     *   <li>{@link java.util.concurrent.ConcurrentMap#remove}</li>
     * </ul>
     * 用户也可以通过以下方法，在key、value或者entry中手动执行删除操作
     * <ul>
     *   <li>{@link java.util.Collection#remove}</li>
     *   <li>{@link java.util.Collection#removeAll}</li>
     *   <li>{@link java.util.Collection#removeIf}</li>
     *   <li>{@link java.util.Collection#retainAll}</li>
     *   <li>{@link java.util.Iterator#remove}</li>
     * </ul>
     */
    EXPLICIT {
        @Override
        public boolean wasEvicted() {
            return false;
        }
    },

    /**
     * 条目本身未被移除，但是值被用户替换了，涉及如下方法：
     * <ul>
     *   <li>{@link Cache#put}</li>
     *   <li>{@link Cache#putAll}</li>
     *   <li>{@link LoadingCache#getAll}</li>
     *   <li>{@link LoadingCache#refresh}</li>
     *   <li>{@link java.util.Map#put}</li>
     *   <li>{@link java.util.Map#putAll}</li>
     *   <li>{@link java.util.Map#replace}</li>
     *   <li>{@link java.util.Map#computeIfPresent}</li>
     *   <li>{@link java.util.Map#compute}</li>
     *   <li>{@link java.util.Map#merge}</li>
     * </ul>
     */
    REPLACED {
        @Override
        public boolean wasEvicted() {
            return false;
        }
    },

    /**
     * 条目因为垃圾回收等动作，被自动删除，涉及的调用有：
     * {@link Caffeine#weakKeys}、{@link Caffeine#weakValues}、{@link Caffeine#softValues}
     */
    COLLECTED {
        @Override
        public boolean wasEvicted() {
            return true;
        }
    },

    /**
     * 条目到期，涉及的调用有：
     * {@link Caffeine#expireAfterWrite}、{@link Caffeine#expireAfterAccess}、{@link Caffeine#expireAfter(Expiry)}
     */
    EXPIRED {
        @Override
        public boolean wasEvicted() {
            return true;
        }
    },

    /**
     * 因为容量限制而被移除，涉及的调用有：
     * {@link Caffeine#maximumSize}、{@link Caffeine#maximumWeight}
     */
    SIZE {
        @Override
        public boolean wasEvicted() {
            return true;
        }
    };

    /**
     * 如果由于被驱逐而移除，则返回true（EXPLICIT和REPLACED回返回false）
     */
    public abstract boolean wasEvicted();
}
