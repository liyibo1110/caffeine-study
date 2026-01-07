package com.github.liyibo1110.caffeine.cache;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 重点性能优化组件 --- 防止执行过于频繁的定步调度器（组合了scheduler）
 * 在任何给定的时间内，只能调度1个任务，最早待处理的任务具有优先权
 * 如果延迟小于容忍阈值，则可能会增加延迟
 * @author liyibo
 * @date 2026-01-06 15:08
 */
final class Pacer {
    // 大概是1.07秒
    static final long TOLERANCE = Caffeine.ceilingPowerOfTwo(TimeUnit.SECONDS.toNanos(1));
    final Scheduler scheduler;

    /** 下一次可以触发任务的时间点（即当前时间不超过这个值时，任务需要等） */
    long nextFireTime;

    /** 要重点理解的字段，意味着任意时刻，只允许存在1个已调度任务 */
    @Nullable Future<?> future;

    Pacer(Scheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    /**
     * 附带降速的scheduler方法
     */
    public void schedule(Executor executor, Runnable command,
                         long now, long delay) {
        long scheduleAt = (now + delay);    // 调度的原始期望时间点
        if(this.future == null) {   // 防止delay很小的并发，造成死循环
            if(this.nextFireTime != 0L)
                return;
        }else if((this.nextFireTime - now) > 0L) {  // 有future了，说明之前有任务提交了，但还没开始执行
            if(this.maySkip(scheduleAt))    // 能不能插队，不能就退出
                return;
            this.future.cancel(false); // 新任务插队了
        }
        // 到这里才真的触发提交
        long actualDelay = this.calculateSchedule(now, delay, scheduleAt);
        this.future = this.scheduler.schedule(executor, command, actualDelay, TimeUnit.NANOSECONDS);
    }

    /**
     * 尝试取消调度任务的执行
     */
    public void cancel() {
        if(this.future != null) {
            this.future.cancel(false);
            this.nextFireTime = 0L;
            this.future = null;
        }
    }

    /**
     * 给定一个任务的预定执行时间，判断是否跳过不执行（接近但不超过点火时间的任务才可以执行）
     * 1、任务执行时间点 > 点火时间点（则跳过，因为晚了）
     * 2、任务执行时间点 < 点火时间点，但是差值在容忍时间之外（则跳过，因为还没到时候）
     * 所以只有在执行时间，很接近点火时间的任务才被执行
     */
    boolean maySkip(long scheduleAt) {
        long delta = scheduleAt - this.nextFireTime;
        return (delta >= 0L) || (-delta <= TOLERANCE);
    }

    /**
     * 返回任务要执行的真实延迟时间delay（防止频繁调度）
     */
    long calculateSchedule(long now, long delay, long scheduleAt) {
        if(delay <= TOLERANCE) {    // delay过小，则会给调大
            this.nextFireTime = (now + TOLERANCE);
            return TOLERANCE;
        }
        this.nextFireTime = scheduleAt; // delay足够大，则保持delay不变（注意这里设置了nextFireTime）
        return delay;
    }
}
