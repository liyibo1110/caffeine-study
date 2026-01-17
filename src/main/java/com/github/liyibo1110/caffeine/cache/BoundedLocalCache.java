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
import java.util.concurrent.ThreadLocalRandom;
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
