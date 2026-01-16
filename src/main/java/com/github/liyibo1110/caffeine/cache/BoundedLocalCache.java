package com.github.liyibo1110.caffeine.cache;

import com.github.liyibo1110.caffeine.cache.stats.StatsCounter;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
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
    final ConcurrentHashMap<Object, Node<K, V>> data;
    final CacheLoader<K, V> cacheLoader;
    final PerformCleanupTask drainBuffersTask;
    final Consumer<Node<K, V>> accessPolicy;
    final Buffer<Node<K, V>> readBuffer;
    /**
     * 这个NodeFactory类以及几十个子类，都是在编译期利用gradle插件和对应的模板代码动态生成出来的
     * 目前国内网络环境没法正常对原始项目进行gradlew build操作，也就是无法预先生成出这些类，所以这个项目会有编译错误
     */
    final NodeFactory<K, V> nodeFactory;
    final ReentrantLock evictionLock;
    final CacheWriter<K, V> writer;
    final Weigher<K, V> weigher;
    final Executor executor;
    final boolean isAsync;

    /** 以下为集合视图 */
    transient Set<K> keySet;
    transient Collection<V> values;
    transient Set<Entry<K, V>> entrySet;

    protected BoundedLocalCache(Caffeine<K, V> builder, CacheLoader<K, V> cacheLoader, boolean isAsync) {
        this.isAsync = isAsync;
        this.cacheLoader = cacheLoader;
        this.executor = builder.getExecutor();
        this.evictionLock = new ReentrantLock();
        this.weigher = builder.getWeigher(isAsync);
        this.writer = builder.getCacheWriter(isAsync);
        this.drainBuffersTask = new PerformCleanupTask(this);
        this.nodeFactory = NodeFactory.newFactory(builder, isAsync);
        this.data = new ConcurrentHashMap<>(builder.getInitialCapacity());
        this.readBuffer = this.evicts() || this.collectKeys() || this.collectValues() || this.expiresAfterAccess()
                ? new BoundedBuffer<>() : Buffer.disabled();
        this.accessPolicy = (this.evicts() || this.expiresAfterAccess()) ? this::onAccess : e -> {};
        this.writeBuffer = new MpscGrowableArrayQueue<>(WRITE_BUFFER_MIN, WRITE_BUFFER_MAX);
        if(this.evicts())
            this.setMaximumSize(builder.getMaximum());
    }

    /* --------------- 通用部分 --------------- */

    /**
     * 如果是异步模式，返回value是否还在计算中
     */
    final boolean isComputingAsync(Node<?, ?> node) {
        return this.isAsync && !Async.isReady((CompletableFuture<?>)node.getValue());
    }

    protected AccessOrderDeque<Node<K, V>> accessOrderWindowDeque() {
        throw new UnsupportedOperationException();
    }

    protected AccessOrderDeque<Node<K, V>> accessOrderProbationDeque() {
        throw new UnsupportedOperationException();
    }

    protected AccessOrderDeque<Node<K, V>> accessOrderProtectedDeque() {
        throw new UnsupportedOperationException();
    }

    protected WriteOrderDeque<Node<K, V>> writeOrderDeque() {
        throw new UnsupportedOperationException();
    }

    public final Executor executor() {
        return this.executor;
    }

    protected boolean hasWriter() {
        return this.writer != CacheWriter.disabledWriter();
    }

    /* --------------- Stats Support相关 --------------- */

    @Override
    public boolean isRecordingStats() {
        return false;
    }

    @Override
    public StatsCounter statsCounter() {
        return StatsCounter.disabledStatsCounter();
    }

    @Override
    public Ticker statsTicker() {
        return Ticker.disabledTicker();
    }

    /* --------------- Removal Listener Support相关 --------------- */

    @Override
    public RemovalListener<K, V> removalListener() {
        return null;
    }

    @Override
    public boolean hasRemovalListener() {
        return false;
    }

    @Override
    public void notifyRemoval(K key, V value, RemovalCause cause) {
        Caffeine.requireState(this.hasRemovalListener(), "Notification should be guarded with a check");
        Runnable task = () -> {
            try{
                this.removalListener().onRemoval(key, value, cause);
            } catch (Throwable t) {
                this.logger.log(Level.WARNING, "Exception thrown by removal listener", t);
            }
        };
        try {
            this.executor.execute(task);
        } catch (Throwable t) {
            this.logger.log(Level.SEVERE, "Exception thrown when submitting removal listener", t);
            task.run(); // 同步执行
        }
    }

    /* --------------- Reference Support相关 --------------- */

    /**
     * key是否是weak引用且可以被GC自动回收
     */
    protected boolean collectKeys() {
        return false;
    }

    /**
     * value是否是weak引用且可以被GC自动回收
     */
    protected boolean collectValues() {
        return false;
    }

    protected ReferenceQueue<K> keyReferenceQueue() {
        return null;
    }

    protected ReferenceQueue<V> valueReferenceQueue() {
        return null;
    }

    /* --------------- Expiration Support相关 --------------- */

    protected Pacer pacer() {
        return null;
    }

    /**
     * 是否在可变时间阈值后entry过期
     */
    protected boolean expiresVariable() {
        return false;
    }

    /**
     * 是否在访问后一定时间entry过期
     */
    protected boolean expiresAfterAccess() {
        return false;
    }

    /**
     * entry被访问后，保留该entry的时间长度
     */
    protected long expiresAfterAccessNanos() {
        throw new UnsupportedOperationException();
    }

    protected void setExpiresAfterAccessNanos(long expireAfterAccessNanos) {
        throw new UnsupportedOperationException();
    }

    /**
     * 是否在写入后一定时间entry过期
     */
    protected boolean expiresAfterWrite() {
        return false;
    }

    /**
     * entry被写入后，保留该entry的时间长度
     */
    protected long expiresAfterWriteNanos() {
        throw new UnsupportedOperationException();
    }

    protected void setExpiresAfterWriteNanos(long expireAfterWriteNanos) {
        throw new UnsupportedOperationException();
    }

    /**
     * 是否在写入后一定时间entry能自动刷新
     */
    protected boolean refreshAfterWrite() {
        return false;
    }

    /**
     * entry被写入后，等待下次刷新的时间长度
     */
    protected long refreshAfterWriteNanos() {
        throw new UnsupportedOperationException();
    }

    protected void setRefreshAfterWriteNanos(long refreshAfterWriteNanos) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasWriteTime() {
        return this.expiresAfterWrite() || this.refreshAfterWrite();
    }

    protected Expiry<K, V> expiry() {
        return null;
    }

    @Override
    public Ticker expirationTicker() {
        return Ticker.disabledTicker();
    }

    protected TimerWheel<K, V> timerWheel() {
        throw new UnsupportedOperationException();
    }

    /* --------------- Eviction Support相关 --------------- */

    /**
     * 是否带有驱逐功能（达到最大大小或权重阈值）
     */
    protected boolean evicts() {
        return false;
    }

    /**
     * 是否entry带有权重功能
     */
    protected boolean isWeighted() {
        return this.weigher != Weigher.singleonWeigher();
    }

    protected FrequencySketch<K> frequencySketch() {
        throw new UnsupportedOperationException();
    }

    /**
     * 是否可以跳过对entry的访问时的通知淘汰策略
     */
    protected boolean fastpath() {
        return false;
    }

    /**
     * 返回最大weight值
     */
    protected long maximum() {
        throw new UnsupportedOperationException();
    }

    /**
     * 返回window space的最大weight值
     */
    protected long windowMaximum() {
        throw new UnsupportedOperationException();
    }

    /**
     * 返回main protected的最大weight值
     */
    protected long mainProtectedMaximum() {
        throw new UnsupportedOperationException();
    }

    protected void setMaximum(long maximum) {
        throw new UnsupportedOperationException();
    }

    protected void setWindowMaximum(long maximum) {
        throw new UnsupportedOperationException();
    }

    protected void setMainProtectedMaximum(long maximum) {
        throw new UnsupportedOperationException();
    }

    /**
     * 返回cache中value的总weight值（可能为负值）
     */
    protected long weightedSize() {
        throw new UnsupportedOperationException();
    }

    /**
     * 返回cache中value的总window space weight值（可能为负值）
     */
    protected long windowWeightedSize() {
        throw new UnsupportedOperationException();
    }

    /**
     * 返回cache中value的总main protected weight值（可能为负值）
     */
    protected long mainProtectedWeightedSize() {
        throw new UnsupportedOperationException();
    }

    protected void setWeightedSize(long weightedSize) {
        throw new UnsupportedOperationException();
    }

    protected void setWindowWeightedSize(long weightedSize) {
        throw new UnsupportedOperationException();
    }

    protected void setMainProtectedWeightedSize(long weightedSize) {
        throw new UnsupportedOperationException();
    }

    protected int hitsInSample() {
        throw new UnsupportedOperationException();
    }

    protected int missesInSample() {
        throw new UnsupportedOperationException();
    }

    protected int sampleCount() {
        throw new UnsupportedOperationException();
    }

    protected double stepSize() {
        throw new UnsupportedOperationException();
    }

    protected double previousSampleHitRate() {
        throw new UnsupportedOperationException();
    }

    protected long adjustment() {
        throw new UnsupportedOperationException();
    }

    protected void setHitsInSample(int hitCount) {
        throw new UnsupportedOperationException();
    }

    protected void setMissesInSample(int missCount) {
        throw new UnsupportedOperationException();
    }

    protected void setSampleCount(int sampleCount) {
        throw new UnsupportedOperationException();
    }

    protected void setStepSize(double stepSize) {
        throw new UnsupportedOperationException();
    }

    protected void setPreviousSampleHitRate(double hitRate) {
        throw new UnsupportedOperationException();
    }

    protected void setAdjustment(long amount) {
        throw new UnsupportedOperationException();
    }

    /**
     * 设置cache的最大加权大小
     * 调用方可能需要主动移除entry，直到cache缩减到适当的大小
     */
    void setMaximumSize(long maximum) {
        Caffeine.requireArgument(maximum >= 0, "maximum must not be negative");
        if(maximum == this.maximum())
            return;

        long max = Math.min(maximum, MAXIMUM_CAPACITY);
        long window = max - (long)(PERCENT_MAIN * max);
        long mainProtected = (long)(PERCENT_MAIN_PROTECTED * (max - window));

        this.setMaximum(max);
        this.setWindowMaximum(window);
        this.setMainProtectedMaximum(mainProtected);

        this.setHitsInSample(0);
        this.setMissesInSample(0);
        this.setStepSize(-HILL_CLIMBER_STEP_PERCENT * max);

        // 是否要初始化FrequencySketch组件
        if(this.frequencySketch() != null && !this.isWeighted() && this.weightedSize() >= (max >>> 1))
            this.frequencySketch().ensureCapacity(max);
    }

    /**
     * 如果cache大小超出了maximum，则触发清理
     */
    void evictEntries() {
        if(!this.evicts())
            return;
        int candidates = this.evictFromWindow();
        this.evictFromMain(candidates);
    }

    int evictFromWindow() {
        return 0;
    }

    void evictFromMain(int candidates) {

    }

    /**
     * 尝试根据给定原因来移除entry
     * 如果entry已更新且不再符合移除条件，则可能忽略移除
     */
    boolean evictEntry(Node<K, V> node, RemovalCause cause, long now) {
        return false;
    }

    /**
     * 尝试调度一个异步任务来清理buffer
     */
    void scheduleDrainBuffers() {

    }

    @Override
    public void cleanUp() {
        try {
            this.performCleanUp(null);
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Exception thrown when performing the maintenance task", e);
        }
    }

    /**
     * 执行维护工作，并阻塞直到获取锁
     * 内部任何抛出的异常，都会传播给调用者
     */
    void performCleanUp(Runnable task) {
        this.evictionLock.lock();
        try {
            this.maintenance(task);
        } finally {
            this.evictionLock.unlock();
        }
        if(drainStatus() == REQUIRED && this.executor == ForkJoinPool.commonPool())
            this.scheduleDrainBuffers();
    }

    void maintenance(Runnable task) {

    }

    /**
     * 尝试更新Node的内部类型
     */
    void onAccess(Node<K, V> node) {

    }

    static final class PerformCleanupTask extends ForkJoinTask<Void> implements Runnable {
        private static final long serialVersionUID = 1L;

        final WeakReference<BoundedLocalCache<?, ?>> reference;

        PerformCleanupTask(BoundedLocalCache<?, ?> cache) {
            reference = new WeakReference<>(cache);
        }

        @Override
        protected boolean exec() {
            try {
                this.run();
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Exception thrown when performing the maintenance task", t);
            }
            return false;
        }

        @Override
        public void run() {
            BoundedLocalCache<?, ?> cache = this.reference.get();
            if(cache != null)
                cache.performCleanUp(null);
        }

        @Override
        public Void getRawResult() {
            return null;
        }

        @Override
        protected void setRawResult(Void value) {}

        @Override
        public void complete(Void value) {}

        @Override
        public void completeExceptionally(Throwable ex) {}

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }
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
