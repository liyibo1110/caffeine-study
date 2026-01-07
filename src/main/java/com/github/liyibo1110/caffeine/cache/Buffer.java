package com.github.liyibo1110.caffeine.cache;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.function.Consumer;

/**
 * 用于多生产者 -> 单消费者的缓冲区
 * 如果已满或者因争用而错误失败，则会拒绝接收新元素
 * 和队列或栈不同，Buffer不保证元素按先进先出（FIFO）或后进先出（LIFO）的顺序排列
 * @author liyibo
 * @date 2026-01-06 11:45
 */
interface Buffer<E> {
    int FULL = 1;
    int SUCCESS = 0;
    int FAILED = -1;

    /**
     * 放置新元素
     */
    int offer(@NonNull E e);

    /**
     * 清空Buffer，并传入指定的Consumer来处理
     * @param consumer
     */
    void drainTo(@NonNull Consumer<E> consumer);

    /**
     * 返回从Buffer中读取走的元素个数
     */
    int reads();

    /**
     * 返回向Buffer中写入的元素个数
     */
    int writes();

    /**
     * 返回Buffer内部持有的元素数
     */
    default int size() {
        return this.writes() - reads();
    }

    /**
     * 返回DisabledBufferd的实现单例
     */
    static <E> Buffer<E> disabled() {
        return (Buffer<E>)DisabledBuffer.INSTANCE;
    }
}

/**
 * 不进行任何存储的Buffer实现
 */
enum DisabledBuffer implements Buffer<Object> {
    INSTANCE;

    @Override
    public int offer(Object obj) {
        return Buffer.SUCCESS;
    }

    @Override
    public void drainTo(Consumer<Object> consumer) {}

    @Override
    public int reads() {
        return 0;
    }

    @Override
    public int writes() {
        return 0;
    }
}
