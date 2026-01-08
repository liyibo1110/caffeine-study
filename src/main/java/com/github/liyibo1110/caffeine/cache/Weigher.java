package com.github.liyibo1110.caffeine.cache;

import java.io.Serializable;
import java.util.Objects;

/**
 * 决定一个cache实体的权重，使用总权重阈值来决定何时需要执行清除操作
 * @author liyibo
 * @date 2026-01-05 15:05
 */
@FunctionalInterface
public interface Weigher<K, V> {

    /**
     * 计算一个cache实体的权重，这个值没有具体单位，只是可以和其它cache相对应比较
     * @return cache的权重，不能是负数
     */
    int weigh(K key, V value);

    /**
     * 获取SingletonWeigher单例
     */
    static <K, V> Weigher<K, V> singleonWeigher() {
        Weigher<K, V> self = (Weigher<K, V>)SingletonWeigher.INSTANCE;
        return self;
    }

    /**
     * 获取一个指定的Weigher
     */
    static <K, V> Weigher<K, V> boundedWeigher(Weigher<K, V> delegate) {
        return new BoundedWeigher<>(delegate);
    }
}

/**
 * 永远返回1的Weigher实现
 */
enum SingletonWeigher implements Weigher<Object, Object> {
    INSTANCE;

    @Override
    public int weigh(Object key, Object value) {
        return 1;
    }
}

final class BoundedWeigher<K, V> implements Weigher<K, V>, Serializable {
    static final long serialVersionUID = 1;
    final Weigher<? super K, ? super V> delegate;

    BoundedWeigher(Weigher<? super K, ? super V> delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public int weigh(K key, V value) {
        int weight = this.delegate.weigh(key, value);
        // 确保必须大于0
        Caffeine.requireArgument(weight >= 0);
        return weight;
    }

    Object writeReplace() {
        return this.delegate;
    }
}
