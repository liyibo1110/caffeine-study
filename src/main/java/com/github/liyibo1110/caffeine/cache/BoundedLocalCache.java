package com.github.liyibo1110.caffeine.cache;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * @author liyibo
 * @date 2026-01-09 10:08
 */
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef<K, V> implements LocalCache<K, V> {
    static final Logger logger = Logger.getLogger(BoundedLocalCache.class.getName());

    /** cpu个数 */
    static final int NCPU = Runtime.getRuntime().availableProcessors();
    /** write buffer初始化大小 */
    static final int WRITE_BUFFER_MIN = 4;
    /** write buffer最大大小 */
    static final int WRITE_BUFFER_MAX = 128 * Caffeine.ceilingPowerOfTwo(NCPU);
    /** 在yield之前尝试写入write buffer的次数 */
    static final int WRITE_BUFFER_RETRIES = 100;
    /** map的最大加权值 */
    static final long MAXIMUM_CAPACITY = Long.MAX_VALUE - Integer.MAX_VALUE;
    /** 主空间所占的最大加权容量的初始百分比 */
    static final double PERCENT_MAIN = 0.99d;
    /** 受保护空间所占的最大加权容量的百分比 */
    static final double PERCENT_MAIN_PROTECTED = 0.80d;
    /** 让climber重新开始的命中率差异 */
    static final double HILL_CLIMBER_RESTART_THRESHOLD = 0.05d;
    /** 通过调整窗口以适应总大小的百分比 */
    static final double HILL_CLIMBER_STEP_PERCENT = 0.0625d;
    /** 减小步长以适应的速率 */
    static final double HILL_CLIMBER_STEP_DECAY_RATE = 0.98d;
    /** 队列间可传输的最大条目数 */
    static final int QUEUE_TRANSFER_THRESHOLD = 1000;
    /** 在expiration必须重排之前，entry更新之间的最大时间窗口 */
    static final long EXPIRE_WRITE_TOLERANCE = TimeUnit.SECONDS.toNanos(1);
    /** entry最大过期时间 */
    static final long MAXIMUM_EXPIRY = (Long.MAX_VALUE >> 1); // 150年

    final MpscGrowableArrayQueue<Runnable> writeBuffer;


    final CacheLoader<K, V> cacheLoader;
    final ReentrantLock evictionLock;
    final CacheWriter<K, V> writer;
    final Weigher<K, V> weigher;
    final Executor executor;
    final boolean async;

    /** 以下为集合视图 */
    transient Set<K> keySet;
    transient Collection<V> values;
    transient Set<Entry<K, V>> entrySet;
}

/**
 * 给BoundedLocalCache的后台线程维护和并发写入，提供了一个状态机 + 内存布局优化的底座
 * 可以理解成BoundedLocalCache专用的并发交通信号灯 + 防抖装置
 * 因为BoundedLocalCache写线程会很多，而且不能立即执行各种清理，以及要避免多个线程并发清理
 * 此组件来控制：什么时候执行清理？谁来执行清理？清理能不能延后等问题
 */
final class BLCHeader {
    abstract static class PadDrainStatus<K, V> extends AbstractMap<K, V> {
        /**
         * 保证DrainStatusRef类唯一字段drainStatus能和剩余的padding存到单一的cache line里面去，即独占一个cache line
         * 保存在父类，防止字段顺序被重排
         * */
        byte p000, p001, p002, p003, p004, p005, p006, p007;
        byte p008, p009, p010, p011, p012, p013, p014, p015;
        byte p016, p017, p018, p019, p020, p021, p022, p023;
        byte p024, p025, p026, p027, p028, p029, p030, p031;
        byte p032, p033, p034, p035, p036, p037, p038, p039;
        byte p040, p041, p042, p043, p044, p045, p046, p047;
        byte p048, p049, p050, p051, p052, p053, p054, p055;
        byte p056, p057, p058, p059, p060, p061, p062, p063;
        byte p064, p065, p066, p067, p068, p069, p070, p071;
        byte p072, p073, p074, p075, p076, p077, p078, p079;
        byte p080, p081, p082, p083, p084, p085, p086, p087;
        byte p088, p089, p090, p091, p092, p093, p094, p095;
        byte p096, p097, p098, p099, p100, p101, p102, p103;
        byte p104, p105, p106, p107, p108, p109, p110, p111;
        byte p112, p113, p114, p115, p116, p117, p118, p119;
    }

    /**
     * 排水槽状态（使一批积累的事件生效）
     */
    abstract static class DrainStatusRef<K, V> extends PadDrainStatus<K, V> {
        /** drainStatus状态字段的地址 */
        static final long DRAIN_STATUS_OFFSET = UnsafeAccess.objectFieldOffset(DrainStatusRef.class, "drainStatus");

        /** 不需要排水 */
        static final int IDLE = 0;
        /** 需要排水 */
        static final int REQUIRED = 1;
        /** 正在排水，清理完回到IDLE状态 */
        static final int PROCESSING_TO_IDLE = 2;
        /** 正在排水，清理完还要再次排水 */
        static final int PROCESSING_TO_REQUIRED = 3;

        /** 通过Unsafe类获得并发安全 */
        volatile int drainStatus = IDLE;

        /**
         * 是否应该排水了
         * @param delayable 如果是read buffer，是否可以先不清空
         */
        boolean shouldDrainBuffers(boolean delayable) {
            switch(this.drainStatus()) {
                case IDLE:
                    return !delayable;
                case REQUIRED:
                    return true;
                case PROCESSING_TO_IDLE:
                case PROCESSING_TO_REQUIRED:
                    return false;
                default:
                    throw new IllegalStateException();
            }
        }

        /**
         * 返回当前状态
         */
        int drainStatus() {
            return UnsafeAccess.UNSAFE.getInt(this, DRAIN_STATUS_OFFSET);
        }

        /**
         * 设置当前状态
         */
        void lazySetDrainStatus(int drainStatus) {
            UnsafeAccess.UNSAFE.putOrderedInt(this, DRAIN_STATUS_OFFSET, drainStatus);
        }

        /**
         * 设置当前状态（CAS原子方式）
         */
        boolean casDrainStatus(int expect, int update) {
            return UnsafeAccess.UNSAFE.compareAndSwapInt(this, DRAIN_STATUS_OFFSET, expect, update);
        }
    }
}
