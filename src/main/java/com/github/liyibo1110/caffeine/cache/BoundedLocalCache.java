package com.github.liyibo1110.caffeine.cache;

import com.github.liyibo1110.caffeine.cache.stats.StatsCounter;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.Objects.requireNonNull;

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

    /**
     * 尝试清理window区，变成main区的probation
     */
    int evictFromWindow() {
        int candidates = 0;
        // 根据LRU算法定义，peek应该取出的是最老的，即最久没有被访问的（注意这些双端队列，写入都是写入last，然后从first取）
        Node<K, V> node = this.accessOrderWindowDeque().peek();
        while(this.windowWeightedSize() > this.windowMaximum()) {
            if(node == null)
                break;

            Node<K, V> next = node.getNextInAccessOrder();
            if(node.getPolicyWeight() != 0) {
                node.makeMainProbation();   // 直接降低到main区的probation
                accessOrderWindowDeque().remove(node);  // 从window队列中移除
                accessOrderProbationDeque().add(node);  // 写入probation队列
                candidates++;
                this.setWindowWeightedSize(this.windowWeightedSize() - node.getPolicyWeight());
            }
            node = next;
        }
        return candidates;
    }

    /**
     * 尝试清理main区
     */
    void evictFromMain(int candidates) {
        int victimQueue = Node.PROBATION;
        Node<K, V> victim = this.accessOrderProbationDeque().peekFirst(); // probation队列里最老的，用的还是LRU，大概率会被清理
        Node<K, V> candidate = this.accessOrderProbationDeque().peekLast(); // 刚从window转到probation的，清理优先级低于probation
        while(this.weightedSize() > this.maximum()) {
            if(candidates == 0) // 优先使用window显式推下来的，如果处理完了空间依然不够，还是优先处理window里面的
                candidate = this.accessOrderWindowDeque().peekLast();   // 注意这里的candidate指的是window里面最热门的，它想通过竞争进入protected


            if(candidate == null && victim == null) {   // 如果window和probation都没有entry了，只能尝试从protected清理
                if(victimQueue == Node.PROBATION) {
                    victim = this.accessOrderProtectedDeque().peekFirst();
                    victimQueue = Node.PROTECTED;
                    continue;
                }else if(victimQueue == Node.PROTECTED) {   // 如果连protected也没了，则只能再从window尝试清理
                    victim = this.accessOrderWindowDeque().peekFirst();
                    victimQueue = Node.WINDOW;
                    continue;
                }
                break;  // 到这里说明3个队列都没有东西了，整个清理结束
            }

            // 跳过weight为0的（僵尸node）
            if(victim != null && victim.getPolicyWeight() == 0) {
                victim = victim.getNextInAccessOrder();
                continue;
            }else if(candidate != null && candidate.getPolicyWeight() == 0) {
                candidate = candidates > 0 ? candidate.getPreviousInAccessOrder() : candidate.getNextInAccessOrder();
                candidates--;
                continue;
            }

            // 说明只有window有东西，但是weight也超了，只能直接移除candidate，这一步也确保了整个evict过程不会进入死循环
            if(victim == null) {
                Node<K, V> previous = candidate.getPreviousInAccessOrder();
                Node<K, V> evict = candidate;
                candidate = previous;
                candidates--;
                this.evictEntry(evict, RemovalCause.SIZE, 0L);
                continue;
            }else if(candidate == null) {   // 反之，window没有东西了，只能直接移除probation
                Node<K, V> evict = victim;
                victim = victim.getNextInAccessOrder();
                evictEntry(evict, RemovalCause.SIZE, 0L);
                continue;
            }

            // 竞争继续，如果发现key变成null了，说明是weak引用并且已被GC回收了，要直接清理
            K victimKey = victim.getKey();
            K candidateKey = candidate.getKey();
            if(victimKey == null) {
                Node<K, V> evict = victim;
                victim = victim.getNextInAccessOrder();
                this.evictEntry(evict, RemovalCause.SIZE, 0L);
                continue;
            }else if(candidateKey == null) {
                Node<K, V> evict = candidate;
                candidate = candidates > 0 ? candidate.getPreviousInAccessOrder() : candidate.getNextInAccessOrder();
                candidates--;
                this.evictEntry(evict, RemovalCause.SIZE, 0L);
                continue;
            }

            // 竞争继续，如果candidate本身单个weight就已经吵了，直接清理
            if(candidate.getPolicyWeight() > maximum()) {
                Node<K, V> evict = candidate;
                candidate = candidates > 0 ? candidate.getPreviousInAccessOrder() : candidate.getNextInAccessOrder();
                candidates--;
                this.evictEntry(evict, RemovalCause.SIZE, 0L);
                continue;
            }

            // 最终竞争，一定要从candidate和victim选出一个来清理了
            candidates--;
            if(this.admit(candidateKey, victimKey)) {
                // candidate赢了
                Node<K, V> evict = victim;
                victim = victim.getNextInAccessOrder();
                this.evictEntry(evict, RemovalCause.SIZE, 0L);
                candidate = candidate.getPreviousInAccessOrder();   // 在probation中留下了，不再参与比赛
            }else {
                // victim赢了
                Node<K, V> evict = candidate;
                candidate = candidates > 0 ? candidate.getPreviousInAccessOrder() : candidate.getNextInAccessOrder();
                this.evictEntry(evict, RemovalCause.SIZE, 0L);
            }
        }
    }

    /**
     * 竞争candidate和victim，看谁应该留下，谁被清理
     * 比较方式是利用FrequencySketch组件，看谁更流行
     * @return 如果candidate赢了，要被留下，则返回true
     */
    boolean admit(K candidateKey, K victimKey) {
        int candidateFreq = this.frequencySketch().frequency(candidateKey);
        int victimFreq = this.frequencySketch().frequency(victimKey);
        if(candidateFreq > victimFreq)
            return true;
        else if(candidateFreq <= 5)  // 注意candidated的流行度小于等于5才直接算失败，否则随机失败一个
            return false;

        int random = ThreadLocalRandom.current().nextInt();
        return (random & 127) == 0;
    }

    /**
     * 触发基于时间过期的清理
     */
    void expireEntries() {
        long now = this.expirationTicker().read();
        this.expireAfterAccessEntries(now);
        this.expireAfterWriteEntries(now);
        this.expireVariableEntries(now);

        // 注意是在这里预约了下一次maintenance的触发时间
        Pacer pacer = this.pacer();
        if(pacer != null) {
            long delay = this.getExpirationDelay(now);
            if(delay == Long.MAX_VALUE)
                pacer.cancel();
            else
                pacer.schedule(this.executor, this.drainBuffersTask, now, delay);
        }
    }

    void expireAfterAccessEntries(long now) {
        if(!this.expiresAfterAccess())
            return;

        this.expireAfterAccessEntries(this.accessOrderWindowDeque(), now);
        if(this.evicts()) {
            this.expireAfterAccessEntries(this.accessOrderProbationDeque(), now);
            this.expireAfterAccessEntries(this.accessOrderProtectedDeque(), now);
        }
    }

    void expireAfterAccessEntries(AccessOrderDeque<Node<K, V>> deque, long now) {
        long duration = this.expiresAfterAccessNanos(); // 即写死的过期时长，比如1小时
        // 会遍历整个队列
        while(true) {
            Node<K, V> node = deque.peekFirst();
            if(node == null || (now - node.getAccessTime()) < duration || !this.evictEntry(node, RemovalCause.EXPIRED, now))
                return;
        }
    }

    void expireAfterWriteEntries(long now) {
        if(!this.expiresAfterWrite())
            return;
        long duration = this.expiresAfterWriteNanos(); // 即写死的过期时长，比如1小时
        while(true) {
            Node<K, V> node = this.writeOrderDeque().peekFirst();
            if(node == null || (now - node.getWriteTime()) < duration || !this.evictEntry(node, RemovalCause.EXPIRED, now))
                return;
        }
    }

    void expireVariableEntries(long now) {
        if(this.expiresVariable())
            this.timerWheel().advance(now);
    }

    /**
     * 计算下一个Node最快过期的时间（找所有deque）
     */
    private long getExpirationDelay(long now) {
        long delay = Long.MAX_VALUE;
        if(this.expiresAfterAccess()) {
            Node<K, V> node = this.accessOrderWindowDeque().peekFirst();
            if(node != null)
                delay = Math.min(delay, this.expiresAfterAccessNanos() - (now - node.getAccessTime()));
            if(this.evicts()) {
                node = this.accessOrderProbationDeque().peekFirst();
                if(node != null)
                    delay = Math.min(delay, this.expiresAfterAccessNanos() - (now - node.getAccessTime()));
                node = this.accessOrderProtectedDeque().peekFirst();
                if(node != null)
                    delay = Math.min(delay, this.expiresAfterAccessNanos() - (now - node.getAccessTime()));
            }
        }

        if(this.expiresAfterWrite()) {
            Node<K, V> node = this.writeOrderDeque().peekFirst();
            if(node != null)
                delay = Math.min(delay, this.expiresAfterWriteNanos() - (now - node.getWriteTime()));
        }

        if(this.expiresVariable()) {
            delay = Math.min(delay, this.timerWheel().getExpirationDelay());
        }
        return delay;
    }

    /**
     * 返回node是否过期
     */
    boolean hasExpired(Node<K, V> node, long now) {
        if(this.isComputingAsync(node))
            return false;
        return (this.expiresAfterAccess() && (now - node.getAccessTime() >= this.expiresAfterAccessNanos()))
            | (this.expiresAfterWrite() && (now - node.getWriteTime() >= this.expiresAfterWriteNanos()))
            | (this.expiresVariable() && (now - node.getVariableTime() >= 0));
    }


    /**
     * 尝试根据给定原因来移除entry
     * 如果entry已更新且不再符合移除条件，则可能忽略移除
     * @param now 当前时间，只在expring功能被使用（eviction功能则会被传入0）
     */
    boolean evictEntry(Node<K, V> node, RemovalCause cause, long now) {
        K key = node.getKey();
        V[] value = (V[])new Object[1];
        boolean[] removed = new boolean[1];
        boolean[] resurrect = new boolean[1];   // 是否又活了
        RemovalCause[] actualCause = new RemovalCause[1];

        // 再次提醒注意data里面的key，是个Reference，value存的才是Node<K, V>，基础结构别搞错了
        this.data.computeIfPresent(node.getKeyReference(), (k, n) -> {
            if(n != node)
                return n;
            synchronized(n) {
                value[0] = n.getValue();

                if(key == null || value[0] == null) {
                    actualCause[0] = RemovalCause.COLLECTED;
                }else if(cause == RemovalCause.COLLECTED) {
                    resurrect[0] = true;
                    return n;
                }else {
                    actualCause[0] = cause;
                }

                if(actualCause[0] == RemovalCause.EXPIRED) {
                    boolean expired = false;
                    if(this.expiresAfterAccess())
                        expired |= (now - n.getAccessTime()) >= this.expiresAfterAccessNanos();
                    if(this.expiresAfterWrite())
                        expired |= (now - n.getWriteTime()) >= this.expiresAfterWriteNanos();
                    if(this.expiresVariable())
                        expired |= (n.getVariableTime() <= now);
                    if(!expired) {
                        resurrect[0] = true;
                        return n;
                    }
                }else if(actualCause[0] == RemovalCause.SIZE) {
                    int weight = node.getWeight();
                    if(weight == 0) {
                        resurrect[0] = true;
                        return n;
                    }
                }
                this.writer.delete(key, value[0], actualCause[0]);
                // 注意makeDead只是改变了node的状态为dead，并且减少了cache的各个weight，队列之类的还都没有进行清理
                this.makeDead(n);
            }
            removed[0] = true;
            return null;
        });

        if(resurrect[0])
            return false;

        // 将被清理的node移除出各个队列
        if(node.inWindow() && (this.evicts() || this.expiresAfterAccess())) {
            this.accessOrderWindowDeque().remove(node);
        }else if(this.evicts()) {
            if(node.inMainProbation())
                this.accessOrderProbationDeque().remove(node);
            else
                this.accessOrderProtectedDeque().remove(node);
        }
        if(this.expiresAfterWrite())
            this.writeOrderDeque().remove(node);
        else if(this.expiresVariable())
            this.timerWheel().deschedule(node);

        if(removed[0]) {
            this.statsCounter().recordEviction(node.getWeight(), actualCause[0]);
            if(this.hasRemovalListener())
                this.notifyRemoval(key, value[0], actualCause[0]);
        }else {
            this.makeDead(node);
        }
        return true;
    }

    /**
     * 根据最近一段时间的命中率变化，动态调整window（偏重recency，即最热）和main（偏重frequency，即访问频率最高）的配额比例
     */
    void climb() {
        if(!this.evicts())
            return;

        this.determineAdjustment();
        this.demoteFromMainProtected();
        long amount = this.adjustment();
        if(amount == 0)
            return;
        else if(amount > 0)
            increaseWindow();
        else
            decreaseWindow();
    }

    /**
     * 计算并设置要调整的配额比例
     */
    void determineAdjustment() {
        if(this.frequencySketch().isNotInitialized()) {
            this.setPreviousSampleHitRate(0.0);
            this.setMissesInSample(0);
            this.setHitsInSample(0);
            return;
        }

        int requestCount = this.hitsInSample() + this.missesInSample();
        if(requestCount < this.frequencySketch().sampleSize)    // 样本不够
            return;

        double hitRate = (double)this.hitsInSample() / requestCount;
        // 命中率和上一轮的变化，为正说明这轮调整的好（要继续正向调节），为负说明这轮调整的差（要反向调节了）
        double hitRateChange = hitRate - this.previousSampleHitRate();
        double amount = hitRateChange >= 0 ? this.stepSize() : -this.stepSize();
        // 更新步长，标准的爬山算法（hill-climbing）
        double nextStepSize = Math.abs(hitRateChange) >= HILL_CLIMBER_RESTART_THRESHOLD
                ? HILL_CLIMBER_STEP_PERCENT * this.maximum() * amount >= 0 ? 1 : -1 // 变化很大（说明需要优化）就用最大值的某个百分比
                : HILL_CLIMBER_STEP_DECAY_RATE * amount;    // 变化不大（说明接近最优）就只衰减一点
        this.setPreviousSampleHitRate(hitRate);
        this.setAdjustment((long)amount);
        this.setStepSize(nextStepSize);
        this.setMissesInSample(0);
        this.setHitsInSample(0);
    }

    /**
     * 增加window容量（amount > 0）
     * 含义：最近调大window后，命中率上升了，说明“新近访问”更重要
     */
    void increaseWindow() {
        if(this.mainProtectedMaximum() == 0)
            return;

        long quota = Math.min(this.adjustment(), this.mainProtectedMaximum());
        this.setMainProtectedMaximum(this.mainProtectedMaximum() - quota);
        this.setWindowMaximum(this.windowMaximum() + quota);
        this.demoteFromMainProtected();

        // 开始从probation或者protected往window里面迁移一些Node
        for(int i = 0; i < QUEUE_TRANSFER_THRESHOLD; i++) {
            Node<K, V> candidate = this.accessOrderProbationDeque().peek();
            boolean probation = true;
            if(candidate == null || quota < candidate.getPolicyWeight()) {
                candidate = this.accessOrderProtectedDeque().peek();
                probation = false;
            }
            if(candidate == null)
                break;
            int weight = candidate.getPolicyWeight();
            if(quota < weight)
                break;
            quota -= weight;
            if(probation) {
                this.accessOrderProbationDeque().remove(candidate);
            }else {
                this.setMainProtectedWeightedSize(this.mainProtectedWeightedSize() - weight);
                this.accessOrderProtectedDeque().remove(candidate);
            }
            this.setWindowWeightedSize(this.windowWeightedSize() + weight);
            this.accessOrderWindowDeque().add(candidate);
            candidate.makeWindow();
        }

        this.setMainProtectedMaximum(this.mainProtectedMaximum() + quota);
        this.setWindowMaximum(this.windowMaximum() - quota);
        this.setAdjustment(quota);
    }

    /**
     * 减少window容量（amount < 0）
     * 含义：最近调大window后，命中率下降了，说明“长期热点”更重要
     */
    void decreaseWindow() {
        if(this.windowMaximum() <= 1)
            return;

        long quota = Math.min(-this.adjustment(), Math.max(0, this.windowMaximum() - 1));
        this.setMainProtectedMaximum(this.mainProtectedMaximum() + quota);
        this.setWindowMaximum(this.windowMaximum() - quota);

        for(int i = 0; i < QUEUE_TRANSFER_THRESHOLD; i++) {
            Node<K, V> candidate = this.accessOrderWindowDeque().peek();
            if(candidate == null)
                break;

            int weight = candidate.getPolicyWeight();
            if(quota < weight)
                break;

            quota -= weight;
            this.setWindowWeightedSize(this.windowWeightedSize() - weight);
            this.accessOrderWindowDeque().remove(candidate);
            this.accessOrderProbationDeque().add(candidate);
            candidate.makeMainProbation();
        }

        this.setMainProtectedMaximum(this.mainProtectedMaximum() - quota);
        this.setWindowMaximum(this.windowMaximum() + quota);
        this.setAdjustment(-quota);
    }

    /**
     * 如果protected区超出配额了，则要调整回去（protected -> probation）
     */
    void demoteFromMainProtected() {
        long mainProtectedMaximum = this.mainProtectedMaximum();
        long mainProtectedWeightedSize = this.mainProtectedWeightedSize();
        if(mainProtectedWeightedSize <= mainProtectedMaximum)
            return;

        for(int i = 0; i < QUEUE_TRANSFER_THRESHOLD; i++) {
            // 容量不超出了就可以停止了
            if(mainProtectedWeightedSize <= mainProtectedMaximum)
                break;

            // 从protected队列转到probation队列
            Node<K, V> demoted = this.accessOrderProtectedDeque().poll();
            if(demoted == null)
                break;
            demoted.makeMainProbation();
            this.accessOrderProbationDeque().add(demoted);
            mainProtectedWeightedSize -= demoted.getPolicyWeight();
        }
        this.setMainProtectedWeightedSize(mainProtectedWeightedSize);
    }

    /**
     * 对cache进行读取操作后，要做的事情
     */
    void afterRead(Node<K, V> node, long now, boolean recordHit) {
        if(recordHit)
            this.statsCounter().recordHits(1);
        boolean delayable = this.skipReadBuffer() || this.readBuffer.offer(node) != Buffer.FULL;
        if(this.shouldDrainBuffers(delayable))
            this.scheduleDrainBuffers();
        // 尝试提前重新加载
        this.refreshIfNeeded(node, now);
    }

    /**
     * 是否跳过readBuffer处理
     */
    boolean skipReadBuffer() {
        return this.fastpath() && this.frequencySketch().isNotInitialized();
    }

    /**
     * 如果符合条件，则异步刷新Node
     */
    void refreshIfNeeded(Node<K, V> node, long now) {
        if(!this.refreshAfterWrite())
            return;
        K key;
        V oldValue;
        long oldWriteTime = node.getWriteTime();
        long refreshWriteTime = now + Async.ASYNC_EXPIRY;
        if((now - oldWriteTime) > this.refreshAfterWriteNanos()
            && ((key = node.getKey()) != null && ((oldValue = node.getValue()) != null))
            && node.casWriteTime(oldWriteTime, refreshWriteTime)) { // 用一个极大值来占坑，保证其它线程不会再进入if里面
            try {
                CompletableFuture<V> refreshFuture;
                long startTime = this.statsTicker().read();
                if(this.isAsync) {
                    CompletableFuture<V> future = (CompletableFuture<V>)oldValue;
                    if(Async.isReady(future)) {
                        CompletableFuture<V> refresh = future.thenCompose(value ->
                                this.cacheLoader.asyncReload(key, value, this.executor));
                        refreshFuture = refresh;
                    }else {
                        // 如果本身oldValue就没算好，把writeTime写回原样
                        node.casWriteTime(refreshWriteTime, oldWriteTime);
                        return;
                    }
                }else {
                    CompletableFuture<V> refresh = this.cacheLoader.asyncReload(key, oldValue, this.executor);
                    refreshFuture = refresh;
                }

                refreshFuture.whenComplete((newValue, error) -> {
                    long loadTime = this.statsTicker().read() - startTime;
                    if(error != null) { // 出错了就还原回去完事
                        if(!(error instanceof CancellationException) && !(error instanceof TimeoutException))
                            logger.log(Level.WARNING, "Exception thrown during refresh", error);
                        node.casWriteTime(refreshWriteTime, oldWriteTime);
                        statsCounter().recordLoadFailure(loadTime);
                        return;
                    }

                    V value = (this.isAsync && newValue != null) ? (V)refreshFuture : newValue;
                    // 成功load出新value了，开始替换
                    boolean[] discard = new boolean[1];
                    this.compute(key, (k, currentValue) -> {
                        if(currentValue == null)
                            return value;
                        else if(currentValue == oldValue && node.getWriteTime() == refreshWriteTime)   // 没有被其它线程改了
                            return value;
                        // 到这里说明不满足上面的条件，就算失败，不做更新
                        discard[0] = true;
                        return currentValue;
                    }, false, false, true);

                    if(discard[0] && this.hasRemovalListener())
                        this.notifyRemoval(key, value, RemovalCause.REPLACED);
                    if(newValue == null)
                        this.statsCounter().recordLoadFailure(loadTime);
                    else
                        this.statsCounter().recordLoadSuccess(loadTime);
                });
            } catch (Throwable t) {
                node.casWriteTime(refreshWriteTime, oldWriteTime);
                logger.log(Level.SEVERE, "Exception thrown when submitting refresh task", t);
            }
        }
    }

    /**
     * 返回node创建后的过期时间（即构建cache时自定义了Expiry实例，用这个方法来返回到期时间）
     */
    long expireAfterCreate(K key, V value, Expiry<K, V> expiry, long now) {
        if(this.expiresVariable() && key != null && value != null) {
            long duration = expiry.expireAfterCreate(key, value, now);  // 即用户自己实现的方法
            return this.isAsync ? now + duration : now + Math.min(duration, MAXIMUM_EXPIRY);
        }
        return 0L;
    }

    /**
     * 返回node被更新后的过期时间（即构建cache时自定义了Expiry实例，用这个方法来返回到期时间）
     */
    long expireAfterUpdate(Node<K, V> node, K key, V value, Expiry<K, V> expiry, long now) {
        if(this.expiresVariable() && key != null && value != null) {
            long currentDuration = Math.max(1, node.getVariableTime() - now);
            long duration = expiry.expireAfterUpdate(key, value, now, currentDuration);
            return this.isAsync ? now + duration : now + Math.min(duration, MAXIMUM_EXPIRY);
        }
        return 0L;
    }

    /**
     * 返回node被访问后的过期时间（即构建cache时自定义了Expiry实例，用这个方法来返回到期时间）
     */
    long expireAfterRead(Node<K, V> node, K key, V value, Expiry<K, V> expiry, long now) {
        if(this.expiresVariable() && key != null && value != null) {
            long currentDuration = Math.max(1, node.getVariableTime() - now);
            long duration = expiry.expireAfterRead(key, value, now, currentDuration);
            return this.isAsync ? now + duration : now + Math.min(duration, MAXIMUM_EXPIRY);
        }
        return 0L;
    }

    /**
     * 在访问后尝试更新node的过期时间（构建cache时自定义了Expiry实例才会有用）
     */
    void tryExpireAfterRead(Node<K, V> node, K key, V value, Expiry<K, V> expiry, long now) {
        if(!this.expiresVariable() || key == null || value == null)
            return;

        long variableTime = node.getVariableTime();
        // 算出的是相对过期长度，因为variableTime是一个绝对时间点，不是时间段
        long currentDuration = Math.max(1, variableTime - now);
        if(this.isAsync && currentDuration > MAXIMUM_EXPIRY)
            return;

        // 调用用户自定义的实现，计算出新的时间段
        long duration = expiry.expireAfterRead(key, value, now, currentDuration);
        if(duration != currentDuration) {   // 不一样则说明要更新
            long expirationTime = this.isAsync ? now + duration : now + Math.min(duration, MAXIMUM_EXPIRY);
            node.casVariableTime(variableTime, expirationTime);
        }
    }

    void setVariableTime(Node<K, V> node, long expirationTime) {
        if(this.expiresVariable())
            node.setVariableTime(expirationTime);
    }

    void setWriteTime(Node<K, V> node, long now) {
        if(this.expiresAfterWrite() || this.refreshAfterWrite())
            node.setWriteTime(now);
    }

    void setAccessTime(Node<K, V> node, long now) {
        if(this.expiresAfterAccess())
            node.setAccessTime(now);
    }

    /**
     * 对cache进行写入操作后，要做的事情
     */
    void afterWrite(Runnable task) {
        for(int i = 0; i < WRITE_BUFFER_RETRIES; i++) {
            // 先优先尝试将task传给writeBuffer，让它来执行，成功说明消费者可以正常消费
            if(this.writeBuffer.offer(task)) {
                this.scheduleAfterWrite();
                return;
            }
            this.scheduleDrainBuffers();    // 这一步相当于催促消费者尽快消费
        }

        // 尝试了100次，也没有将task写入writeBuffer，只能自己来了
        try {
            this.performCleanUp(task);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Exception thrown when performing the maintenance task", e);
        }
    }

    void scheduleAfterWrite() {
        while(true) {
            switch(this.drainStatus()) {
                case IDLE:  // 没有在运行maintenance，立刻开始调度
                    this.casDrainStatus(IDLE, REQUIRED);
                    this.scheduleDrainBuffers();
                    return;
                case REQUIRED:
                    this.scheduleDrainBuffers();
                    return;
                case PROCESSING_TO_IDLE:    // maintenance已有调度，完事要再运行一次
                    if(this.casDrainStatus(PROCESSING_TO_IDLE, PROCESSING_TO_REQUIRED))
                        return;
                    continue;
                case PROCESSING_TO_REQUIRED:
                    return;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    /**
     * 尝试调度一个异步任务来清理buffer，即启动drainBuffersTask
     */
    void scheduleDrainBuffers() {
        // 正在排就直接结束
        if(this.drainStatus() >= PROCESSING_TO_IDLE)
            return;
        if(this.evictionLock.tryLock()) {
            try {
                int drainStatus = this.drainStatus();
                if(drainStatus >= PROCESSING_TO_IDLE)
                    return;
                this.lazySetDrainStatus(PROCESSING_TO_IDLE);
                this.executor.execute(this.drainBuffersTask);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Exception thrown when submitting maintenance task", t);
                maintenance(null);
            } finally {
                this.evictionLock.unlock();
            }
        }
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
        this.lazySetDrainStatus(PROCESSING_TO_IDLE);
        try {
            this.drainReadBuffer();
            this.drainWriteBuffer();
            if(task != null)
                task.run();
            this.drainKeyReferences();
            this.drainValueReferences();
            this.expireEntries();
            this.evictEntries();
            this.climb();
        } finally {
            if(this.drainStatus() != PROCESSING_TO_IDLE || !this.casDrainStatus(PROCESSING_TO_IDLE, IDLE))
                this.lazySetDrainStatus(REQUIRED);
        }
    }

    /**
     * 清理weak key引用队列
     */
    void drainKeyReferences() {
        if(!this.collectKeys())
            return;
        Reference<? extends K> keyRef;
        while((keyRef = this.keyReferenceQueue().poll()) != null) {
            Node<K, V> node = this.data.get(keyRef);
            if(node != null)
                this.evictEntry(node, RemovalCause.COLLECTED, 0L);
        }
    }

    void drainValueReferences() {
        if(!this.collectValues())
            return;

        Reference<? extends V> valueRef;
        while((valueRef = this.valueReferenceQueue().poll()) != null) {
            @SuppressWarnings("unchecked")
            References.InternalReference<V> ref = (References.InternalReference<V>)valueRef;
            Node<K, V> node = this.data.get(ref.getKeyReference());
            if(node != null && valueRef == node.getValueReference())
                this.evictEntry(node, RemovalCause.COLLECTED, 0L);
        }
    }

    /**
     * 清理read buffer
     */
    void drainReadBuffer() {
        if(!this.skipReadBuffer())
            this.readBuffer.drainTo(this.accessPolicy);
    }

    /**
     * node在被访问之后的后处理逻辑
     */
    void onAccess(Node<K, V> node) {
        if(this.evicts()) { // 完整的cache策略路径
            K key = node.getKey();
            if(key == null)
                return;
            this.frequencySketch().increment(key);  // read事件说明命中了，需要统计
            if(node.inWindow())
                reorder(this.accessOrderWindowDeque(), node);
            else if(node.inMainProbation())
                this.reorderProbation(node);
            else
                reorder(this.accessOrderProtectedDeque(), node);
            this.setHitsInSample(this.hitsInSample() + 1);
        }else if(this.expiresAfterAccess()) {   // 没有清理策略，只检查有没有过期策略
            reorder(this.accessOrderWindowDeque(), node);
        }
        if(this.expiresVariable())
            this.timerWheel().reschedule(node);
    }

    /**
     * node被命中，尝试probation -> protected
     */
    void reorderProbation(Node<K, V> node) {
        if(!this.accessOrderProbationDeque().contains(node))    // 不在probation queue
            return;
        else if(node.getPolicyWeight() > this.mainProtectedMaximum())
            return;
        // probation -> protected
        this.setMainProtectedWeightedSize(this.mainProtectedWeightedSize() + node.getPolicyWeight());
        this.accessOrderProbationDeque().remove(node);
        this.accessOrderProtectedDeque().add(node);
        node.makeMainProtected();
    }

    /**
     * 将deque里面指定的node移动到队列尾（last）
     */
    static <K, V> void reorder(LinkedDeque<Node<K, V>> deque, Node<K, V> node) {
        if(deque.contains(node))
            deque.moveToBack(node);
    }

    void drainWriteBuffer() {
        // 尽力执行writeBuffer里面的Runnable（即真正的写后逻辑），每轮maintenance只会处理一部分
        for(int i = 0; i <= WRITE_BUFFER_MAX ; i++) {
            // writeBuffer里面的Runnable，和readBuffer不同，属于强一致，可以不一次性完成，但要保证“最终完成”，
            Runnable task = this.writeBuffer.poll();
            if(task == null)    // 都处理完了则返回
                return;
            task.run();
        }
        // 到这里说明writeBuffer仍然有Runnable尚未执行，需要标记drain状态为“还要再次清理”
        this.lazySetDrainStatus(PROCESSING_TO_REQUIRED);
    }

    /**
     * 将Node转换成dead状态，并减少weighted的值
     */
    void makeDead(Node<K, V> node) {
        synchronized(node) {
            if(node.isDead())
                return;
            if(this.evicts()) {
                if(node.inWindow())
                    this.setWindowWeightedSize(this.windowWeightedSize() - node.getWeight());
                else if(node.inMainProtected())
                    this.setMainProtectedWeightedSize(this.mainProtectedWeightedSize() - node.getWeight());
                this.setWeightedSize(this.weightedSize() - node.getWeight());
            }
            node.die();
        }
    }

    /**
     * cache add操作后的后处理
     */
    final class AddTask implements Runnable {
        final Node<K, V> node;
        final int weight;

        AddTask(Node<K, V> node, int weight) {
            this.weight = weight;
            this.node = node;
        }

        @Override
        public void run() {
            if(evicts()) {
                long weightedSize = weightedSize();
                setWeightedSize(weightedSize + this.weight);
                setWindowWeightedSize(windowWeightedSize() + this.weight);  // 新来的会是window状态
                this.node.setPolicyWeight(this.node.getPolicyWeight() + this.weight);

                long maximum = maximum();
                // 接近maximum时，才会初始化FrequencySketch（懒初始化）
                if(weightedSize >= (maximum >>> 1)) {   // 超过maximum的一半
                    // isWeighted为false，说明每个Node的weight值固定为1
                    long capacity = isWeighted() ? data.mappingCount() : maximum;
                    frequencySketch().ensureCapacity(capacity);
                }

                K key = this.node.getKey();
                if(key != null)
                    frequencySketch().increment(key);
                setMissesInSample(missesInSample() + 1);    // add了说明之前没有命中cache
            }

            boolean isAlive;
            synchronized(this.node) {
                isAlive = this.node.isAlive();
            }
            if(isAlive) {
                if(expiresAfterWrite())
                    writeOrderDeque().add(this.node);
                if(evicts() && this.weight > windowMaximum())   // 如果node的weight太大了，直接放到window的最冷位置（尽快驱逐）
                    accessOrderWindowDeque().offerFirst(this.node);
                else if(evicts() || expiresAfterAccess())   // 正常放到window最热
                    accessOrderWindowDeque().offerLast(node);

                if(expiresVariable())
                    timerWheel().schedule(this.node);
            }

            if(isComputingAsync(this.node)) {   // 如果时async模式，并且value还没计算完
                synchronized(this.node) {
                    if(!Async.isReady((CompletableFuture<?>)this.node.getValue())) {
                        // 如果真的没算完，就把所有的时间暂时设为超大值，真正的时间值会在UpdateTask里填充
                        long expirationTime = expirationTicker().read() + Async.ASYNC_EXPIRY;
                        setVariableTime(node, expirationTime);
                        setAccessTime(node, expirationTime);
                        setWriteTime(node, expirationTime);
                    }
                }
            }
        }
    }

    /**
     * cache remove操作后的后处理
     */
    final class RemovalTask implements Runnable {
        final Node<K, V> node;

        RemovalTask(Node<K, V> node) {
            this.node = node;
        }

        @Override
        public void run() {
            if(this.node.inWindow() && (evicts() || expiresAfterAccess())) {
                accessOrderWindowDeque().remove(this.node);
            }else if(evicts()) {
                if(this.node.inMainProbation())
                    accessOrderProbationDeque().remove(this.node);
                else
                    accessOrderProtectedDeque().remove(this.node);
            }
            if(expiresAfterWrite())
                writeOrderDeque().remove(this.node);
            else if(expiresVariable())
                timerWheel().deschedule(this.node);
            makeDead(this.node);
        }
    }

    /**
     * cache update操作后的后处理
     */
    final class UpdateTask implements Runnable {
        final Node<K, V> node;
        final int weightDifference; // weight的增量

        public UpdateTask(Node<K, V> node, int weightDifference) {
            this.node = node;
            this.weightDifference = weightDifference;
        }

        @Override
        public void run() {
            if(evicts()) {
                int oldWeightedSize = this.node.getPolicyWeight();
                this.node.setPolicyWeight(oldWeightedSize + this.weightDifference);
                if(this.node.inWindow()) {
                    if(this.node.getPolicyWeight() <= windowMaximum())
                        onAccess(this.node);
                    else if(accessOrderWindowDeque().contains(this.node)) // node的weight太大了，挪到front等下一轮eviction再处理
                        accessOrderWindowDeque().moveToFront(this.node);
                    setWindowWeightedSize(windowWeightedSize() + this.weightDifference);
                }else if(this.node.inMainProbation()) {
                    if(this.node.getPolicyWeight() <= maximum())
                        onAccess(this.node);
                    else if(accessOrderProbationDeque().remove(this.node)) { // node的weight太大了，只能挪回window里
                        accessOrderWindowDeque().addFirst(this.node);
                        setWindowWeightedSize(windowWeightedSize() + this.node.getPolicyWeight());
                    }
                }else if(this.node.inMainProtected()) {
                    if(this.node.getPolicyWeight() <= maximum()) {
                        onAccess(this.node);
                        setMainProtectedWeightedSize(mainProtectedWeightedSize() + this.weightDifference);
                    }else if(accessOrderProtectedDeque().remove(this.node)) { // node的weight太大了，只能挪回window里
                        accessOrderWindowDeque().addFirst(this.node);
                        setWindowWeightedSize(windowWeightedSize() + this.node.getPolicyWeight());
                        setMainProtectedWeightedSize(mainProtectedWeightedSize() - oldWeightedSize);
                    }else {
                        setMainProtectedWeightedSize(mainProtectedWeightedSize() - oldWeightedSize);
                    }
                }
                setWeightedSize(weightedSize() + this.weightDifference);
            }else if(expiresAfterAccess()) {    // 没有清理策略，则按照read后处理
                onAccess(this.node);
            }

            if(expiresAfterWrite())
                reorder(writeOrderDeque(), this.node);  // 调整在writeOrderDeque里面的顺序
            else if(expiresVariable())
                timerWheel().reschedule(this.node);
        }
    }

    /* --------------- Concurrent Map Support相关实现 --------------- */

    @Override
    public boolean isEmpty() {
        return this.data.isEmpty();
    }

    @Override
    public int size() {
        return this.data.size();
    }

    @Override
    public long estimatedSize() {
        return this.data.mappingCount();
    }

    @Override
    public void clear() {
        this.evictionLock.lock();
        try {
            long now = this.expirationTicker().read();
            // 运行所有的写后处理
            Runnable task;
            while((task = this.writeBuffer.poll()) != null)
                task.run();

            // 移除所有的node
            for(Node<K, V> node : this.data.values())
                this.removeNode(node, now);

            // 取消cleanup的调度
            Pacer pacer = this.pacer();
            if(pacer != null)
                pacer.cancel();

            // 丢弃所有readBuffer内容
            this.readBuffer.drainTo(e -> {});
        } finally {
            this.evictionLock.unlock();
        }
    }

    /**
     * 移除单个node
     */
    void removeNode(Node<K, V> node, long now) {
        K key = node.getKey();
        V[] value = (V[])new Object[1];
        RemovalCause[] cause = new RemovalCause[1];

        this.data.computeIfPresent(node.getKeyReference(), (k, n) -> {
            if(n != node)
                return n;
            synchronized(n) {
                value[0] = n.getValue();
                if(key == null || value[0] == null)
                    cause[0] = RemovalCause.COLLECTED;
                else if(this.hasExpired(n, now))
                    cause[0] = RemovalCause.EXPIRED;
                else
                    cause[0] = RemovalCause.EXPLICIT;

                if(key != null)
                    this.writer.delete(key, value[0], cause[0]);
                this.makeDead(n);
                return null;
            }
        });

        if(node.inWindow() && (this.evicts() || this.expiresAfterAccess())) {
            this.accessOrderWindowDeque().remove(node);
        }else if(this.evicts()) {
            if(node.inMainProbation())
                this.accessOrderProbationDeque().remove(node);
            else
                this.accessOrderProtectedDeque().remove(node);
        }
        if(this.expiresAfterWrite())
            this.writeOrderDeque().remove(node);
        else if(this.expiresVariable())
            this.timerWheel().deschedule(node);

        if(cause[0] != null && this.hasRemovalListener())
            this.notifyRemoval(key, value[0], cause[0]);
    }

    @Override
    public boolean containsKey(Object key) {
        Node<K, V> node = this.data.get(this.nodeFactory.newLookupKey(key));
        return node != null && node.getValue() != null && !this.hasExpired(node, this.expirationTicker().read());
    }

    @Override
    public boolean containsValue(Object value) {
        Objects.requireNonNull(value);
        long now = this.expirationTicker().read();
        for(Node<K, V> node : this.data.values()) {
            if(node.containsValue(value) && !this.hasExpired(node, now) && node.getKey() != null)
                return true;
        }
        return false;
    }

    @Override
    public V get(Object key) {
        return this.getIfPresent(key, false);
    }

    @Override
    public V getIfPresent(Object key, boolean recordStats) {
        Node<K, V> node = this.data.get(this.nodeFactory.newLookupKey(key));
        if(node == null) {
            if(recordStats)
                this.statsCounter().recordMisses(1);
            if(this.drainStatus() == REQUIRED)  // 如果没有跑清理，顺便触发跑一下，和node为null其实关系不大
                this.scheduleDrainBuffers();
            return null;
        }

        V value = node.getValue();
        long now = expirationTicker().read();
        if(this.hasExpired(node, now) || (this.collectValues() && value == null)) {
            if(recordStats)
                this.statsCounter().recordMisses(1);
            this.scheduleDrainBuffers();    // 直接触发，因为node已过期，或者已被GC
            return null;
        }

        if(!this.isComputingAsync(node)) {
            // value已取出，并且可以返回了
            K castedKey = (K)key;
            this.setAccessTime(node, now);
            this.tryExpireAfterRead(node, castedKey, value, this.expiry(), now);
        }
        this.afterRead(node, now, recordStats);
        return value;
    }

    @Override
    public @Nullable V getIfPresentQuietly(Object key, long[] writeTime) {
        V value;
        Node<K, V> node = this.data.get(this.nodeFactory.newLookupKey(key));
        if(node == null || (value = node.getValue()) == null || this.hasExpired(node, this.expirationTicker().read()))
            return null;
        writeTime[0] = node.getWriteTime();
        return value;
    }

    @Override
    public Map<K, V> getAllPresent(Iterable<?> keys) {
        Set<Object> uniqueKeys = new LinkedHashSet<>();
        for(Object key : keys)
            uniqueKeys.add(key);

        int misses = 0;
        long now = expirationTicker().read();
        Map<Object, Object> result = new LinkedHashMap<>(uniqueKeys.size());
        for(Object key : uniqueKeys) {
            V value;
            Node<K, V> node = this.data.get(this.nodeFactory.newLookupKey(key));
            if(node == null || (value = node.getValue()) == null | this.hasExpired(node, now)) {
                misses++;
            }else {
                result.put(key, value);
                if(!this.isComputingAsync(node)) {
                    K castedKey = (K)key;
                    this.tryExpireAfterRead(node, castedKey, value, expiry(), now);
                    this.setAccessTime(node, now);
                }
                this.afterRead(node, now, /* recordHit */ false);
            }
        }
        this.statsCounter().recordMisses(misses);
        this.statsCounter().recordHits(result.size());
        Map<K, V> castedResult = (Map<K, V>)result;
        return Collections.unmodifiableMap(castedResult);
    }

    @Override
    public @Nullable V put(K key, V value) {
        return this.put(key, value, this.expiry(), true, false);
    }

    @Override
    public @Nullable V put(K key, V value, boolean notifyWriter) {
        return this.put(key, value, expiry(), notifyWriter, false);
    }

    @Override
    public @Nullable V putIfAbsent(K key, V value) {
        return this.put(key, value, expiry(), true, true);
    }

    V put(K key, V value, Expiry<K, V> expiry, boolean notifyWriter, boolean onlyIfAbsent) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);

        Node<K, V> node = null;
        long now = expirationTicker().read();
        int newWeight = this.weigher.weigh(key, value);
        while (true) {
            Node<K, V> prior = this.data.get(this.nodeFactory.newLookupKey(key));
            if (prior == null) { // key还不存在
                if (node == null) {
                    node = this.nodeFactory.newNode(key, this.keyReferenceQueue(),
                            value, this.valueReferenceQueue(), newWeight, now);
                    this.setVariableTime(node, this.expireAfterCreate(key, value, expiry, now));
                }
                if (notifyWriter && this.hasWriter()) {
                    Node<K, V> computed = node;
                    prior = this.data.computeIfAbsent(node.getKeyReference(), k -> {
                        this.writer.write(key, value);
                        return computed;
                    });
                    if (prior == node) { // 说明上面computeIfAbsent确实将新的node写入了
                        this.afterWrite(new AddTask(node, newWeight));
                        return null;
                    } else if (onlyIfAbsent) {
                        V currentValue = prior.getValue();
                        if (currentValue != null && !this.hasExpired(prior, now)) {
                            if (!this.isComputingAsync(prior)) {
                                this.tryExpireAfterRead(prior, key, currentValue, expiry(), now);
                                this.setAccessTime(prior, now);
                            }
                            this.afterRead(prior, now, false);
                            return currentValue;    // 方法结束并返回
                        }
                    }
                } else {
                    prior = this.data.putIfAbsent(node.getKeyReference(), node);
                    if (prior == null) { // 进了这里说明node成功put了，整个方法可以直接返回
                        this.afterWrite(new AddTask(node, newWeight));
                        return null;
                    } else if (onlyIfAbsent) {    // 说明key对应里面有node，开启了onlyIfAbsent则要保留旧值
                        V currentValue = prior.getValue();
                        if (currentValue != null && !this.hasExpired(prior, now)) {
                            if (!this.isComputingAsync(prior)) {
                                this.tryExpireAfterRead(prior, key, currentValue, expiry(), now);
                                this.setAccessTime(prior, now);
                            }
                            this.afterRead(prior, now, false);
                            return currentValue;    // 方法结束并返回
                        }
                    }
                }
            } else if (onlyIfAbsent) {    // 如果key已存在（即prior不为null），同时开启了onlyIfAbsent
                V currentValue = prior.getValue();
                if (currentValue != null && !this.hasExpired(prior, now)) {
                    if (!this.isComputingAsync(prior)) {
                        this.tryExpireAfterRead(prior, key, currentValue, expiry(), now);
                        this.setAccessTime(prior, now);
                    }
                    this.afterRead(prior, now, false);
                    return currentValue;    // 方法结束并返回
                }
            }

            // 如果key已经有对应value在里面，但是值为null或者有值但过期，继续下面的逻辑
            V oldValue;
            long varTime;
            int oldWeight;
            boolean expired = false;
            boolean mayUpdate = true;   // 这次put算不算update操作
            boolean exceedsTolerance = false;   // 是否值得做一次维护（即新node带来了过期时间是否足够大）
            synchronized (prior) {
                if (!prior.isAlive())
                    continue;
                oldValue = prior.getValue();
                oldWeight = prior.getWeight();
                if (oldValue == null) {  // 已被GC
                    varTime = this.expireAfterCreate(key, value, expiry, now);  // 刷新过期时间
                    this.writer.delete(key, null, RemovalCause.COLLECTED);
                } else if (this.hasExpired(prior, now)) {
                    expired = true;
                    varTime = this.expireAfterCreate(key, value, expiry, now);  // 刷新过期时间
                    this.writer.delete(key, oldValue, RemovalCause.EXPIRED);
                } else if (onlyIfAbsent) {
                    mayUpdate = false;
                    varTime = this.expireAfterRead(prior, key, value, expiry, now); // 刷新过期时间
                } else {
                    varTime = this.expireAfterUpdate(prior, key, value, expiry, now);   // 刷新过期时间
                }

                if (notifyWriter && (expired || (mayUpdate && (value != oldValue)))) {
                    this.writer.write(key, value);
                    if (mayUpdate) {
                        exceedsTolerance =
                                (expiresAfterWrite() && (now - prior.getWriteTime()) > EXPIRE_WRITE_TOLERANCE)
                                        || (expiresVariable() && Math.abs(varTime - prior.getVariableTime()) > EXPIRE_WRITE_TOLERANCE);

                        this.setWriteTime(prior, now);
                        prior.setWeight(newWeight);
                        prior.setValue(value, this.valueReferenceQueue());
                    }

                    this.setVariableTime(prior, varTime);
                    this.setAccessTime(prior, now);
                }

                if(this.hasRemovalListener()) {
                    if(expired)
                        this.notifyRemoval(key, oldValue, RemovalCause.EXPIRED);
                    else if(oldValue == null)
                        this.notifyRemoval(key, null, RemovalCause.COLLECTED);
                    else if(mayUpdate && (value != oldValue))
                        this.notifyRemoval(key, oldValue, RemovalCause.REPLACED);
                }

                int weightedDifference = mayUpdate ? (newWeight - oldWeight) : 0;
                if(oldValue == null || weightedDifference != 0 || expired)
                    this.afterWrite(new UpdateTask(prior, weightedDifference));
                else if(!onlyIfAbsent && exceedsTolerance)
                    this.afterWrite(new UpdateTask(prior, weightedDifference));
                else{
                    if(mayUpdate)
                        this.setWriteTime(prior, now);
                    this.afterRead(prior, now, false);
                }
                return expired ? null : oldValue;
            }
        }
    }

    public V remove(Object key) {
        K castedKey = (K)key;
        Node<K, V>[] node = new Node[1];
        V[] oldValue = (V[])new Object[1];
        RemovalCause[] cause = new RemovalCause[1];

        // 并没有真的remove，只是把value设为null了
        this.data.computeIfPresent(this.nodeFactory.newLookupKey(key), (k, n) -> {
            synchronized(n) {
                oldValue[0] = n.getValue();
                if(oldValue[0] == null)
                    cause[0] = RemovalCause.COLLECTED;
                else if(this.hasExpired(n, this.expirationTicker().read()))
                    cause[0] = RemovalCause.EXPIRED;
                else
                    cause[0] = RemovalCause.EXPLICIT;

                writer.delete(castedKey, oldValue[0], cause[0]);
                n.retire();
            }
            node[0] = n;
            return null;
        });

        if(cause[0] != null) {
            this.afterWrite(new RemovalTask(node[0]));
            if(this.hasRemovalListener())
                this.notifyRemoval(castedKey, oldValue[0], cause[0]);
        }
        return cause[0] == RemovalCause.EXPLICIT ? oldValue[0] : null;
    }

    @Override
    public boolean remove(Object key, Object value) {
        Objects.requireNonNull(key);
        if(value == null)
            return false;

        Node<K, V>[] removed = new Node[1];
        K[] oldKey = (K[])new Object[1];
        V[] oldValue = (V[])new Object[1];
        RemovalCause[] cause = new RemovalCause[1];

        this.data.computeIfPresent(this.nodeFactory.newLookupKey(key), (kR, node) -> {
            synchronized(node) {
                oldKey[0] = node.getKey();
                oldValue[0] = node.getValue();
                if(oldKey[0] == null)
                    cause[0] = RemovalCause.COLLECTED;
                else if(this.hasExpired(node, this.expirationTicker().read()))
                    cause[0] = RemovalCause.EXPIRED;
                else if(node.containsValue(value))
                    cause[0] = RemovalCause.EXPLICIT;
                else
                    return node;    // 保持不变

                this.writer.delete(oldKey[0], oldValue[0], cause[0]);
                removed[0] = node;
                node.retire();
                return null;
            }
        });

        if(removed[0] == null)
            return false;
        else if(this.hasRemovalListener())
            this.notifyRemoval(oldKey[0], oldValue[0], cause[0]);

        this.afterWrite(new RemovalTask(removed[0]));
        return cause[0] == RemovalCause.EXPLICIT;
    }

    @Override
    public V replace(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);

        int[] oldWeight = new int[1];
        K[] nodeKey = (K[])new Object[1];
        V[] oldValue = (V[])new Object[1];
        long[] now = new long[1];
        int weight = weigher.weigh(key, value);

        Node<K, V> node = data.computeIfPresent(nodeFactory.newLookupKey(key), (k, n) -> {
            synchronized(n) {
                nodeKey[0] = n.getKey();
                oldValue[0] = n.getValue();
                oldWeight[0] = n.getWeight();
                if(nodeKey[0] == null || oldValue[0] == null
                        || this.hasExpired(n, now[0] = this.expirationTicker().read())) {
                    // 如果key不存在，或者value不存在或已过期，则说明replace条件不成立
                    oldValue[0] = null;
                    return n;
                }

                long varTime = this.expireAfterUpdate(n, key, value, this.expiry(), now[0]);
                if(value != oldValue[0])
                    this.writer.write(nodeKey[0], value);

                // 注意是直接替换value，而不是return value
                n.setValue(value, this.valueReferenceQueue());
                n.setWeight(weight);

                this.setVariableTime(n, varTime);
                this.setAccessTime(n, now[0]);
                this.setWriteTime(n, now[0]);
                return n;
            }
        });

        if(oldValue[0] == null) // 说明replace条件不成立
            return null;

        int weightedDifference = weight - oldWeight[0];
        if(this.expiresAfterWrite() || weightedDifference != 0)
            this.afterWrite(new UpdateTask(node, weightedDifference));
        else
            this.afterRead(node, now[0], false);

        if(this.hasRemovalListener() && value != oldValue[0])
            this.notifyRemoval(nodeKey[0], oldValue[0], RemovalCause.REPLACED);

        return oldValue[0]; // 返回旧值
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(oldValue);
        Objects.requireNonNull(newValue);

        int weight = weigher.weigh(key, newValue);
        boolean[] replaced = new boolean[1];
        K[] nodeKey = (K[])new Object[1];
        V[] prevValue = (V[])new Object[1];
        int[] oldWeight = new int[1];
        long[] now = new long[1];

        Node<K, V> node = data.computeIfPresent(nodeFactory.newLookupKey(key), (k, n) -> {
            synchronized (n) {
                nodeKey[0] = n.getKey();
                prevValue[0] = n.getValue();
                oldWeight[0] = n.getWeight();
                if(nodeKey[0] == null || prevValue[0] == null || !n.containsValue(oldValue)
                        || this.hasExpired(n, now[0] = this.expirationTicker().read())) {
                    // 如果key不存在，或者value不存在或已过期，则说明replace条件不成立
                    return n;
                }

                long varTime = this.expireAfterUpdate(n, key, newValue, expiry(), now[0]);
                if(newValue != prevValue[0])
                    this.writer.write(key, newValue);
                n.setValue(newValue, this.valueReferenceQueue());
                n.setWeight(weight);

                this.setVariableTime(n, varTime);
                this.setAccessTime(n, now[0]);
                this.setWriteTime(n, now[0]);
                replaced[0] = true;
            }
            return n;
        });

        if(!replaced[0])
            return false;

        int weightedDifference = weight - oldWeight[0];
        if(this.expiresAfterWrite() || weightedDifference != 0)
            this.afterWrite(new UpdateTask(node, weightedDifference));
        else
            this.afterRead(node, now[0], false);

        if(this.hasRemovalListener() && oldValue != newValue)
            this.notifyRemoval(nodeKey[0], prevValue[0], RemovalCause.REPLACED);

        return true;
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        // 为了封装一下writer.write操作
        BiFunction<K, V, V> remappingFunction = (key, oldValue) -> {
            V newValue = Objects.requireNonNull(function.apply(key, oldValue));
            if(oldValue != newValue)
                this.writer.write(key, newValue);
            return newValue;
        };
        for(K key : this.keySet) {
            long[] now = { this.expirationTicker().read() };
            Object lookupKey = this.nodeFactory.newLookupKey(key);  // 反查出cache里面真正的key
            this.remap(key, lookupKey, remappingFunction, now, false);
        }
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
                             boolean recordStats, boolean recordLoad) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(mappingFunction);
        long now = this.expirationTicker().read();

        Node<K, V> node = data.get(this.nodeFactory.newLookupKey(key));
        if(node != null) {  // 有值了，继续检查是否真的不符合absent
            V value = node.getValue();
            if(value != null && !this.hasExpired(node, now)) {  // 真不符合absent，就当作read之后返回旧value
                if(!this.isComputingAsync(node)) {
                    this.tryExpireAfterRead(node, key, value, this.expiry(), now);
                    this.setAccessTime(node, now);
                }
                this.afterRead(node, now, recordStats);
                return value;
            }
        }
        // node为null或者value为null或者node已过期，不算absent
        if(recordStats)
            mappingFunction = this.statsAware(mappingFunction, recordLoad);
        Object keyRef = this.nodeFactory.newReferenceKey(key, keyReferenceQueue());
        // 调用实际的compute方法
        return this.doComputeIfAbsent(key, keyRef, mappingFunction, new long[] { now }, recordStats);
    }

    V doComputeIfAbsent(K key, Object keyRef, Function<? super K, ? extends V> mappingFunction,
                        long[] now, boolean recordStats) {
        V[] oldValue = (V[])new Object[1];
        V[] newValue = (V[])new Object[1];
        K[] nodeKey = (K[])new Object[1];
        Node<K, V>[] removed = new Node[1];
        int[] weight = new int[2]; // 旧值 + 新值
        RemovalCause[] cause = new RemovalCause[1];

        Node<K, V> node = this.data.compute(keyRef, (k, n) -> {
            if(n == null) { // 调用function计算新value
                newValue[0] = mappingFunction.apply(key);
                if(newValue[0] == null)
                    return null;
                now[0] = this.expirationTicker().read();
                weight[1] = this.weigher.weigh(key, newValue[0]);
                n = this.nodeFactory.newNode(key, this.keyReferenceQueue(),
                        newValue[0], this.valueReferenceQueue(), weight[1], now[0]);
                this.setVariableTime(n, this.expireAfterCreate(key, newValue[0], this.expiry(), now[0]));
                return n;   // 结束compute方法了
            }

            // node有值，走下面的compute流程
            synchronized(n) {
                nodeKey[0] = n.getKey();
                weight[0] = n.getWeight();
                oldValue[0] = n.getValue();
                if(nodeKey[0] == null || oldValue[0] == null)
                    cause[0] = RemovalCause.COLLECTED;
                else if(this.hasExpired(n, now[0]))
                    cause[0] = RemovalCause.EXPIRED;
                else
                    return n;   // 合法的node，只能保持旧值了，不符合absent语义

                this.writer.delete(nodeKey[0], oldValue[0], cause[0]);
                newValue[0] = mappingFunction.apply(key);   // 尝试计算
                if(newValue[0] == null) {   // 说明mappingFunction等同于remove
                    removed[0] = n;
                    n.retire();
                    return null;
                }
                weight[1] = this.weigher.weigh(key, newValue[0]);
                n.setValue(newValue[0], this.valueReferenceQueue());
                n.setWeight(weight[1]);

                now[0] = this.expirationTicker().read();
                this.setVariableTime(n, this.expireAfterCreate(key, newValue[0], this.expiry(), now[0]));
                this.setAccessTime(n, now[0]);
                this.setWriteTime(n, now[0]);
                return n;
            }
        });

        if(node == null) {  // remove
            if(removed[0] != null)
                this.afterWrite(new RemovalTask(removed[0]));
            return null;
        }
        if(cause[0] != null) {
            if(this.hasRemovalListener())
                this.notifyRemoval(nodeKey[0], oldValue[0], cause[0]);
            this.statsCounter().recordEviction(weight[0], cause[0]);
        }
        if(newValue[0] == null) {   // 算出来value还是null，只能返回旧值了
            if(!this.isComputingAsync(node)) {
                this.tryExpireAfterRead(node, key, oldValue[0], expiry(), now[0]);
                this.setAccessTime(node, now[0]);
            }
            this.afterRead(node, now[0], recordStats);
            return oldValue[0];
        }
        if(oldValue[0] == null && cause[0] == null) {   // 说明key是新的，就是正常的新增
            this.afterWrite(new AddTask(node, weight[1]));
        }else { // 否则就是update
            int weightedDifference = (weight[1] - weight[0]);
            this.afterWrite(new UpdateTask(node, weightedDifference));
        }

        return newValue[0];
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(remappingFunction);

        Object lookupKey = this.nodeFactory.newLookupKey(key);
        @Nullable Node<K, V> node = this.data.get(lookupKey);
        long now;
        if(node == null) {  // 不符合present语义
            return null;
        }else if (node.getValue() == null || this.hasExpired(node, (now = this.expirationTicker().read()))) {
            // value为null、node过期，都不符合present语义
            this.scheduleDrainBuffers();
            return null;
        }

        BiFunction<? super K, ? super V, ? extends V> statsAwareRemappingFunction =
                statsAware(remappingFunction, false, true, true);
        return this.remap(key, lookupKey, statsAwareRemappingFunction,
                new long[] { now }, false);
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
                     boolean recordMiss, boolean recordLoad, boolean recordLoadFailure) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(remappingFunction);

        long[] now = { this.expirationTicker().read() };
        Object keyRef = this.nodeFactory.newReferenceKey(key, this.keyReferenceQueue());
        BiFunction<? super K, ? super V, ? extends V> statsAwareRemappingFunction =
                statsAware(remappingFunction, recordMiss, recordLoad, recordLoadFailure);
        // 最后一个参数computeIfAbsent和computeIfPresent方法传的相反
        return this.remap(key, keyRef, statsAwareRemappingFunction, now, true);
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        Objects.requireNonNull(remappingFunction);

        long[] now = { this.expirationTicker().read() };
        Object keyRef = this.nodeFactory.newReferenceKey(key, this.keyReferenceQueue());
        BiFunction<? super K, ? super V, ? extends V> mergeFunction = (k, oldValue) ->
                oldValue == null ? value : this.statsAware(remappingFunction).apply(oldValue, value);
        return remap(key, keyRef, mergeFunction, now, true);
    }

    /**
     * 尝试为指定的key以及对应的value，计算出新的value
     * 很多方法最终都会复用此方法
     */
    V remap(K key, Object keyRef, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
            long[] now, boolean computeIfAbsent) {
        K[] nodeKey = (K[])new Object[1];
        V[] oldValue = (V[])new Object[1];
        V[] newValue = (V[])new Object[1];
        Node<K, V>[] removed = new Node[1];
        int[] weight = new int[2]; // 旧值 + 新值
        RemovalCause[] cause = new RemovalCause[1];

        Node<K, V> node = this.data.compute(keyRef, (kr, n) -> {
            if(n == null) {
                if(!computeIfAbsent)    // 显式为true才可以继续，不然就直接让返回的node为null
                    return null;
                newValue[0] = remappingFunction.apply(key, null);   // 直接算新value
                if(newValue[0] == null)
                    return null;
                now[0] = this.expirationTicker().read();
                weight[1] = weigher.weigh(key, newValue[0]);
                // 生成对应新的node实例
                n = nodeFactory.newNode(keyRef, newValue[0], this.valueReferenceQueue(), weight[1], now[0]);
                this.setVariableTime(n, expireAfterCreate(key, newValue[0], expiry(), now[0]));
                return n;   // node = 刚新建的n
            }

            // 走到这里，说明原来有旧value
            synchronized(n) {
                nodeKey[0] = n.getKey();
                oldValue[0] = n.getValue();
                if(nodeKey[0] == null || oldValue[0] == null)
                    cause[0] = RemovalCause.COLLECTED;
                else if(this.hasExpired(n, now[0]))
                    cause[0] = RemovalCause.EXPIRED;

                if(cause[0] != null) {
                    this.writer.delete(nodeKey[0], oldValue[0], cause[0]);
                    if(!computeIfAbsent) {  // 显式为false，说明允许旧value为null，不能用新node覆盖
                        removed[0] = n;
                        n.retire();
                        return null;
                    }
                }

                // 根据旧value，计算新value
                newValue[0] = remappingFunction.apply(nodeKey[0], cause[0] == null ?oldValue[0] : null);
                if(newValue[0] == null) {
                    if(cause[0] == null)
                        cause[0] = RemovalCause.EXPLICIT;
                    removed[0] = n;
                    n.retire();
                    return null;
                }

                weight[0] = n.getWeight();
                weight[1] = this.weigher.weigh(key, newValue[0]);
                now[0] = expirationTicker().read();
                if(cause[0] == null) {
                    if(newValue[0] != oldValue[0])
                        cause[0] = RemovalCause.REPLACED;
                    this.setVariableTime(n, expireAfterUpdate(n, key, newValue[0], expiry(), now[0]));
                }else {
                    this.setVariableTime(n, expireAfterCreate(key, newValue[0], expiry(), now[0]));
                }
                n.setValue(newValue[0], this.valueReferenceQueue());
                n.setWeight(weight[1]);
                this.setAccessTime(n, now[0]);
                this.setWriteTime(n, now[0]);
                return n;
            }
        });

        // 终于生成了新的node了
        if(cause[0] != null) {
            if(cause[0].wasEvicted())
                this.statsCounter().recordEviction(weight[0], cause[0]);
            if(this.hasRemovalListener())
                this.notifyRemoval(nodeKey[0], oldValue[0], cause[0]);
        }

        if(removed[0] != null) {
            this.afterWrite(new RemovalTask(removed[0]));
        }else if(node == null) {
            // absent and not computable
        }else if(oldValue[0] == null && cause[0] == null) { // 纯新增，没有旧值
            this.afterWrite(new AddTask(node, weight[1]));
        }else {
            if(cause[0] == null) {
                if(!this.isComputingAsync(node)) {
                    this.tryExpireAfterRead(node, key, newValue[0], this.expiry(), now[0]);
                    this.setAccessTime(node, now[0]);
                }
            }else if(cause[0] == RemovalCause.COLLECTED)
                this.scheduleDrainBuffers();
            this.afterRead(node, now[0], false);
        }

        return newValue[0];
    }

    @Override
    public Set<K> keySet() {
        final Set<K> ks = this.keySet;
        return ks == null ? this.keySet = new KeySetView<>(this) : ks;
    }

    @Override
    public Collection<V> values() {
        final Collection<V> vs = this.values;
        return vs == null ? this.values = new ValuesView<>(this) : vs;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        final Set<Entry<K, V>> es = this.entrySet;
        return es == null ? this.entrySet = new EntrySetView<>(this) : es;
    }

    /**
     * 返回最热/最冷的limit个Node
     */
    Map<K, V> evictionOrder(int limit, Function<V, V> transformer, boolean hottest) {
        Supplier<Iterator<Node<K, V>>> iteratorSupplier = () -> {
            // 定义排序器（按照流行度里存的频率）
            Comparator<Node<K, V>> comparator = Comparator.comparingInt(node -> {
                K key = node.getKey();
                return key == null ? 0 : frequencySketch().frequency(key);
            });
            if(hottest) {
                var secondary = LinkedDeque.PeekingIterator.comparing(
                        this.accessOrderProbationDeque().descendingIterator(),
                        this.accessOrderWindowDeque().descendingIterator(), comparator);
                return LinkedDeque.PeekingIterator.concat(this.accessOrderProtectedDeque().descendingIterator(), secondary);
            }else {
                var primary = LinkedDeque.PeekingIterator.comparing(
                        this.accessOrderWindowDeque().iterator(),
                        this.accessOrderProbationDeque().iterator(), comparator.reversed());
                return LinkedDeque.PeekingIterator.concat(primary, this.accessOrderProtectedDeque().iterator());
            }
        };
        return this.fixedSnapshot(iteratorSupplier, limit, transformer);
    }

    /**
     * 返回最年轻/最老（access时间）的limit个Node
     */
    Map<K, V> expireAfterAccessOrder(int limit, Function<V, V> transformer, boolean oldest) {
        if(!this.evicts()) {
            // 没有开启清理功能，则直接走里面的逻辑
            Supplier<Iterator<Node<K, V>>> iteratorSupplier = () -> oldest
                    ? this.accessOrderWindowDeque().iterator()
                    : this.accessOrderWindowDeque().descendingIterator();
            return this.fixedSnapshot(iteratorSupplier, limit, transformer);
        }

        // 开启了清理，则走下面的流程
        Supplier<Iterator<Node<K, V>>> iteratorSupplier = () -> {
            Comparator<Node<K, V>> comparator = Comparator.comparingLong(Node::getAccessTime);
            LinkedDeque.PeekingIterator<Node<K, V>> first, second, third;
            if(oldest) {
                first = this.accessOrderWindowDeque().iterator();
                second = this.accessOrderProbationDeque().iterator();
                third = this.accessOrderProtectedDeque().iterator();
            }else {
                comparator = comparator.reversed();
                first = this.accessOrderWindowDeque().descendingIterator();
                second = this.accessOrderProbationDeque().descendingIterator();
                third = this.accessOrderProtectedDeque().descendingIterator();
            }
            return LinkedDeque.PeekingIterator.comparing(
                    LinkedDeque.PeekingIterator.comparing(first, second, comparator), third, comparator);
        };
        return this.fixedSnapshot(iteratorSupplier, limit, transformer);
    }

    /**
     * 返回最年轻/最老（write时间）的limit个Node
     */
    Map<K, V> expireAfterWriteOrder(int limit, Function<V, V> transformer, boolean oldest) {
        Supplier<Iterator<Node<K, V>>> iteratorSupplier = () -> oldest
                ? this.writeOrderDeque().iterator()
                : this.writeOrderDeque().descendingIterator();
        return this.fixedSnapshot(iteratorSupplier, limit, transformer);
    }

    /**
     * 返回一个由给定的迭代器排序的只读快照映射
     */
    Map<K, V> fixedSnapshot(Supplier<Iterator<Node<K, V>>> iteratorSupplier, int limit, Function<V, V> transformer) {
        Caffeine.requireArgument(limit >= 0);
        this.evictionLock.lock();
        try {
            this.maintenance(null); // 强制清理一次
            int initialCapacity = Math.min(limit, size());
            Iterator<Node<K, V>> iter = iteratorSupplier.get();
            Map<K, V> map = new LinkedHashMap<>(initialCapacity);
            while(map.size() < limit && iter.hasNext()) {
                Node<K, V> node = iter.next();
                K key = node.getKey();
                V value = transformer.apply(node.getValue());
                if(key != null && value != null && node.isAlive())
                    map.put(key, value);
            }
            return Collections.unmodifiableMap(map);
        } finally {
            this.evictionLock.unlock();
        }
    }

    /**
     * 返回一个由到variable到期顺序的只读快照映射
     */
    Map<K, V> variableSnapshot(boolean ascending, int limit, Function<V, V> transformer) {
        this.evictionLock.lock();
        try {
            this.maintenance(null);
            return this.timerWheel().snapshot(ascending, limit, transformer);
        } finally {
            this.evictionLock.unlock();
        }
    }

    static final class KeySetView<K, V> extends AbstractSet<K> {
        final BoundedLocalCache<K, V> cache;

        KeySetView(BoundedLocalCache<K, V> cache) {
            this.cache = Objects.requireNonNull(cache);
        }

        @Override
        public int size() {
            return this.cache.size();
        }

        @Override
        public void clear() {
            this.cache.clear();
        }

        @Override
        public boolean contains(Object obj) {
            return this.cache.containsKey(obj);
        }

        @Override
        public boolean remove(Object obj) {
            return this.cache.remove(obj) != null;
        }

        @Override
        public Iterator<K> iterator() {
            return new KeyIterator<>(this.cache);
        }

        @Override
        public Spliterator<K> spliterator() {
            return new KeySpliterator<>(this.cache);
        }

        @Override
        public Object[] toArray() {
            List<Object> keys = new ArrayList<>(this.size());
            for(Object key : this)
                keys.add(key);
            return keys.toArray();
        }

        @Override
        public <T> T[] toArray(T[] array) {
            List<Object> keys = new ArrayList<>(this.size());
            for(Object key : this)
                keys.add(key);
            return keys.toArray(array);
        }
    }

    static final class KeyIterator<K, V> implements Iterator<K> {
        final EntryIterator<K, V> iter;

        KeyIterator(BoundedLocalCache<K, V> cache) {
            this.iter = new EntryIterator<>(cache);
        }

        @Override
        public boolean hasNext() {
            return this.iter.hasNext();
        }

        @Override
        public K next() {
            return this.iter.nextKey();
        }

        @Override
        public void remove() {
            this.iter.remove();
        }
    }

    static final class KeySpliterator<K, V> implements Spliterator<K> {
        final BoundedLocalCache<K, V> cache;
        final Spliterator<Node<K, V>> spliterator;

        KeySpliterator(BoundedLocalCache<K, V> cache, Spliterator<Node<K, V>> spliterator) {
            this.spliterator = Objects.requireNonNull(spliterator);
            this.cache = Objects.requireNonNull(cache);
        }

        KeySpliterator(BoundedLocalCache<K, V> cache) {
            this(cache, cache.data.values().spliterator());
        }

        @Override
        public void forEachRemaining(Consumer<? super K> action) {
            Objects.requireNonNull(action);
            // 只是个定义
            Consumer<Node<K, V>> consumer = node -> {
                K key = node.getKey();
                V value = node.getValue();
                long now = this.cache.expirationTicker().read();
                if(key != null && value != null && node.isAlive() && !this.cache.hasExpired(node, now))
                    action.accept(key);
            };
            this.spliterator.forEachRemaining(consumer);
        }

        @Override
        public boolean tryAdvance(Consumer<? super K> action) {
            Objects.requireNonNull(action);
            boolean[] advanced = { false };
            // 只是个定义
            Consumer<Node<K, V>> consumer = node -> {
                K key = node.getKey();
                V value = node.getValue();
                long now = cache.expirationTicker().read();
                if(key != null && value != null && node.isAlive() && !this.cache.hasExpired(node, now)) {
                    action.accept(key);
                    advanced[0] = true;
                }
            };
            while(true) {
                if(this.spliterator.tryAdvance(consumer)) {
                    if(advanced[0])
                        return true;
                    continue;
                }
                return false;
            }
        }

        @Override
        public Spliterator<K> trySplit() {
            Spliterator<Node<K, V>> split = this.spliterator.trySplit();
            return split == null ? null : new KeySpliterator<>(this.cache, split);
        }

        @Override
        public long estimateSize() {
            return this.spliterator.estimateSize();;
        }

        @Override
        public int characteristics() {
            return Spliterator.DISTINCT | Spliterator.CONCURRENT | Spliterator.NONNULL;;
        }
    }

    static final class ValuesView<K, V> extends AbstractCollection<V> {
        final BoundedLocalCache<K, V> cache;

        ValuesView(BoundedLocalCache<K, V> cache) {
            this.cache = Objects.requireNonNull(cache);
        }

        @Override
        public int size() {
            return this.cache.size();
        }

        @Override
        public void clear() {
            this.cache.clear();
        }

        @Override
        public boolean contains(Object o) {
            return this.cache.containsValue(o);
        }

        @Override
        public boolean removeIf(Predicate<? super V> filter) {
            Objects.requireNonNull(filter);
            boolean removed = false;
            for(Entry<K, V> entry : this.cache.entrySet()) {
                if(filter.test(entry.getValue()))
                    removed |= this.cache.remove(entry.getKey(), entry.getValue());
            }
            return removed;
        }

        @Override
        public Iterator<V> iterator() {
            return new ValueIterator<>(this.cache);
        }

        @Override
        public Spliterator<V> spliterator() {
            return new ValueSpliterator<>(this.cache);
        }
    }

    static final class ValueIterator<K, V> implements Iterator<V> {
        final EntryIterator<K, V> iter;

        ValueIterator(BoundedLocalCache<K, V> cache) {
            this.iter = new EntryIterator<>(cache);
        }

        @Override
        public boolean hasNext() {
            return this.iter.hasNext();
        }

        @Override
        public V next() {
            return this.iter.nextValue();
        }

        @Override
        public void remove() {
            this.iter.remove();
        }
    }

    static final class ValueSpliterator<K, V> implements Spliterator<V> {
        final BoundedLocalCache<K, V> cache;
        final Spliterator<Node<K, V>> spliterator;

        ValueSpliterator(BoundedLocalCache<K, V> cache, Spliterator<Node<K, V>> spliterator) {
            this.spliterator = Objects.requireNonNull(spliterator);
            this.cache = Objects.requireNonNull(cache);
        }

        ValueSpliterator(BoundedLocalCache<K, V> cache) {
            this(cache, cache.data.values().spliterator());
        }

        @Override
        public void forEachRemaining(Consumer<? super V> action) {
            Objects.requireNonNull(action);
            Consumer<Node<K, V>> consumer = node -> {
                K key = node.getKey();
                V value = node.getValue();
                long now = this.cache.expirationTicker().read();
                if(key != null && value != null && node.isAlive() && !this.cache.hasExpired(node, now))
                    action.accept(value);
            };
            this.spliterator.forEachRemaining(consumer);
        }

        @Override
        public boolean tryAdvance(Consumer<? super V> action) {
            Objects.requireNonNull(action);
            boolean[] advanced = { false };
            long now = this.cache.expirationTicker().read();
            Consumer<Node<K, V>> consumer = node -> {
                K key = node.getKey();
                V value = node.getValue();
                if ((key != null) && (value != null) && !this.cache.hasExpired(node, now) && node.isAlive()) {
                    action.accept(value);
                    advanced[0] = true;
                }
            };
            while(true) {
                if(this.spliterator.tryAdvance(consumer)) {
                    if(advanced[0])
                        return true;
                    continue;
                }
                return false;
            }
        }

        @Override
        public @Nullable Spliterator<V> trySplit() {
            Spliterator<Node<K, V>> split = this.spliterator.trySplit();
            return split == null ? null : new ValueSpliterator<>(this.cache, split);
        }

        @Override
        public long estimateSize() {
            return this.spliterator.estimateSize();
        }

        @Override
        public int characteristics() {
            return Spliterator.CONCURRENT | Spliterator.NONNULL;
        }
    }

    static final class EntrySetView<K, V> extends AbstractSet<Entry<K, V>> {
        final BoundedLocalCache<K, V> cache;

        EntrySetView(BoundedLocalCache<K, V> cache) {
            this.cache = requireNonNull(cache);
        }

        @Override
        public int size() {
            return this.cache.size();
        }

        @Override
        public void clear() {
            this.cache.clear();
        }

        @Override
        public boolean contains(Object obj) {
            if(!(obj instanceof Entry<?, ?>))
                return false;
            Entry<?, ?> entry = (Entry<?, ?>) obj;
            Object key = entry.getKey();
            Object value = entry.getValue();
            if(key == null || value == null)
                return false;
            Node<K, V> node = this.cache.data.get(this.cache.nodeFactory.newLookupKey(key));
            return node != null && node.containsValue(value);
        }

        @Override
        public boolean remove(Object obj) {
            if(!(obj instanceof Entry<?, ?>))
                return false;
            Entry<?, ?> entry = (Entry<?, ?>)obj;
            return this.cache.remove(entry.getKey(), entry.getValue());
        }

        @Override
        public boolean removeIf(Predicate<? super Entry<K, V>> filter) {
            Objects.requireNonNull(filter);
            boolean removed = false;
            for(Entry<K, V> entry : this) {
                if(filter.test(entry))
                    removed |= this.cache.remove(entry.getKey(), entry.getValue());
            }
            return removed;
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator<>(this.cache);
        }

        @Override
        public Spliterator<Entry<K, V>> spliterator() {
            return new EntrySpliterator<>(this.cache);
        }
    }

    static final class EntryIterator<K, V> implements Iterator<Entry<K, V>> {
        final BoundedLocalCache<K, V> cache;
        final Iterator<Node<K, V>> iter;
        final long now;

        K key;
        V value;
        K removalKey;
        Node<K, V> next;

        EntryIterator(BoundedLocalCache<K, V> cache) {
            this.cache = cache;
            this.now = cache.expirationTicker().read();
            this.iter = cache.data.values().iterator();
        }

        @Override
        public boolean hasNext() {
            if(this.next != null)
                return true;
            while(true) {
                if(this.iter.hasNext()) {
                    this.next = this.iter.next();
                    this.key = this.next.getKey();
                    this.value = this.next.getValue();

                    // node是否已过期，即遍历过程要跳过过期的node
                    boolean evictable = this.cache.hasExpired(this.next, this.now) || this.key == null || this.value == null;
                    if(evictable || !this.next.isAlive()) {
                        if(evictable)
                            this.cache.scheduleDrainBuffers();
                        this.value = null;
                        this.next = null;
                        this.key = null;
                        continue;
                    }
                    return true;
                }
                return false;
            }
        }

        @Override
        public Entry<K, V> next() {
            if(!this.hasNext())
                throw new NoSuchElementException();
            Entry<K, V> entry = new WriteThroughEntry<>(this.cache, this.key, this.value);
            this.removalKey = this.key;
            this.value = null;
            this.next = null;
            this.key = null;
            return entry;
        }

        @Override
        public void remove() {
            if(this.removalKey == null)
                throw new IllegalStateException();
            this.cache.remove(this.removalKey);
            this.removalKey = null;
        }

        K nextKey() {
            if(!this.hasNext())
                throw new NoSuchElementException();
            this.removalKey = this.key;
            this.value = null;
            this.next = null;
            this.key = null;
            return this.removalKey;
        }

        V nextValue() {
            if(!this.hasNext())
                throw new NoSuchElementException();
            this.removalKey = this.key;
            V val = this.value;
            this.value = null;
            this.next = null;
            this.key = null;
            return val;
        }
    }

    static final class EntrySpliterator<K, V> implements Spliterator<Entry<K, V>> {
        final BoundedLocalCache<K, V> cache;
        final Spliterator<Node<K, V>> spliterator;

        EntrySpliterator(BoundedLocalCache<K, V> cache, Spliterator<Node<K, V>> spliterator) {
            this.spliterator = Objects.requireNonNull(spliterator);
            this.cache = Objects.requireNonNull(cache);
        }

        EntrySpliterator(BoundedLocalCache<K, V> cache) {
            this(cache, cache.data.values().spliterator());
        }

        @Override
        public void forEachRemaining(Consumer<? super Entry<K, V>> action) {
            Objects.requireNonNull(action);
            Consumer<Node<K, V>> consumer = node -> {
                K key = node.getKey();
                V value = node.getValue();
                long now = this.cache.expirationTicker().read();
                if ((key != null) && (value != null) && node.isAlive() && !this.cache.hasExpired(node, now)) {
                    action.accept(new WriteThroughEntry<>(this.cache, key, value));
                }
            };
            this.spliterator.forEachRemaining(consumer);
        }

        @Override
        public boolean tryAdvance(Consumer<? super Entry<K, V>> action) {
            Objects.requireNonNull(action);
            boolean[] advanced = { false };
            Consumer<Node<K, V>> consumer = node -> {
                K key = node.getKey();
                V value = node.getValue();
                long now = this.cache.expirationTicker().read();
                if ((key != null) && (value != null) && node.isAlive() && !this.cache.hasExpired(node, now)) {
                    action.accept(new WriteThroughEntry<>(this.cache, key, value));
                    advanced[0] = true;
                }
            };
            while(true) {
                if(this.spliterator.tryAdvance(consumer)) {
                    if(advanced[0])
                        return true;
                    continue;
                }
                return false;
            }
        }

        @Override
        public @Nullable Spliterator<Entry<K, V>> trySplit() {
            Spliterator<Node<K, V>> split = this.spliterator.trySplit();
            return split == null ? null : new EntrySpliterator<>(this.cache, split);
        }

        @Override
        public long estimateSize() {
            return this.spliterator.estimateSize();
        }

        @Override
        public int characteristics() {
            return Spliterator.DISTINCT | Spliterator.CONCURRENT | Spliterator.NONNULL;
        }
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

    /**
     * 将cache配置序列化
     */
    static <K, V> SerializationProxy<K, V> makeSerializationProxy(BoundedLocalCache<?, ?> cache, boolean isWeighted) {
        SerializationProxy<K, V> proxy = new SerializationProxy<>();
        proxy.weakKeys = cache.collectKeys();
        proxy.weakValues = cache.nodeFactory.weakValues();
        proxy.softValues = cache.nodeFactory.softValues();
        proxy.isRecordingStats = cache.isRecordingStats();
        proxy.removalListener = cache.removalListener();
        proxy.ticker = cache.expirationTicker();
        proxy.writer = cache.writer;
        if(cache.expiresAfterAccess())
            proxy.expiresAfterAccessNanos = cache.expiresAfterAccessNanos();
        if(cache.expiresAfterWrite())
            proxy.expiresAfterWriteNanos = cache.expiresAfterWriteNanos();
        if(cache.expiresVariable())
            proxy.expiry = cache.expiry();
        if(cache.evicts()) {
            if(isWeighted) {
                proxy.weigher = cache.weigher;
                proxy.maximumWeight = cache.maximum();
            } else {
                proxy.maximumSize = cache.maximum();
            }
        }
        return proxy;
    }

    /* --------------- Manual Cache同步相关实现 --------------- */

    static class BoundedLocalManualCache<K, V> implements LocalManualCache<K, V>, Serializable {
        private static final long serialVersionUID = 1;

        final BoundedLocalCache<K, V> cache;
        final boolean isWeighted;
        Policy<K, V> policy;

        BoundedLocalManualCache(Caffeine<K, V> builder) {
            this(builder, null);
        }

        BoundedLocalManualCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
            this.cache = LocalCacheFactory.newBoundedLocalCache(builder, loader, false);
            this.isWeighted = builder.isWeighted();
        }

        @Override
        public BoundedLocalCache<K, V> cache() {
            return this.cache;
        }

        @Override
        public Policy<K, V> policy() {
            return this.policy == null
                    ? this.policy = new BoundedPolicy<>(this.cache, Function.identity(), this.isWeighted)
                    : this.policy;
        }

        private void readObject(ObjectInputStream stream) throws InvalidObjectException {
            throw new InvalidObjectException("Proxy required");
        }

        Object writeReplace() {
            return makeSerializationProxy(cache, isWeighted);
        }

    }

    static final class BoundedPolicy<K, V> implements Policy<K, V> {
        final BoundedLocalCache<K, V> cache;
        final Function<V, V> transformer;
        final boolean isWeighted;

        Optional<Eviction<K, V>> eviction;
        Optional<Expiration<K, V>> refreshes;
        Optional<Expiration<K, V>> afterWrite;
        Optional<Expiration<K, V>> afterAccess;
        Optional<VarExpiration<K, V>> variable;

        BoundedPolicy(BoundedLocalCache<K, V> cache, Function<V, V> transformer, boolean isWeighted) {
            this.transformer = transformer;
            this.isWeighted = isWeighted;
            this.cache = cache;
        }

        @Override
        public boolean isRecordingStats() {
            return this.cache.isRecordingStats();
        }

        @Override
        public V getIfPresentQuietly(Object key) {
            Node<K, V> node = this.cache.data.get(this.cache.nodeFactory.newLookupKey(key));
            if(node == null || this.cache.hasExpired(node, this.cache.expirationTicker().read()))
                return null;
            return this.transformer.apply(node.getValue());
        }

        @Override
        public Optional<Eviction<K, V>> eviction() {
            return this.cache.evicts()
                    ? this.eviction == null ? this.eviction = Optional.of(new BoundedEviction()) : this.eviction;
        }

        @Override
        public Optional<Expiration<K, V>> expireAfterAccess() {
            if(!this.cache.expiresAfterAccess())
                return Optional.empty();
            return this.afterAccess == null
                    ? this.afterAccess = Optional.of(new BoundedExpireAfterAccess())
                    : this.afterAccess;
        }

        @Override
        public Optional<Expiration<K, V>> expireAfterWrite() {
            if(!this.cache.expiresAfterWrite())
                return Optional.empty();
            return this.afterWrite == null
                    ? this.afterWrite = Optional.of(new BoundedExpireAfterWrite())
                    : this.afterWrite;
        }

        @Override
        public Optional<VarExpiration<K, V>> expireVariably() {
            if(!cache.expiresVariable())
                return Optional.empty();
            return this.variable == null
                    ? this.variable = Optional.of(new BoundedVarExpiration())
                    : this.variable;
        }

        @Override
        public Optional<Expiration<K, V>> refreshAfterWrite() {
            if(!cache.refreshAfterWrite())
                return Optional.empty();
            return this.refreshes == null
                    ? this.refreshes = Optional.of(new BoundedRefreshAfterWrite())
                    : this.refreshes;
        }

        final class BoundedEviction implements Eviction<K, V> {
            @Override
            public boolean isWeighted() {
                return isWeighted;
            }

            @Override
            public OptionalInt weightOf(K key) {
                Objects.requireNonNull(key);
                if(!isWeighted)
                    return OptionalInt.empty();
                Node<K, V> node = cache.data.get(cache.nodeFactory.newLookupKey(key));
                if(node == null)
                    return OptionalInt.empty();
                synchronized (node) {
                    return OptionalInt.of(node.getWeight());
                }
            }

            @Override
            public OptionalLong weightedSize() {
                if(cache.evicts() && this.isWeighted()) {
                    cache.evictionLock.lock();
                    try {
                        return OptionalLong.of(Math.max(0, cache.weightedSize()));
                    } finally {
                        cache.evictionLock.unlock();
                    }
                }
                return OptionalLong.empty();
            }

            @Override
            public long getMaximum() {
                cache.evictionLock.lock();
                try {
                    return cache.maximum();
                } finally {
                    cache.evictionLock.unlock();
                }
            }

            @Override
            public void setMaximum(long maximum) {
                cache.evictionLock.lock();
                try {
                    cache.setMaximumSize(maximum);
                    cache.maintenance(null);
                } finally {
                    cache.evictionLock.unlock();
                }
            }

            @Override
            public Map<K, V> coldest(int limit) {
                return cache.evictionOrder(limit, transformer, false);
            }

            @Override
            public Map<K, V> hottest(int limit) {
                return cache.evictionOrder(limit, transformer, true);
            }
        }

        final class BoundedExpireAfterAccess implements Expiration<K, V> {
            @Override
            public OptionalLong ageOf(K key, TimeUnit unit) {
                Objects.requireNonNull(key);
                Objects.requireNonNull(unit);
                Object lookupKey = cache.nodeFactory.newLookupKey(key);
                Node<?, ?> node = cache.data.get(lookupKey);
                if(node == null)
                    return OptionalLong.empty();
                long age = cache.expirationTicker().read() - node.getAccessTime();
                return age > cache.expiresAfterAccessNanos()
                        ? OptionalLong.empty()
                        : OptionalLong.of(unit.convert(age, TimeUnit.NANOSECONDS));
            }

            @Override
            public long getExpiresAfter(TimeUnit unit) {
                return unit.convert(cache.expiresAfterAccessNanos(), TimeUnit.NANOSECONDS);
            }

            @Override
            public void setExpiresAfter(long duration, TimeUnit unit) {
                Caffeine.requireArgument(duration >= 0);
                cache.setExpiresAfterAccessNanos(unit.toNanos(duration));
                cache.scheduleAfterWrite();
            }

            @Override
            public Map<K, V> oldest(int limit) {
                return cache.expireAfterAccessOrder(limit, transformer, true);
            }

            @Override
            public Map<K, V> youngest(int limit) {
                return cache.expireAfterAccessOrder(limit, transformer, false);
            }
        }

        final class BoundedExpireAfterWrite implements Expiration<K, V> {
            @Override
            public OptionalLong ageOf(K key, TimeUnit unit) {
                Objects.requireNonNull(key);
                Objects.requireNonNull(unit);
                Object lookupKey = cache.nodeFactory.newLookupKey(key);
                Node<?, ?> node = cache.data.get(lookupKey);
                if(node == null)
                    return OptionalLong.empty();
                long age = cache.expirationTicker().read() - node.getWriteTime();
                return age > cache.expiresAfterWriteNanos()
                        ? OptionalLong.empty()
                        : OptionalLong.of(unit.convert(age, TimeUnit.NANOSECONDS));
            }

            @Override
            public long getExpiresAfter(TimeUnit unit) {
                return unit.convert(cache.expiresAfterWriteNanos(), TimeUnit.NANOSECONDS);
            }

            @Override
            public void setExpiresAfter(long duration, TimeUnit unit) {
                Caffeine.requireArgument(duration >= 0);
                cache.setExpiresAfterWriteNanos(unit.toNanos(duration));
                cache.scheduleAfterWrite();
            }

            @Override
            public Map<K, V> oldest(int limit) {
                return cache.expireAfterWriteOrder(limit, transformer, true);
            }

            @Override
            public Map<K, V> youngest(int limit) {
                return cache.expireAfterWriteOrder(limit, transformer, false);
            }
        }

        final class BoundedVarExpiration implements VarExpiration<K, V> {
            @Override
            public OptionalLong getExpiresAfter(K key, TimeUnit unit) {
                Objects.requireNonNull(key);
                Objects.requireNonNull(unit);
                Object lookupKey = cache.nodeFactory.newLookupKey(key);
                Node<?, ?> node = cache.data.get(lookupKey);
                if(node == null)
                    return OptionalLong.empty();
                long duration = node.getVariableTime() - cache.expirationTicker().read();
                return duration <= 0
                        ? OptionalLong.empty()
                        : OptionalLong.of(unit.convert(duration, TimeUnit.NANOSECONDS));
            }

            @Override
            public void setExpiresAfter(K key, long duration, TimeUnit unit) {
                Objects.requireNonNull(key);
                Objects.requireNonNull(unit);
                Caffeine.requireArgument(duration >= 0);
                Object lookupKey = cache.nodeFactory.newLookupKey(key);
                Node<K, V> node = cache.data.get(lookupKey);
                if(node != null) {
                    long now;
                    long durationNanos = TimeUnit.NANOSECONDS.convert(duration, unit);
                    synchronized(node) {
                        now = cache.expirationTicker().read();
                        node.setVariableTime(now + Math.min(durationNanos, MAXIMUM_EXPIRY));
                    }
                    cache.afterRead(node, now, false);
                }
            }

            @Override
            public void put(K key, V value, long duration, TimeUnit unit) {
                this.put(key, value, duration, unit, false);
            }

            @Override
            public boolean putIfAbsent(K key, V value, long duration, TimeUnit unit) {
                V previous = this.put(key, value, duration, unit, true);
                return previous == null;
            }

            /**
             * 直接给定了存活时间的特殊put入口，将直接使用这个时间来构造Expire实现（即不用key和value来计算了）
             */
            V put(K key, V value, long duration, TimeUnit unit, boolean onlyIfAbsent) {
                Objects.requireNonNull(unit);
                Objects.requireNonNull(value);
                Caffeine.requireArgument(duration >= 0);
                Expiry<K, V> expiry = new Expiry<>() {
                    @Override
                    public long expireAfterCreate(K key, V value, long currentTime) {
                        return unit.toNanos(duration);
                    }

                    @Override
                    public long expireAfterUpdate(K key, V value, long currentTime, long currentDuration) {
                        return unit.toNanos(duration);
                    }

                    @Override
                    public long expireAfterRead(K key, V value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                };
                if(cache.isAsync) {
                    Expiry<K, V> asyncExpiry = (Expiry<K, V>)new Async.AsyncExpiry<>(expiry);
                    expiry = asyncExpiry;
                    V asyncValue = (V)CompletableFuture.completedFuture(value);
                    value = asyncValue;
                }
                return cache.put(key, value, expiry, true, onlyIfAbsent);
            }

            @Override
            public Map<K, V> oldest(int limit) {
                return cache.variableSnapshot(true, limit, transformer);
            }

            @Override
            public Map<K, V> youngest(int limit) {
                return cache.variableSnapshot(false, limit, transformer);
            }
        }

        final class BoundedRefreshAfterWrite implements Expiration<K, V> {
            @Override
            public OptionalLong ageOf(K key, TimeUnit unit) {
                Objects.requireNonNull(key);
                Objects.requireNonNull(unit);
                Object lookupKey = cache.nodeFactory.newLookupKey(key);
                Node<?, ?> node = cache.data.get(lookupKey);
                if(node == null)
                    return OptionalLong.empty();
                long age = cache.expirationTicker().read() - node.getWriteTime();
                return age > cache.refreshAfterWriteNanos()
                        ? OptionalLong.empty()
                        : OptionalLong.of(unit.convert(age, TimeUnit.NANOSECONDS));
            }

            @Override
            public long getExpiresAfter(TimeUnit unit) {
                return unit.convert(cache.refreshAfterWriteNanos(), TimeUnit.NANOSECONDS);
            }

            @Override
            public void setExpiresAfter(long duration, TimeUnit unit) {
                Caffeine.requireArgument(duration >= 0);
                cache.setRefreshAfterWriteNanos(unit.toNanos(duration));
                cache.scheduleAfterWrite();
            }

            @Override
            public Map<K, V> oldest(int limit) {
                return cache.expiresAfterWrite()
                        ? expireAfterWrite().get().oldest(limit)
                        : sortedByWriteTime(limit, true);
            }

            @Override
            public Map<K, V> youngest(int limit) {
                return cache.expiresAfterWrite()
                        ? expireAfterWrite().get().youngest(limit)
                        : sortedByWriteTime(limit, false);
            }

            /**
             * 保底算法，如果cache没有开启write过期，则只能遍历cache本身查看node的writeTime来返回了
             */
            Map<K, V> sortedByWriteTime(int limit, boolean ascending) {
                Comparator<Node<K, V>> comparator = Comparator.comparingLong(Node::getWriteTime);
                var iter = cache.data.values().stream().parallel().sorted(
                        ascending ? comparator : comparator.reversed()).limit(limit).iterator();
                return cache.fixedSnapshot(() -> iter, limit, transformer);
            }
        }
    }

    /* --------------- Loading Cache同步相关实现 --------------- */

    static final class BoundedLocalLoadingCache<K, V> extends BoundedLocalManualCache<K, V>
        implements LocalLoadingCache<K, V> {
        private static final long serialVersionUID = 1;

        final Function<K, V> mappingFunction;
        final Function<Iterable<? extends K>, Map<K, V>> bulkMappingFunction;

        BoundedLocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
            super(builder, loader);
            Objects.requireNonNull(loader);
            this.mappingFunction = LocalLoadingCache.newMappingFunction(loader);
            this.bulkMappingFunction = LocalLoadingCache.newBulkMappingFunction(loader);
        }

        @Override
        public CacheLoader<? super K, V> cacheLoader() {
            return cache.cacheLoader;
        }

        @Override
        public Function<K, V> mappingFunction() {
            return mappingFunction;
        }

        @Override
        public @Nullable Function<Iterable<? extends K>, Map<K, V>> bulkMappingFunction() {
            return bulkMappingFunction;
        }

        private void readObject(ObjectInputStream stream) throws InvalidObjectException {
            throw new InvalidObjectException("Proxy required");
        }

        @Override
        Object writeReplace() {
            SerializationProxy<K, V> proxy = (SerializationProxy<K, V>)super.writeReplace();
            if(cache.refreshAfterWrite())
                proxy.refreshAfterWriteNanos = cache.refreshAfterWriteNanos();
            proxy.loader = cache.cacheLoader;
            return proxy;
        }
    }

    /* --------------- Async Cache异步相关实现 --------------- */

    static final class BoundedLocalAsyncCache<K, V> implements LocalAsyncCache<K, V>, Serializable {
        private static final long serialVersionUID = 1;

        final BoundedLocalCache<K, CompletableFuture<V>> cache;
        final boolean isWeighted;

        ConcurrentMap<K, CompletableFuture<V>> mapView;
        CacheView<K, V> cacheView;
        Policy<K, V> policy;

        BoundedLocalAsyncCache(Caffeine<K, V> builder) {
            this.cache = (BoundedLocalCache<K, CompletableFuture<V>>)LocalCacheFactory
                    .newBoundedLocalCache(builder, null, true);
            this.isWeighted = builder.isWeighted();
        }

        @Override
        public BoundedLocalCache<K, CompletableFuture<V>> cache() {
            return this.cache;
        }

        @Override
        public ConcurrentMap<K, CompletableFuture<V>> asMap() {
            return this.mapView == null ? this.mapView = new AsyncAsMapView<>(this) : this.mapView;
        }

        @Override
        public Cache<K, V> synchronous() {
            return this.cacheView == null ? this.cacheView = new CacheView<>(this) : this.cacheView;
        }

        @Override
        public Policy<K, V> policy() {
            if(this.policy == null) {
                BoundedLocalCache<K, V> castCache = (BoundedLocalCache<K, V>)this.cache;
                Function<CompletableFuture<V>, V> transformer = Async::getIfReady;
                Function<V, V> castTransformer = (Function<V, V>)transformer;
                this.policy = new BoundedPolicy<>(castCache, castTransformer, this.isWeighted);
            }
            return this.policy;
        }

        private void readObject(ObjectInputStream stream) throws InvalidObjectException {
            throw new InvalidObjectException("Proxy required");
        }

        Object writeReplace() {
            SerializationProxy<K, V> proxy = makeSerializationProxy(this.cache, this.isWeighted);
            if(this.cache.refreshAfterWrite())
                proxy.refreshAfterWriteNanos = this.cache.refreshAfterWriteNanos();
            proxy.async = true;
            return proxy;
        }
    }

    /* --------------- Async Loading Cache异步相关实现 --------------- */

    static final class BoundedLocalAsyncLoadingCache<K, V> extends LocalAsyncLoadingCache<K, V> implements Serializable {
        private static final long serialVersionUID = 1;

        final BoundedLocalCache<K, CompletableFuture<V>> cache;
        final boolean isWeighted;

        ConcurrentMap<K, CompletableFuture<V>> mapView;
        Policy<K, V> policy;

        BoundedLocalAsyncLoadingCache(Caffeine<K, V> builder, AsyncCacheLoader<? super K, V> loader) {
            super(loader);
            this.isWeighted = builder.isWeighted();
            this.cache = (BoundedLocalCache<K, CompletableFuture<V>>)LocalCacheFactory
                    .newBoundedLocalCache(builder, new AsyncLoader<>(loader, builder), true);
        }

        @Override
        public BoundedLocalCache<K, CompletableFuture<V>> cache() {
            return this.cache;
        }

        @Override
        public ConcurrentMap<K, CompletableFuture<V>> asMap() {
            return this.mapView == null ? this.mapView = new AsyncAsMapView<>(this) : this.mapView;
        }

        @Override
        public Policy<K, V> policy() {
            if(policy == null) {
                BoundedLocalCache<K, V> castCache = (BoundedLocalCache<K, V>)this.cache;
                Function<CompletableFuture<V>, V> transformer = Async::getIfReady;
                Function<V, V> castTransformer = (Function<V, V>)transformer;
                this.policy = new BoundedPolicy<>(castCache, castTransformer, this.isWeighted);
            }
            return this.policy;
        }

        private void readObject(ObjectInputStream stream) throws InvalidObjectException {
            throw new InvalidObjectException("Proxy required");
        }

        Object writeReplace() {
            SerializationProxy<K, V> proxy = makeSerializationProxy(this.cache, this.isWeighted);
            if(this.cache.refreshAfterWrite())
                proxy.refreshAfterWriteNanos = this.cache.refreshAfterWriteNanos();
            proxy.loader = this.loader;
            proxy.async = true;
            return proxy;
        }

        static final class AsyncLoader<K, V> implements CacheLoader<K, V> {
            final AsyncCacheLoader<? super K, V> loader;
            final Executor executor;

            AsyncLoader(AsyncCacheLoader<? super K, V> loader, Caffeine<?, ?> builder) {
                this.loader = Objects.requireNonNull(loader);
                this.executor = Objects.requireNonNull(builder.getExecutor());
            }

            @Override
            public V load(K key) {
                V newValue = (V)this.loader.asyncLoad(key, this.executor);
                return newValue;
            }

            @Override
            public V reload(K key, V oldValue) {
                V newValue = (V)this.loader.asyncReload(key, oldValue, this.executor);
                return newValue;
            }

            @Override
            public CompletableFuture<V> asyncReload(K key, V oldValue, Executor executor) {
                return this.loader.asyncReload(key, oldValue, executor);
            }
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
