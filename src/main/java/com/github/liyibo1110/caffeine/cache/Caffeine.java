package com.github.liyibo1110.caffeine.cache;

import com.github.liyibo1110.caffeine.cache.stats.ConcurrentStatsCounter;
import com.github.liyibo1110.caffeine.cache.stats.StatsCounter;
import org.checkerframework.checker.index.qual.NonNegative;

import java.io.Serializable;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 主要组件的builder类，以及static工具（注意Caffeine本身就是Builder，不要再里面寻找Builder了）
 * @author liyibo
 * @date 2026-01-05 15:19
 */
public final class Caffeine<K, V> {
    static final Logger logger = Logger.getLogger(Caffeine.class.getName());

    static final Supplier<StatsCounter> ENABLED_STATS_COUNTER_SUPPLIER = ConcurrentStatsCounter::new;

    enum Strength { WEAK, SOFT }
    static final int UNSET_INT = -1;

    static final int DEFAULT_INITIAL_CAPACITY = 16;
    static final int DEFAULT_EXPIRATION_NANOS = 0;
    static final int DEFAULT_REFRESH_NANOS = 0;

    boolean strictParsing = true;

    long maximumSize = UNSET_INT;
    long maximumWeight = UNSET_INT;
    int initialCapacity = UNSET_INT;

    long expireAfterWriteNanos = UNSET_INT;
    long expireAfterAccessNanos = UNSET_INT;
    long refreshAfterWriteNanos = UNSET_INT;

    RemovalListener<? super K, ? super V> evictionListener;
    RemovalListener<? super K, ? super V> removalListener;
    Supplier<StatsCounter> statsCounterSupplier;
    CacheWriter<? super K, ? super V> writer;
    Weigher<? super K, ? super V> weigher;
    Expiry<? super K, ? super V> expiry;
    Scheduler scheduler;
    Executor executor;
    Ticker ticker;

