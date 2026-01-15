package com.github.liyibo1110.caffeine.cache;

import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 分层时间轮，用来高效地管理可能过期的Node
 * @author liyibo
 * @date 2026-01-15 11:14
 */
public class TimerWheel<K, V> {
    static final int[] BUCKETS = { 64, 64, 32, 4, 1 };
    static final long[] SPANS = {
        Caffeine.ceilingPowerOfTwo(TimeUnit.SECONDS.toNanos(1)), // 1.07秒
        Caffeine.ceilingPowerOfTwo(TimeUnit.MINUTES.toNanos(1)), // 1.14分钟
        Caffeine.ceilingPowerOfTwo(TimeUnit.HOURS.toNanos(1)),   // 1.22小时
        Caffeine.ceilingPowerOfTwo(TimeUnit.DAYS.toNanos(1)),    // 1.63天
        BUCKETS[3] * Caffeine.ceilingPowerOfTwo(TimeUnit.DAYS.toNanos(1)), // 6.5天
        BUCKETS[3] * Caffeine.ceilingPowerOfTwo(TimeUnit.DAYS.toNanos(1)), // 6.5天（兜底用的）
    };

    /**
     * 用多少位二进制，能表示对应的时间粒度
     * 例如SPANS[0] = 2^30 ~= 1秒，所SHIFT[0]= 30（即二进制0的个数）
     */
    static final long[] SHIFT = {
        Long.numberOfTrailingZeros(SPANS[0]),
        Long.numberOfTrailingZeros(SPANS[1]),
        Long.numberOfTrailingZeros(SPANS[2]),
        Long.numberOfTrailingZeros(SPANS[3]),
        Long.numberOfTrailingZeros(SPANS[4])
    };

    final BoundedLocalCache<K, V> cache;
    final Node<K, V>[][] wheel;
    /** 当前时间 */
    long nanos;

    TimerWheel(BoundedLocalCache<K, V> cache) {
        this.cache = Objects.requireNonNull(cache);
        this.wheel = new Node[BUCKETS.length][1];
        for(int i = 0; i < this.wheel.length; i++) {
            this.wheel[i] = new Node[BUCKETS[i]];
            for(int j = 0; j < this.wheel[i].length; j++)
                this.wheel[i][j] = new Sentinel<>();
        }
    }

    /**
     * 推进计时器并移除过期的entry
     * @param currentTimeNanos 当前时间
     */
    public void advance(long currentTimeNanos) {
        long previousTimeNanos = this.nanos; // 暂存上一轮的时间点
        try {
            this.nanos = currentTimeNanos;
            if(previousTimeNanos < 0 && currentTimeNanos > 0) {
                previousTimeNanos += Long.MAX_VALUE;
                currentTimeNanos += Long.MAX_VALUE;
            }

            for(int i = 0; i < SHIFT.length; i++) {
                long previousTicks = previousTimeNanos >>> SHIFT[i];
                long currentTicks = currentTimeNanos >>> SHIFT[i];
                // 重要判断，判断2个时间差，是否跨越了当前时间级别粒度（即大约1秒、1分钟、1小时、1天、6.5天）
                if((currentTicks - previousTicks) <= 0L)
                    break;
            }
        } catch (Throwable t) {
            this.nanos = previousTimeNanos;
            throw t;
        }
    }

