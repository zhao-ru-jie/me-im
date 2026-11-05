package com.github.im.gateway.transport;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一条连接「是否正在因上行积压而暂停读取」的共享状态。
 *
 * <p><b>为什么需要这个类</b>：{@code setAutoRead(false)} 之后这条连接<b>读不到任何入站字节</b>，
 * 包括客户端回的心跳 {@code pong}。而 {@link HeartbeatHandler} 判死连接的唯一依据就是
 * 「有没有读到上行帧」——于是它会把「服务端自己主动不读」误判成「客户端已死」，
 * 在 90s 心跳窗口到期后把一条其实健康的连接踢下线。
 *
 * <p>这个类把「暂停读」这个事实暴露给心跳层，语义上把判定从
 * <b>「没收到帧 ⇒ 客户端死了」</b> 修正为
 * <b>「没收到帧 <i>且我们确实在读</i> ⇒ 客户端死了」</b>。
 *
 * <p><b>谁会改它</b>：入站 handler 在 {@link SerialExecutor#onBackpressure} 的两个回调里
 * 调 {@link #markPaused()} / {@link #markResumed()}。
 * <b>谁会听它</b>：{@link HeartbeatHandler} 通过 {@link #listen} 注册，
 * 在暂停时撤掉心跳判定、并启动背压看门狗（暂停过久仍要回收，否则会留下单向死连接）。
 *
 * <p><b>线程模型</b>：{@code markPaused} 多在 event loop 上被调用（{@code offer} 来自
 * {@code channelRead0}），{@code markResumed} 在虚拟线程的 drain 循环里被调用。
 * 状态本身是原子的；回调的线程安全由注册方 {@link HeartbeatHandler} 自己负责
 * （它会把动作切回 event loop）。
 *
 * @author zhaorujie
 * @see SerialExecutor#onBackpressure
 * @since 1.0.4
 */
public final class BackpressureState {

    /** 当前是否处于「暂停读取」状态 */
    private final AtomicBoolean paused = new AtomicBoolean();

    /** 进入暂停的时刻（{@link System#nanoTime()}）；只在 {@code paused} 为 true 时有意义 */
    private volatile long pausedSinceNanos;

    private volatile Runnable onPause = () -> { };
    private volatile Runnable onResume = () -> { };

    /**
     * 注册暂停/恢复的观察者。
     *
     * <p>必须在任何 {@link #markPaused()} 之前调用（实际是在 {@code handlerAdded} 里注册），
     * 否则错过的那次状态变化不会补发——心跳层只关心「当前是否暂停」，错过也不会误判。
     */
    public void listen(Runnable onPause, Runnable onResume) {
        this.onPause = onPause == null ? () -> { } : onPause;
        this.onResume = onResume == null ? () -> { } : onResume;
    }

    /** 进入暂停读取；重复调用只生效一次（以第一次的时间戳为准） */
    public void markPaused() {
        // 先写时间戳再翻标志位：读方看到 paused=true 时一定读得到有效的时间戳
        long now = System.nanoTime();
        pausedSinceNanos = now;
        if (paused.compareAndSet(false, true)) {
            fire(onPause);
        }
    }

    /** 恢复读取；重复调用只生效一次 */
    public void markResumed() {
        if (paused.compareAndSet(true, false)) {
            fire(onResume);
        }
    }

    /** 当前是否暂停读取（热路径会频繁调用，只做一次 volatile 读） */
    public boolean isPaused() {
        return paused.get();
    }

    /** 已经暂停了多久（毫秒）；未暂停时返回 0 */
    public long pausedForMillis() {
        if (!paused.get()) {
            return 0L;
        }
        return Math.max(0L, (System.nanoTime() - pausedSinceNanos) / 1_000_000L);
    }

    /** 回调异常不能影响背压本身（否则会挡住恢复读取，连接彻底卡死） */
    private void fire(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException ignored) {
            // 观察者只是心跳层的旁路逻辑，失败就退化成「暂停期间照常判定心跳」
        }
    }
}
