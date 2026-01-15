package com.github.liyibo1110.caffeine.cache;

import org.checkerframework.checker.index.qual.NonNegative;

import java.lang.ref.ReferenceQueue;

/**
 * 相当于Map中的Entry，包含了key、value以及权重、访问和写入的元数据
 * key和value可能是weak或者soft类型引用，需要进行身份比较
 * @author liyibo
 * @date 2026-01-14 14:50
 */
abstract class Node<K, V> implements AccessOrderDeque.AccessOrder<Node<K, V>>, WriteOrderDeque.WriteOrder<Node<K, V>> {

    /**
     * 返回key，如果已被GC，返回null
     */
    public abstract K getKey();

    /**
     * 如果是强引用，则正常返回key，否则返回用于封装的WeakReference
     */
    public abstract Object getKeyReference();

    /**
     * 返回value，如果已被GC，返回null
     */
    public abstract V getValue();

    /**
     * 如果是强引用，则正常返回key，否则返回用于封装的Reference
     */
    public abstract Object getValueReference();

    /**
     * 设置value，可能是强、弱、软引用
     */
    public abstract void setValue(V value, ReferenceQueue<V> referenceQueue);

    /**
     * 强引用通过相等来比较，其它引用通过身份来比较
     */
    public abstract boolean containsValue(Object value);

    public int getWeight() {
        return 1;
    }

    public void setWeight(int weight) {}

    public int getPolicyWeight() {
        return 1;
    }

    public void setPolicyWeight(@NonNegative int weight) {}

    /* --------------- Health相关 --------------- */

    /**
     * cache中是否存在该entry
     */
    public abstract boolean isAlive();

    /**
     * 在cache中是否已被移除
     */
    public abstract boolean isRetired();

    /**
     * 在cache中是否已被移除
     */
    public abstract boolean isDead();

    /**
     * 设置为retired状态
     */
    public abstract void retire();

    /**
     * 设置为dead状态
     */
    public abstract void die();

    /* --------------- Variable order相关 --------------- */

    /**
     * 返回变量的过期时间，单位是纳秒
     */
    public long getVariableTime() {
        return 0L;
    }

    public void setVariableTime(long time) {}

    /**
     * 对variableTime进行cas操作（即当前值等于预期值再替换，整个过程具有原子性）
     */
    public boolean casVariableTime(long expect, long update) {
        throw new UnsupportedOperationException();
    }

    public Node<K, V> getPreviousInVariableOrder() {
        throw new UnsupportedOperationException();
    }

    public void setPreviousInVariableOrder(Node<K, V> prev) {
        throw new UnsupportedOperationException();
    }

    public Node<K, V> getNextInVariableOrder() {
        throw new UnsupportedOperationException();
    }

    public void setNextInVariableOrder(Node<K, V> prev) {
        throw new UnsupportedOperationException();
    }

    /* --------------- Access order相关 --------------- */

    /**
     * 试用期状态：
     * 1、新加入不久的
     * 2、容量不大
     * 作用：新来的在这儿
     */
    public static final int WINDOW = 0;
    /**
     * 观察期（主区）状态：
     * 1、由PROTECTED状态降低而来
     * 2、最近不太活跃
     * 3、容量较大
     * 作用：优先会淘汰这种状态的Node
     */
    public static final int PROBATION = 1;
    /**
     * 核心区（主区）状态
     * 1、被频繁访问
     * 2、多次被命中
     * 3、容量有限，但大于WINDOW
     * 作用：热点Node，尽量不让这种状态的Node被淘汰
     */
    public static final int PROTECTED = 2;

    public boolean inWindow() {
        return this.getQueueType() == WINDOW;
    }

    public boolean inMainProbation() {
        return this.getQueueType() == PROBATION;
    }

    public boolean inMainProtected() {
        return this.getQueueType() == PROTECTED;
    }

    public void makeWindow() {
        this.setQueueType(WINDOW);
    }

    public void makeMainProbation() {
        this.setQueueType(PROBATION);
    }

    public void makeMainProtected() {
        this.setQueueType(PROTECTED);
    }

    /**
     * 返回entry所在的队列（window、probation、protected）
     */
    public int getQueueType() {
        return WINDOW;
    }

    /**
     * 设置entry所在的队列（window、probation、protected）
     */
    public void setQueueType(int queueType) {
        throw new UnsupportedOperationException();
    }

    /**
     * 此entry最后被访问的时间点，单位是纳秒
     */
    public long getAccessTime() {
        return 0L;
    }

    public void setAccessTime(long time) {}

    @Override
    public Node<K, V> getPreviousInAccessOrder() {
        return null;
    }

    @Override
    public void setPreviousInAccessOrder(Node<K, V> prev) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Node<K, V> getNextInAccessOrder() {
        return null;
    }

    @Override
    public void setNextInAccessOrder(Node<K, V> next) {
        throw new UnsupportedOperationException();
    }

    /* --------------- Write order相关 --------------- */

    /**
     * 此entry最后被写入的时间点，单位是纳秒
     */
    public long getWriteTime() {
        return 0L;
    }

    public void setWriteTime(long time) {}

    public boolean casWriteTime(long expect, long update) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Node<K, V> getPreviousInWriteOrder() {
        return null;
    }

    @Override
    public void setPreviousInWriteOrder(Node<K, V> prev) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Node<K, V> getNextInWriteOrder() {
        return null;
    }

    @Override
    public void setNextInWriteOrder(Node<K, V> next) {
        throw new UnsupportedOperationException();
    }

    @Override
    public final String toString() {
        return String.format("%s=[key=%s, value=%s, weight=%d, queueType=%,d, accessTimeNS=%,d, "
                        + "writeTimeNS=%,d, varTimeNs=%,d, prevInAccess=%s, nextInAccess=%s, prevInWrite=%s, "
                        + "nextInWrite=%s]", getClass().getSimpleName(), getKey(), getValue(), getWeight(),
                getQueueType(), getAccessTime(), getWriteTime(), getVariableTime(),
                getPreviousInAccessOrder() != null, getNextInAccessOrder() != null,
                getPreviousInWriteOrder() != null, getNextInWriteOrder() != null);
    }
}