    /**
     * 核心清理方法：如果entry仍有效，则将其过期，或重新安排到适当的bucket中
     */
    void expire(int index, long previousTicks, long currentTicks) {
        Node<K, V>[] timerWheel = this.wheel[index];
        int mask = timerWheel.length - 1;   // buckets - 1

        int steps = Math.min(1 + Math.abs((int)(currentTicks - previousTicks)), timerWheel.length);
        int start = (int)(previousTicks & mask);    // 要清理的bucket上标
        int end = start + steps;    // 要清理的bucket下标

        // 尝试清理
        for(int i = start; i < end; i++) {
            Node<K, V> sentinel = timerWheel[i & mask];
            Node<K, V> prev = sentinel.getPreviousInVariableOrder();
            Node<K, V> node = sentinel.getNextInVariableOrder();
            // 哨兵暂时脱钩
            sentinel.setPreviousInVariableOrder(sentinel);
            sentinel.setNextInVariableOrder(sentinel);
            while(node != sentinel) {
                Node<K, V> next = node.getNextInVariableOrder();
                node.setPreviousInVariableOrder(null);
                node.setNextInVariableOrder(null);

                try {
                    if(node.getVariableTime() - this.nanos > 0
                        || !this.cache.evictEntry(node, RemovalCause.EXPIRED, this.nanos)) {
                        // 如果真的未过期，或没有被移除，则尝试重新安置到合适的bucket中
                        schedule(node);
                    }
                    node = next;
                } catch (Throwable t) {
                    // 还原
                    node.setPreviousInVariableOrder(sentinel.getPreviousInVariableOrder());
                    node.setNextInVariableOrder(next);
                    sentinel.getPreviousInVariableOrder().setNextInVariableOrder(node);
                    sentinel.setPreviousInVariableOrder(prev);
                    throw t;
                }
            }
        }
    }

    /**
     * 将Node调度至合适的bucket中
     */
    public void schedule(Node<K, V> node) {
        Node<K, V> sentinel = this.findBucket(node.getVariableTime());
        this.link(sentinel, node);
    }

    /**
     * 将Node重新调度至合适的bucket中
     */
    public void reschedule(Node<K, V> node) {
        if(node.getNextInVariableOrder() != null) {
            this.unlink(node);
            this.schedule(node);
        }
    }

    /**
     * 如果entry存在，从相应bucket中移除
     */
    public void deschedule(Node<K, V> node) {
        this.unlink(node);
        node.setNextInVariableOrder(null);
        node.setPreviousInVariableOrder(null);
    }

    /**
     * 根据给定的实际到期时间点，返回合适的bucket的Sentinel实例
     * @param time
     */
    Node<K, V> findBucket(long time) {
        long duration = time - this.nanos;
        int length = this.wheel.length - 1;
        for(int i = 0; i < length; i++) {
           if(duration < SPANS[i + 1]) {
               long ticks = time >>> SHIFT[i];
               int index = (int)(ticks & (this.wheel[i].length - 1));
               return this.wheel[i][index];
           }
        }
        return this.wheel[length][0];
    }

    /**
     * 添加entry至bucket链表的尾部
     */
    void link(Node<K, V> sentinel, Node<K, V> node) {
        node.setPreviousInVariableOrder(sentinel.getPreviousInVariableOrder());
        node.setNextInVariableOrder(sentinel);
        sentinel.getPreviousInAccessOrder().setNextInAccessOrder(node);
        sentinel.setPreviousInVariableOrder(node);
    }

    void unlink(Node<K, V> node) {
        Node<K, V> next = node.getNextInVariableOrder();
        if(next != null) {
            Node<K, V> prev = node.getPreviousInVariableOrder();
            next.setPreviousInVariableOrder(prev);
            prev.setNextInVariableOrder(next);
        }
    }

    /**
     * 返回到下一个bucket时间点的剩余时间
     */
    public long getExpirationDelay() {
        for(int i = 0; i < SHIFT.length; i++) {
            Node<K, V>[] timeWheel = this.wheel[i];
            long ticks = this.nanos >>> SHIFT[i];

            long spanMask = SPANS[i] - 1;
            int start = (int)(ticks & spanMask);
            int end = start + timeWheel.length;
            int mask = timeWheel.length - 1;
            for (int j = start; j < end; j++) {
                Node<K, V> sentinel = timeWheel[j & mask];
                Node<K, V> next = sentinel.getNextInVariableOrder();
                if(next == sentinel)    // 跳过sentinel
                    continue;
                long buckets = j - start;
                long delay = (buckets << SHIFT[i]) - (this.nanos & spanMask);
                delay = delay > 0 ? delay : SPANS[i];

                for(int k = i + 1; k < SHIFT.length; k++) {
                    long nextDelay = this.peekAhead(k);
                    delay = Math.min(delay, nextDelay);
                }
                return delay;
            }
        }
        return Long.MAX_VALUE;
    }