    Strength keyStrength;
    Strength valueStrength;

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
    static void requireArgument(boolean expression, String template, Object... args) {
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
    static void requireState(boolean expression, String template, Object... args) {
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
     * 从CaffeineSpec实例中生成Caffeine实例
     */
    public static Caffeine<Object, Object> from(CaffeineSpec spec) {
        Caffeine<Object, Object> builder = spec.toBuilder();
        builder.strictParsing = false;
        return builder;
    }

    /**
     * 从配置字符串中生成Caffeine实例
     */
    public static Caffeine<Object, Object> from(String spec) {
        return from(CaffeineSpec.parse(spec));
    }

    public Caffeine<K, V> initialCapacity(int initialCapacity) {
        requireState(this.initialCapacity == UNSET_INT,
                "initial capacity was already set to %s", this.initialCapacity);
        requireArgument(initialCapacity >= 0);
        this.initialCapacity = initialCapacity;
        return this;
    }

    boolean hasInitialCapacity() {
        return (initialCapacity != UNSET_INT);
    }

    int getInitialCapacity() {
        return hasInitialCapacity() ? this.initialCapacity : DEFAULT_INITIAL_CAPACITY;
    }

    public Caffeine<K, V> executor(Executor executor) {
        requireState(this.executor == null, "executor was already set to %s", this.executor);
        this.executor = Objects.requireNonNull(executor);
        return this;
    }

    Executor getExecutor() {
        return (this.executor == null) ? ForkJoinPool.commonPool() : this.executor;
    }

    public Caffeine<K, V> scheduler(Scheduler scheduler) {
        requireState(this.scheduler == null, "scheduler was already set to %s", this.scheduler);
        this.scheduler = Objects.requireNonNull(scheduler);
        return this;
    }

    Scheduler getScheduler() {
        if(this.scheduler == null || this.scheduler == Scheduler.disabledScheduler())
            return Scheduler.disabledScheduler();
        else if(this.scheduler == Scheduler.systemScheduler())
            return this.scheduler;
        return Scheduler.guardedScheduler(this.scheduler);
    }

    public Caffeine<K, V> maximumSize(long maximumSize) {
        requireState(this.maximumSize == UNSET_INT,
                "maximum size was already set to %s", this.maximumSize);
        requireState(this.maximumWeight == UNSET_INT,
                "maximum weight was already set to %s", this.maximumWeight);
        requireState(this.weigher == null, "maximum size can not be combined with weigher");
        requireArgument(maximumSize >= 0, "maximum size must not be negative");
        this.maximumSize = maximumSize;
        return this;
    }

    public Caffeine<K, V> maximumWeight(@NonNegative long maximumWeight) {
        requireState(this.maximumWeight == UNSET_INT,
                "maximum weight was already set to %s", this.maximumWeight);
        requireState(this.maximumSize == UNSET_INT,
                "maximum size was already set to %s", this.maximumSize);
        requireArgument(maximumWeight >= 0, "maximum weight must not be negative");
        this.maximumWeight = maximumWeight;
        return this;
    }

    public <K1 extends K, V1 extends V> Caffeine<K1, V1> weigher(Weigher<? super K1, ? super V1> weigher) {
        Objects.requireNonNull(weigher);
        requireState(this.weigher == null, "weigher was already set to %s", this.weigher);
        requireState(!this.strictParsing || this.maximumSize == UNSET_INT,
                "weigher can not be combined with maximum size");
        Caffeine<K1, V1> self = (Caffeine<K1, V1>)this;
        self.weigher = weigher;
        return self;
    }

    boolean evicts() {
        return this.getMaximum() != UNSET_INT;
    }

    boolean isWeighted() {
        return this.weigher != null;
    }

    long getMaximum() {
        return isWeighted() ? this.maximumWeight : this.maximumSize;
    }

    <K1 extends K, V1 extends V> Weigher<K1, V1> getWeigher(boolean isAsync) {
        Weigher<K1, V1> delegate = (this.weigher == null) || (this.weigher == Weigher.singleonWeigher())
                ? Weigher.singleonWeigher()
                : Weigher.boundedWeigher((Weigher<K1, V1>)this.weigher);
        return isAsync ? (Weigher<K1, V1>)new Async.AsyncWeigher(delegate) : delegate;
    }

    public Caffeine<K, V> weakKeys() {
        requireState(this.keyStrength == null, "Key strength was already set to %s", keyStrength);
        requireState(this.writer == null, "Weak keys may not be used with CacheWriter");

        this.keyStrength = Strength.WEAK;
        return this;
    }

    boolean isStrongKeys() {
        return this.keyStrength == null;
    }

    public Caffeine<K, V> weakValues() {
        requireState(this.valueStrength == null, "Value strength was already set to %s", this.valueStrength);
        this.valueStrength = Strength.WEAK;
        return this;
    }

    boolean isStrongValues() {
        return this.valueStrength == null;
    }

    boolean isWeakValues() {
        return this.valueStrength == Strength.WEAK;
    }

    public Caffeine<K, V> softValues() {
        requireState(this.valueStrength == null, "Value strength was already set to %s", this.valueStrength);
        this.valueStrength = Strength.SOFT;
        return this;
    }

    public Caffeine<K, V> expireAfterWrite(Duration duration) {
        return this.expireAfterWrite(saturatedToNanos(duration), TimeUnit.NANOSECONDS);
    }

    public Caffeine<K, V> expireAfterWrite(long duration, TimeUnit unit) {
        requireState(this.expireAfterWriteNanos == UNSET_INT,
                "expireAfterWrite was already set to %s ns", this.expireAfterWriteNanos);
        requireState(this.expiry == null, "expireAfterWrite may not be used with variable expiration");
        requireArgument(duration >= 0, "duration cannot be negative: %s %s", duration, unit);
        this.expireAfterWriteNanos = unit.toNanos(duration);
        return this;
    }

    long getExpiresAfterWriteNanos() {
        return this.expiresAfterWrite() ? this.expireAfterWriteNanos : DEFAULT_EXPIRATION_NANOS;
    }

    boolean expiresAfterWrite() {
        return this.expireAfterWriteNanos != UNSET_INT;
    }

    public Caffeine<K, V> expireAfterAccess(Duration duration) {
        return this.expireAfterAccess(saturatedToNanos(duration), TimeUnit.NANOSECONDS);
    }

    public Caffeine<K, V> expireAfterAccess(long duration, TimeUnit unit) {
        requireState(this.expireAfterAccessNanos == UNSET_INT,
                "expireAfterAccess was already set to %s ns", this.expireAfterAccessNanos);
        requireState(this.expiry == null, "expireAfterAccess may not be used with variable expiration");
        requireArgument(duration >= 0, "duration cannot be negative: %s %s", duration, unit);
        this.expireAfterAccessNanos = unit.toNanos(duration);
        return this;
    }

    long getExpiresAfterAccessNanos() {
        return this.expiresAfterAccess() ? this.expireAfterAccessNanos : DEFAULT_EXPIRATION_NANOS;
    }

    boolean expiresAfterAccess() {
        return this.expireAfterAccessNanos != UNSET_INT;
    }

    public <K1 extends K, V1 extends V> Caffeine<K1, V1> expireAfter(Expiry<? super K1, ? super V1> expiry) {
        Objects.requireNonNull(expiry);
        requireState(this.expiry == null, "Expiry was already set to %s", this.expiry);
        requireState(this.expireAfterAccessNanos == UNSET_INT,
                "Expiry may not be used with expiresAfterAccess");
        requireState(this.expireAfterWriteNanos == UNSET_INT,
                "Expiry may not be used with expiresAfterWrite");


        Caffeine<K1, V1> self = (Caffeine<K1, V1>)this;
        self.expiry = expiry;
        return self;
    }

    boolean expiresVariable() {
        return this.expiry != null;
    }

    Expiry<K, V> getExpiry(boolean isAsync) {
        return isAsync && (this.expiry != null)
                ? (Expiry<K, V>)new Async.AsyncExpiry<>(this.expiry)
                : (Expiry<K, V>)this.expiry;
    }

    public Caffeine<K, V> refreshAfterWrite(Duration duration) {
        return this.refreshAfterWrite(saturatedToNanos(duration), TimeUnit.NANOSECONDS);
    }

    public Caffeine<K, V> refreshAfterWrite(long duration, TimeUnit unit) {
        Objects.requireNonNull(unit);
        requireState(this.refreshAfterWriteNanos == UNSET_INT,
                "refreshAfterWriteNanos was already set to %s ns", this.refreshAfterWriteNanos);
        requireArgument(duration > 0, "duration must be positive: %s %s", duration, unit);
        this.refreshAfterWriteNanos = unit.toNanos(duration);
        return this;
    }

    long getRefreshAfterWriteNanos() {
        return this.refreshAfterWrite() ? this.refreshAfterWriteNanos : DEFAULT_REFRESH_NANOS;
    }

    boolean refreshAfterWrite() {
        return this.refreshAfterWriteNanos != UNSET_INT;
    }

    /**
     * 设置Ticker实例
     * @param ticker
     * @return
     */
    public Caffeine<K, V> ticker(Ticker ticker) {
        // 限制了只能被设置1次
        requireState(this.ticker == null, "Ticker was already set to %s", this.ticker);
        this.ticker = Objects.requireNonNull(ticker);
        return this;
    }

    Ticker getTicker() {
        boolean useTicker = this.expiresVariable() || this.expiresAfterAccess()
                || this.expiresAfterWrite() || this.refreshAfterWrite() || this.isRecordingStats();
        return useTicker
                ? (this.ticker == null) ? Ticker.systemTicker() : this.ticker
                : Ticker.disabledTicker();
    }

    public <K1 extends K, V1 extends V> Caffeine<K1, V1> evictionListener(
            RemovalListener<? super K1, ? super V1> evictionListener) {
        requireState(this.evictionListener == null,
                "eviction listener was already set to %s", this.evictionListener);

        Caffeine<K1, V1> self = (Caffeine<K1, V1>)this;
        self.evictionListener = Objects.requireNonNull(evictionListener);
        return self;
    }

    public <K1 extends K, V1 extends V> Caffeine<K1, V1> removalListener(
            RemovalListener<? super K1, ? super V1> removalListener) {
        requireState(this.removalListener == null,
                "removal listener was already set to %s", this.removalListener);

        Caffeine<K1, V1> self = (Caffeine<K1, V1>)this;
        self.removalListener = Objects.requireNonNull(removalListener);
        return self;
    }

    <K1 extends K, V1 extends V> RemovalListener<K1, V1> getRemovalListener(boolean async) {
        RemovalListener<K1, V1> castedListener = (RemovalListener<K1, V1>)this.removalListener;
        return async && (castedListener != null)
                ? new Async.AsyncRemovalListener(castedListener, getExecutor())
                : castedListener;
    }

    @Deprecated
    public <K1 extends K, V1 extends V> Caffeine<K1, V1> writer(CacheWriter<? super K1, ? super V1> writer) {
        requireState(this.writer == null, "Writer was already set to %s", this.writer);
        requireState(this.keyStrength == null, "Weak keys may not be used with CacheWriter");
        requireState(this.evictionListener == null, "Eviction listener may not be used with CacheWriter");

        @SuppressWarnings("unchecked")
        Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
        self.writer = Objects.requireNonNull(writer);
        return self;
    }

    <K1 extends K, V1 extends V> CacheWriter<K1, V1> getCacheWriter(boolean async) {
        CacheWriter<K1, V1> castedWriter;
        if(this.evictionListener == null) {
            castedWriter = (CacheWriter<K1, V1>)this.writer;
        }else {
            castedWriter = new CacheWriterAdapter<>(this.evictionListener, async);
        }
        return (castedWriter == null) ? CacheWriter.disabledWriter() : castedWriter;
    }

    public Caffeine<K, V> recordStats() {
        requireState(this.statsCounterSupplier == null, "Statistics recording was already set");
        this.statsCounterSupplier = ENABLED_STATS_COUNTER_SUPPLIER;
        return this;
    }

    public Caffeine<K, V> recordStats(Supplier<? extends StatsCounter> statsCounterSupplier) {
        requireState(this.statsCounterSupplier == null, "Statistics recording was already set");
        Objects.requireNonNull(statsCounterSupplier);
        this.statsCounterSupplier = () -> StatsCounter.guardedStatsCounter(statsCounterSupplier.get());
        return this;
    }

    boolean isRecordingStats() {
        return this.statsCounterSupplier != null;
    }

    Supplier<StatsCounter> getStatsCounterSupplier() {
        return this.statsCounterSupplier == null
                ? StatsCounter::disabledStatsCounter
                : this.statsCounterSupplier;
    }

    boolean isBounded() {
        return this.maximumSize != UNSET_INT
                || this.maximumWeight != UNSET_INT
                || this.expireAfterAccessNanos != UNSET_INT
                || this.expireAfterWriteNanos != UNSET_INT
                || this.expiry != null
                || this.keyStrength != null
                || this.valueStrength != null;
    }


    /* 各种build方法 */

    public <K1 extends K, V1 extends V> Cache<K1, V1> build() {
        this.requireWeightWithWeigher();
        this.requireNonLoadingCache();

        Caffeine<K1, V1> self = (Caffeine<K1, V1>)this;
        return this.isBounded()
                ? new BoundedLocalCache.BoundedLocalManualCache<>(self)
                : new UnboundedLocalCache.UnboundedLocalManualCache<>(self);
    }

    public <K1 extends K, V1 extends V> LoadingCache<K1, V1> build(CacheLoader<? super K1, V1> loader) {
        this.requireWeightWithWeigher();

        Caffeine<K1, V1> self = (Caffeine<K1, V1>)this;
        return this.isBounded() || this.refreshAfterWrite()
                ? new BoundedLocalCache.BoundedLocalLoadingCache<>(self, loader)
                : new UnboundedLocalCache.UnboundedLocalLoadingCache<>(self, loader);
    }

    public <K1 extends K, V1 extends V> AsyncCache<K1, V1> buildAsync() {
        requireState(this.valueStrength == null, "Weak or soft values can not be combined with AsyncCache");
        requireState(this.writer == null, "CacheWriter can not be combined with AsyncCache");
        requireState(isStrongKeys() || this.evictionListener == null, "Weak keys cannot be combined eviction listener and with AsyncLoadingCache");
        this.requireWeightWithWeigher();
        this.requireNonLoadingCache();

        Caffeine<K1, V1> self = (Caffeine<K1, V1>)this;
        return this.isBounded()
                ? new BoundedLocalCache.BoundedLocalAsyncCache<>(self)
                : new UnboundedLocalCache.UnboundedLocalAsyncCache<>(self);
    }

    public <K1 extends K, V1 extends V> AsyncLoadingCache<K1, V1> buildAsync(CacheLoader<? super K1, V1> loader) {
        return this.buildAsync((AsyncCacheLoader<? super K1, V1>)loader);
    }

    public <K1 extends K, V1 extends V> AsyncLoadingCache<K1, V1> buildAsync(AsyncCacheLoader<? super K1, V1> loader) {
        requireState(isStrongValues(),"Weak or soft values cannot be combined with AsyncLoadingCache");
        requireState(this.writer == null, "CacheWriter cannot be combined with AsyncLoadingCache");
        requireState(isStrongKeys() || this.evictionListener == null, "Weak keys cannot be combined eviction listener and with AsyncLoadingCache");
        this.requireWeightWithWeigher();
        Objects.requireNonNull(loader);

        Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
        return isBounded() || refreshAfterWrite()
                ? new BoundedLocalCache.BoundedLocalAsyncLoadingCache<>(self, loader)
                : new UnboundedLocalCache.UnboundedLocalAsyncLoadingCache<>(self, loader);
    }

    void requireNonLoadingCache() {
        this.requireState(this.refreshAfterWriteNanos == UNSET_INT, "refreshAfterWrite requires a LoadingCache");
    }

    void requireWeightWithWeigher() {
        if(this.weigher == null)
            requireState(this.maximumWeight == UNSET_INT, "maximumWeight requires weigher");
        else if (this.strictParsing)
            requireState(this.maximumWeight != UNSET_INT, "weigher requires maximumWeight");
        else if (this.maximumWeight == UNSET_INT)
            logger.log(Level.WARNING, "ignoring weigher specified without maximumWeight");
    }

    /**
     * 在不抛出异常或发生溢出的情况下，返回Duration对应的纳秒数
     * 会将异常转换成最大最小值返回
     */
    private static long saturatedToNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException tooBig) {
            return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder(75);
        s.append(getClass().getSimpleName()).append('{');
        int baseLength = s.length();
        if(this.initialCapacity != UNSET_INT)
            s.append("initialCapacity=").append(this.initialCapacity).append(", ");
        if(this.maximumSize != UNSET_INT)
            s.append("maximumSize=").append(this.maximumSize).append(", ");
        if(this.maximumWeight != UNSET_INT)
            s.append("maximumWeight=").append(this.maximumWeight).append(", ");
        if(this.expireAfterWriteNanos != UNSET_INT)
            s.append("expireAfterWrite=").append(this.expireAfterWriteNanos).append("ns, ");
        if(this.expireAfterAccessNanos != UNSET_INT)
            s.append("expireAfterAccess=").append(this.expireAfterAccessNanos).append("ns, ");
        if(this.expiry != null)
            s.append("expiry, ");
        if(this.refreshAfterWriteNanos != UNSET_INT)
            s.append("refreshAfterWriteNanos=").append(this.refreshAfterWriteNanos).append("ns, ");
        if(this.keyStrength != null)
            s.append("keyStrength=").append(this.keyStrength.toString().toLowerCase(Locale.US)).append(", ");
        if(this.valueStrength != null)
            s.append("valueStrength=").append(this.valueStrength.toString().toLowerCase(Locale.US)).append(", ");
        if(this.evictionListener != null)
            s.append("evictionListener, ");
        if(this.removalListener != null)
            s.append("removalListener, ");
        if(this.writer != null)
            s.append("writer, ");

        if(s.length() > baseLength)
            s.deleteCharAt(s.length() - 2);

        return s.append('}').toString();
    }

    /**
     * RemovalListener -> CacheWriter
     */
    static final class CacheWriterAdapter<K, V> implements CacheWriter<K, V>, Serializable {
        private static final long serialVersionUID = 1;

        final RemovalListener<? super K, ? super  V> delegate;
        final boolean isAsync;

        CacheWriterAdapter(RemovalListener<? super K, ? super  V> delegate, boolean isAsync) {
            this.delegate = delegate;
            this.isAsync = isAsync;
        }

        @Override
        public void write(K key, V value) {}

        @Override
        public void delete(K key, V value, RemovalCause cause) {
            if(cause.wasEvicted()) {
                try {
                    if(this.isAsync && value != null) {
                        CompletableFuture<V> future = (CompletableFuture<V>)value;
                        value = Async.getIfReady(future);
                    }
                    this.delegate.onRemoval(key, value, cause);
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "Exception thrown by eviction listener", t);
                }
            }
        }
    }
}
