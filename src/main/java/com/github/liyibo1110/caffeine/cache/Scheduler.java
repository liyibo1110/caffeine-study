package com.github.liyibo1110.caffeine.cache;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 调度器，作用是在给定的延迟时间后将任务提交给执行器（Executor）
 * @author liyibo
 * @date 2026-01-06 14:35
 */
@FunctionalInterface
public interface Scheduler {

    /**
     * 返回一个Future实例，调度器将在指定延迟后将command提交给指定的executor
     */
    Future<?> schedule(Executor executor, Runnable command, long delay, TimeUnit unit);

    static Scheduler disabledScheduler() {
        return DisabledScheduler.INSTANCE;
    }

    static Scheduler systemScheduler() {
        return SystemScheduler.isPresent() ? SystemScheduler.INSTANCE : disabledScheduler();
    }

    static Scheduler forScheduledExecutorService(ScheduledExecutorService service) {
        return new ExecutorServiceScheduler(service);
    }

    static Scheduler guardedScheduler(Scheduler scheduler) {
        return (scheduler instanceof GuardedScheduler) ? scheduler : new GuardedScheduler(scheduler);
    }
}

/**
 * 使用系统范围内的调度线程的调度器实现（即JDK自带的组件）
 * 在Java9或更高的版本中，可以通过使用CompletableFuture的delayedExecutor方法获取此调度器
 */
enum SystemScheduler implements Scheduler {
    INSTANCE;

    static final Method delayedExecutor = getDelayedExecutorMethod();

    @Override
    public Future<?> schedule(Executor executor, Runnable command,
                              long delay, TimeUnit unit) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(command);
        Objects.requireNonNull(unit);

        try {
            // 直接获取JDK内置的带延迟Executor
            Executor scheduler = (Executor)delayedExecutor.invoke(CompletableFuture.class, delay, unit, executor);
            return CompletableFuture.runAsync(command, scheduler);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 尝试获取CompletableFuture类的delayedExecutor方法实例（Java9才会有，此项目最低版本是Java8）
     */
    static Method getDelayedExecutorMethod() {
        try {
            return CompletableFuture.class.getMethod("delayedExecutor", long.class, TimeUnit.class, Executor.class);
        } catch (NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    static boolean isPresent() {
        return delayedExecutor != null;
    }
}

/**
 * 使用java.util.concurrent.ScheduledExecutorService的调度器实现（也是JDK自带的组件）
 */
final class ExecutorServiceScheduler implements Scheduler, Serializable {
    static final Logger logger = Logger.getLogger(ExecutorServiceScheduler.class.getName());
    static final long serialVersionUID = 1;

    final ScheduledExecutorService service;

    ExecutorServiceScheduler(ScheduledExecutorService service) {
        this.service = Objects.requireNonNull(service);
    }

    @Override
    public Future<?> schedule(Executor executor, Runnable command,
                              long delay, TimeUnit unit) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(command);
        Objects.requireNonNull(unit);

        if(this.service.isShutdown())   // 退化
            return DisabledFuture.INSTANCE;

        return this.service.schedule(() -> {
            try {
                executor.execute(command);
            } catch (Throwable t) {
                this.logger.log(Level.WARNING, "Exception thrown when submitting scheduled task", t);
                throw t;
            }
        }, delay, unit);
    }
}

final class GuardedScheduler implements Scheduler, Serializable {
    static final Logger logger = Logger.getLogger(GuardedScheduler.class.getName());
    static final long serialVersionUID = 1;
    final Scheduler delegate;

    GuardedScheduler(Scheduler delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public Future<?> schedule(Executor executor, Runnable command, long delay, TimeUnit unit) {
        try {
            Future<?> future = this.delegate.schedule(executor, command, delay, unit);
            return (future == null) ? DisabledFuture.INSTANCE : future;
        } catch (Throwable t) {
            this.logger.log(Level.WARNING, "Exception thrown by scheduler; discarded task", t);
            return DisabledFuture.INSTANCE;
        }
    }
}

enum DisabledScheduler implements Scheduler {
    INSTANCE;

    @Override
    public Future<?> schedule(Executor executor, Runnable command,
                              long delay, TimeUnit unit) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(command);
        Objects.requireNonNull(unit);
        return DisabledFuture.INSTANCE;
    }
}

enum DisabledFuture implements Future<Void> {
    INSTANCE;

    @Override
    public boolean isDone() {
        return true;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public Void get() throws InterruptedException, ExecutionException {
        return null;
    }

    @Override
    public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit);
        return null;
    }
}
