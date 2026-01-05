package com.github.liyibo1110.caffeine.cache;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * 主要组件的builder类，以及static工具
 * @author liyibo
 * @date 2026-01-05 15:19
 */
public final class Caffeine<K, V> {
    static final Logger logger = Logger.getLogger(Caffeine.class.getName());

    @Nullable Ticker ticker;

    private Caffeine() {}

    /**
     * 确保expression为true，否则抛IllegalArgumentException异常
     * @param expression
     */
    static void requireArgument(boolean expression) {
        if(!expression)
            throw new IllegalArgumentException();
    }

    /**
     * 确保expression为true，否则抛IllegalArgumentException异常
     * @param expression
     * @param template
     * @param args
     */
    static void requireArgument(boolean expression, String template, @Nullable Object... args) {
        if(!expression)
            throw new IllegalArgumentException(String.format(template, args));
    }

    /**
     * 确保expression为true，否则抛IllegalStateException异常
     * @param expression
     */
    static void requireState(boolean expression) {
        if(!expression)
            throw new IllegalStateException();
    }

    /**
     * 确保expression为true，否则抛IllegalStateException异常（带自定义信息）
     * @param expression
     * @param template
     * @param args
     */
    static void requireState(boolean expression, String template, @Nullable Object... args) {
        if(!expression)
            throw new IllegalStateException(String.format(template, args));
    }

    /**
     * 返回大于或等于x的最小2次幂
     * 比如1 -> 1，10 -> 16，100 -> 128
     * @param x
     * @return
     */
    static int ceilingPowerOfTwo(int x) {
        return 1 << -Integer.numberOfLeadingZeros(x - 1);
    }

    static long ceilingPowerOfTwo(long x) {
        return 1L << -Long.numberOfLeadingZeros(x - 1);
    }

    /**
     * 返回空的builder
     * @return
     */
    public static Caffeine<Object, Object> newBuilder() {
        return new Caffeine<>();
    }

    /**
     * 设置Ticker实例
     * @param ticker
     * @return
     */
    public Caffeine<K, V> ticker(@NonNull Ticker ticker) {
        // 限制了只能被设置1次
        requireState(this.ticker == null, "Ticker was already set to %s", this.ticker);
        this.ticker = Objects.requireNonNull(ticker);
        return this;
    }
}