    long peekAhead(int i) {
        long ticks = this.nanos >>> SHIFT[i];
        Node<K, V>[] timerWheel = this.wheel[i];

        long spanMask = SPANS[i] - 1;
        int mask = timerWheel.length - 1;
        int probe = (int)((ticks + 1) & mask);
        Node<K, V> sentinel = timerWheel[probe];
        Node<K, V> next = sentinel.getNextInVariableOrder();
        return next == sentinel ? Long.MAX_VALUE : (SPANS[i] - (this.nanos & spanMask));
    }

    /**
     * 返回一个只读的，大致按过期时间排序的快照映射
     * 落在bucket范围内的timer不进行排序
     */
    public Map<K, V> snapshot(boolean ascending, int limit, Function<V, V> transformer) {
        Caffeine.requireArgument(limit >= 0);

        Map<K, V> map = new LinkedHashMap<>(Math.min(limit, this.cache.size()));
        int startLevel = ascending ? 0 : this.wheel.length - 1;
        for(int i = 0; i < this.wheel.length; i++) {
            int indexOffset = ascending ? i : -i;
            int index = startLevel + indexOffset;

            int ticks = (int)(this.nanos >>> SHIFT[index]);
            int bucketMask = this.wheel[index].length - 1;
            int startBucket = (ticks & bucketMask) + (ascending ? 1 : 0);
            for(int j = 0; j < this.wheel[index].length; j++) {
                int bucketOffset = ascending ? j : -j;
                Node<K, V> sentinel = this.wheel[index][(startBucket + bucketOffset) & bucketMask];

                for(Node<K, V> node = traverse(ascending, sentinel);
                    node != sentinel; node = traverse(ascending, node)) {
                    if(map.size() >= limit)
                        break;

                    K key = node.getKey();
                    V value = transformer.apply(node.getValue());
                    if(key != null && value != null && node.isAlive())
                        map.put(key, value);
                }
            }
        }
        return Collections.unmodifiableMap(map);
    }

    static <K, V> Node<K, V> traverse(boolean ascending, Node<K, V> node) {
        return ascending ? node.getNextInVariableOrder() : node.getPreviousInVariableOrder();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < this.wheel.length; i++) {
            Map<Integer, List<K>> buckets = new TreeMap<>();
            for(int j = 0; j < this.wheel[i].length; j++) {
                List<K> events = new ArrayList<>();
                for(Node<K, V> node = this.wheel[i][j].getNextInVariableOrder();
                    node != this.wheel[i][j]; node = node.getNextInVariableOrder()) {
                    events.add(node.getKey());
                }
                if(!events.isEmpty())
                    buckets.put(j, events);
            }
            sb.append("Wheel #").append(i + 1).append(": ").append(buckets).append('\n');
        }
        return sb.deleteCharAt(sb.length() - 1).toString();
    }

    /**
     * 桶中的双链链表哨兵
     */
    static final class Sentinel<K, V> extends Node<K, V> {
        Node<K, V> prev;
        Node<K, V> next;

        Sentinel() {
            this.prev = this;
            this.next = this;
        }

        @Override
        public Node<K, V> getPreviousInVariableOrder() {
            return this.prev;
        }

        @Override
        public Node<K, V> getNextInVariableOrder() {
            return this.next;
        }

        @Override
        public void setNextInVariableOrder(Node<K, V> next) {
            this.next = next;
        }

        @Override
        public K getKey() {
            return null;
        }

        @Override
        public Object getKeyReference() {
            return new UnsupportedOperationException();
        }

        @Override
        public V getValue() {
            return null;
        }

        @Override
        public Object getValueReference() {
            return new UnsupportedOperationException();
        }

        @Override
        public void setValue(V value, ReferenceQueue<V> referenceQueue) {}

        @Override
        public boolean containsValue(Object value) {
            return false;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public boolean isRetired() {
            return false;
        }

        @Override
        public boolean isDead() {
            return false;
        }

        @Override
        public void retire() {}

        @Override
        public void die() {}
    }
}
