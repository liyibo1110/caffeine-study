package com.github.liyibo1110.caffeine.cache;

/**
 * 内部用的事件暂存器（无序 + 允许失败 + 批量消费drainTo）
 * PS：内容直接抄的，先当作黑盒子
 * @author liyibo
 * @date 2026-01-10 01:43
 */
public class BoundedBuffer<E> extends StripedBuffer<E> {
    @Override
    protected Buffer<E> create(E e) {
        return null;
    }
}
