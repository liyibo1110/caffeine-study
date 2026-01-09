package com.github.liyibo1110.caffeine.cache.stats;

import com.github.liyibo1110.caffeine.cache.RemovalCause;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 对委托的StatsCounter实现进行代理的StatsCounter实现，负责吃掉异常并log输出
 * @author liyibo
 * @date 2026-01-06 10:24
 */
final class GuardedStatsCounter implements StatsCounter {
    static final Logger logger = Logger.getLogger(GuardedStatsCounter.class.getName());

    final StatsCounter delegate;

    GuardedStatsCounter(StatsCounter delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public void recordHits(int count) {
        try {
            this.delegate.recordHits(count);
        } catch (Throwable t) {
            this.logger.log(Level.WARNING, "Exception thrown by stats counter", t);
        }
    }

    @Override
    public void recordMisses(int count) {
        try {
            this.delegate.recordMisses(count);
        } catch (Throwable t) {
            this.logger.log(Level.WARNING, "Exception thrown by stats counter", t);
        }
    }

    @Override
    public void recordLoadSuccess(long loadTime) {
        try {
            this.delegate.recordLoadSuccess(loadTime);
        } catch (Throwable t) {
            this.logger.log(Level.WARNING, "Exception thrown by stats counter", t);
        }
    }

    @Override
    public void recordLoadFailure(long loadTime) {
        try {
            this.delegate.recordLoadFailure(loadTime);
        } catch (Throwable t) {
            this.logger.log(Level.WARNING, "Exception thrown by stats counter", t);
        }
    }

    @Override
    public void recordEviction() {
        try {
            this.delegate.recordEviction();
        } catch (Throwable t) {
            this.logger.log(Level.WARNING, "Exception thrown by stats counter", t);
        }
    }

    @Override
    public void recordEviction(int weight) {
        try {
            this.delegate.recordEviction(weight);
        } catch (Throwable t) {
            this.logger.log(Level.WARNING, "Exception thrown by stats counter", t);
        }
    }

    @Override
    public void recordEviction(int weight, RemovalCause cause) {
        try {
            this.delegate.recordEviction(weight, cause);
        } catch (Throwable t) {
            this.logger.log(Level.WARNING, "Exception thrown by stats counter", t);
        }
    }

    @Override
    public CacheStats snapshot() {
        try {
            return this.delegate.snapshot();
        } catch (Throwable t) {
            this.logger.log(Level.WARNING, "Exception thrown by stats counter", t);
            return CacheStats.empty();
        }
    }

    @Override
    public String toString() {
        return this.delegate.toString();
    }
}
