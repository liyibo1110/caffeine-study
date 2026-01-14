package com.github.liyibo1110.caffeine.cache;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * weak引用和soft引用的相关工具和封装类
 * @author liyibo
 * @date 2026-01-13 22:53
 */
final class References {
    private References() {}

    /**
     * 包含key的引用，只能是weak或者soft类型的
     */
    interface InternalReference<E> {

        /**
         * 返回被引用的对象，如果对象已被程序或GC清除，则返回null
         */
        E get();

        /**
         * 如果key是强引用，则返回key实例本身，否则返回InternalReference实例
         */
        Object getKeyReference();

        /**
         * 判断和给定引用是否相等
         */
        default boolean referenceEquals(Object obj) {
            if(obj == this)
                return true;
            else if(obj instanceof InternalReference<?>) {
                InternalReference<?> ref = (InternalReference<?>)obj;
                return this.get() == ref.get();
            }
            return false;
        }
    }

    /**
     * 用来作为weakReference类型key的查找适配器，因为用户传来的key肯定是强引用的，需要转换成LookupKeyReference
     */
    static final class LookupKeyReference<E> implements InternalReference<E> {
        private final int hashCode;
        private final E e;

        public LookupKeyReference(@NonNull E e) {
            this.hashCode = System.identityHashCode(e); // 就是平台默认的hashCode方法实现
            this.e = Objects.requireNonNull(e);
        }

        @Override
        public E get() {
            return this.e;
        }

        @Override
        public Object getKeyReference() {
            return this;
        }

        @Override
        public boolean equals(Object obj) {
            return this.referenceEquals(obj);
        }

        @Override
        public int hashCode() {
            return this.hashCode;
        }
    }

    /**
     * 用来存储weakReference的cache key
     * 刻意存储了hashCode，为的是可以以O(1)时间从cache中查找并移除entry（因为key是weak类型，会被GC干掉）
     */
    static class WeakKeyReference<K> extends WeakReference<K> implements InternalReference<K> {
        private final int hashCode;

        public WeakKeyReference(K key, ReferenceQueue<K> queue) {
            super(key, queue);
            hashCode = System.identityHashCode(key);
        }

        @Override
        public Object getKeyReference() {
            return this;
        }

        @Override
        public boolean equals(Object object) {
            return this.referenceEquals(object);
        }

        @Override
        public int hashCode() {
            return this.hashCode;
        }
    }

    /**
     * 用来存储weakReference的cache value
     * 刻意存储了keyReference，为的是可以以O(1)时间从cache中查找并移除entry（因为value是weak类型，如果被GC干掉，通过keyReference可以再干掉entry）
     */
    static final class WeakValueReference<V> extends WeakReference<V> implements InternalReference<V> {
        private Object keyReference;

        public WeakValueReference(Object keyReference, V value, ReferenceQueue<V> queue) {
            super(value, queue);
            this.keyReference = keyReference;
        }

        @Override
        public Object getKeyReference() {
            return this.keyReference;
        }

        public void setKeyReference(Object keyReference) {
            this.keyReference = keyReference;
        }

        @Override
        public boolean equals(Object object) {
            return referenceEquals(object);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    /**
     * 用来存储SoftReference的cache value
     * 刻意存储了keyReference，为的是可以以O(1)时间从cache中查找并移除entry（因为value是soft类型，如果被GC干掉，通过keyReference可以再干掉entry）
     */
    static final class SoftValueReference<V> extends SoftReference<V> implements InternalReference<V> {
        private Object keyReference;

        public SoftValueReference(Object keyReference, V value, ReferenceQueue<V> queue) {
            super(value, queue);
            this.keyReference = keyReference;
        }

        @Override
        public Object getKeyReference() {
            return this.keyReference;
        }

        public void setKeyReference(Object keyReference) {
            this.keyReference = keyReference;
        }

        @Override
        public boolean equals(Object object) {
            return referenceEquals(object);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }
}
