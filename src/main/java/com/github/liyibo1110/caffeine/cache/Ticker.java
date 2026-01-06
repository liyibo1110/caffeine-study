package com.github.liyibo1110.caffeine.cache;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * 返回一个特定的时间值，单位是纳秒（默认有2种实现）
 * @author liyibo
 * @date 2026-01-05 14:51
 */
public interface Ticker {

    /**
     * 获取时间点
     */
    long read();

    /**
     * 获取SystemTicker单例
     */
    static @NonNull Ticker systemTicker() {
        return SystemTicker.INSTANCE;
    }

    /**
     * 获取DisabledTicker单例
     */
    static @NonNull Ticker disabledTicker() {
        return DisabledTicker.INSTANCE;
    }
}

/**
 * 获取当前时间点的Ticker实现
 */
enum SystemTicker implements Ticker {
    INSTANCE;

    @Override
    public long read() {
        return System.nanoTime();
    }
}

/**
 * 永远返回0的Ticker实现
 */
enum DisabledTicker implements Ticker {
    INSTANCE;

    @Override
    public long read() {
        return 0L;
    }
}
