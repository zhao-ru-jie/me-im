package com.github.im.gateway.transport;

import com.github.im.gateway.metrics.GatewayMetrics;
import com.github.im.gateway.protocol.ClientFrames;
import com.github.im.gateway.session.NettyClientSession;
import com.github.im.gateway.session.SessionAttributes;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 连接级心跳：探测「客户端进程被杀 / 网络半开（NAT 超时、拔网线）」这类不会发 FIN 的死连接。
 *
 * <p>两段式（阈值来自 {@code im.gateway.idle.ping-after-ms} / {@code close-after-ms}，
 * 实际值由 {@code NettyGatewayServer} <b>按连接</b>叠加一个 [0, jitter-ms] 的随机偏移后传入，
 * 用来把「一批同时建立的连接」的心跳时刻摊开，避免周期性尖峰）：
 * <ol>
 *   <li><b>空闲达到随机化后的 ping 时刻</b>：下发应用层 {@code {"type":"ping"}}，并挂一个
 *       「close-after 之后仍无任何上行」的定时任务；</li>
 *   <li><b>定时任务到期</b>：仍没收到任何帧 → 判定死连接，关闭连接。
 *       关闭会触发 {@code channelInactive}，由 {@link SessionLifecycle} 清本地会话与 Redis 路由
 *       （用户转离线，消息落离线队列）。</li>
 * </ol>
 *
 * <p><b>为什么用 Netty 的 IdleStateHandler 而不是扫全表</b>：旧实现是 Spring 定时任务每 30s
 * 遍历所有连接比对时间戳，连接数上到十万级时每次扫描都要抢一次全局 map 的遍历开销，
 * 而且探测时机是「扫描批次」决定的，会有最多一个周期的抖动。现在每个连接由自己的 event loop
 * 计时，精确、无全局扫描、连接数增加不影响单次开销。
 *
 * <p><b>为什么用应用层 JSON ping 而不是 WebSocket 协议级 Ping 帧</b>：浏览器 JS 无法主动发送
 * 协议级 ping（API 不暴露），若只用协议级 ping，WS 客户端会被误判为死连接；而 TCP 根本没有
 * 协议级 ping。所以统一在业务帧层用 {@code {"type":"ping"}}，两种传输语义一致。
 *
 * <p><b>未认证连接</b>：握手/认证还没完成时客户端解析不了 ping，因此不发探测，
 * 第二次空闲直接断开（TCP 的 10s 认证窗口通常早就先把它关了）。
 *
 * <h2>与上行背压的关系（重要）</h2>
 *
 * <p>上行积压过高时 {@link SerialExecutor} 会 {@code setAutoRead(false)} 暂停读取，
 * 此时这条连接<b>读不到任何入站字节</b>，客户端回的 {@code pong} 会停在 socket 缓冲区里。
 * 若仍按原判据「没收到上行 ⇒ 客户端死了」处理，就会在 90s 后把一条完全健康的连接踢下线，
 * 并触发「过载 → 批量断连 → 集中重连 → 再次过载」的放大效应，恰好是背压想避免的活锁。
 *
 * <p>因此本类通过 {@link BackpressureState} 感知暂停状态，做两件事：
 * <ol>
 *   <li><b>暂停期间判定作废</b>：不发 ping、不挂 closeTask；已挂的 closeTask 到期也直接放弃
 *       （把 {@code pinged} 复位），避免「pong 躺在内核缓冲区里读不到」造成的误杀；</li>
 *   <li><b>改由背压看门狗计时</b>：暂停时长超过 {@code im.gateway.idle.backpressure-timeout-ms}
 *       就主动断开，并记为独立的断连原因 {@code backpressure-timeout}。
 *       这一步不能省——背压若永不恢复（下游彻底 hang），抑制心跳后就再没有机制回收这条连接，
 *       会留下「出站正常、入站永久卡死」的单向死连接，比直接断连更糟。</li>
 * </ol>
 *
 * @author zhaorujie
 * @since 1.0.0
 */
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    /** 背压超时断开时使用的关闭原因，与心跳超时区分，便于排障时一眼定位 */
    public static final String CLOSE_REASON_BACKPRESSURE = "backpressure-timeout";

    private final ClientFrames frames;
    private final GatewayMetrics metrics;
    /** ping 发出后等待应答的时长（= close-after-ms - ping-after-ms） */
    private final long closeDelayMs;
    /** 与入站 handler 共享的「是否正在暂停读取」状态；为 null 表示不做背压感知 */
    private final BackpressureState backpressure;
    /** 暂停读取最长允许多久；<=0 表示不限制（只抑制心跳，不做看门狗回收） */
    private final long backpressureTimeoutMs;

    /** 本轮是否已发过 ping；收到任意上行帧即复位 */
    private boolean pinged;
    /** 未认证连接连续空闲次数 */
    private int idleWithoutAuth;
    private ScheduledFuture<?> closeTask;
    /** 背压看门狗：暂停过久时主动回收连接 */
    private ScheduledFuture<?> pauseWatchdog;

    private ChannelHandlerContext ctx;

    public HeartbeatHandler(ClientFrames frames, GatewayMetrics metrics, long closeDelayMs,
                            BackpressureState backpressure, long backpressureTimeoutMs) {
        this.frames = frames;
        this.metrics = metrics;
        this.closeDelayMs = closeDelayMs;
        this.backpressure = backpressure;
        this.backpressureTimeoutMs = backpressureTimeoutMs;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        if (backpressure != null) {
            // 回调可能来自虚拟线程（恢复读取发生在 drain 循环里），统一切回 event loop 处理
            backpressure.listen(
                    () -> runOnEventLoop(this::onBackpressurePaused),
                    () -> runOnEventLoop(this::onBackpressureResumed));
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 任何上行帧（含业务层的 pong / msg.send）都算「活着」，取消待执行的关闭
        pinged = false;
        idleWithoutAuth = 0;
        cancelCloseTask();
        ctx.fireChannelRead(msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (!(evt instanceof IdleStateEvent)) {
            ctx.fireUserEventTriggered(evt);
            return;
        }
        NettyClientSession session = ctx.channel().attr(SessionAttributes.SESSION).get();
        if (session == null) {
            // 还没认证：客户端此时解析不了 ping，攒够两次空闲直接断，防止半开连接常驻
            if (++idleWithoutAuth >= 2) {
                log.debug("close unauthenticated idle connection {}", ctx.channel().id().asShortText());
                ctx.close();
            }
            return;
        }
        if (isBackpressured()) {
            // 暂停读取期间读不到 pong，发 ping 没有意义；判定与回收交给背压看门狗
            return;
        }
        if (pinged || closeTask != null) {
            // 已经探测过，等 closeTask 判定；这里不重复发 ping，避免死连接被刷屏
            return;
        }
        pinged = true;
        session.send(frames.ping());
        log.debug("heartbeat ping -> {}", session);
        // 定时关闭：到期前收到任何帧都会 cancelCloseTask
        closeTask = ctx.executor().schedule(() -> {
            // 先复位自身引用：否则非暂停分支返回后 pinged/closeTask 会永久非空，
            // userEventTriggered 里的「已探测过」判断会把后续心跳全挡掉
            closeTask = null;
            if (isBackpressured()) {
                // 本次窗口内没读到上行是「我们主动不读」造成的，不能据此判死客户端；
                // 放弃本轮判定，等恢复读取后重新计时（由背压看门狗负责兜底回收）
                pinged = false;
                log.debug("heartbeat timeout skipped while backpressured: {}", session);
                return;
            }
            if (pinged && ctx.channel().isActive()) {
                log.info("heartbeat timeout after {}ms, closing {}", closeDelayMs, session);
                metrics.sessionIdleClosed(1);
                session.close("heartbeat-timeout");
            }
        }, closeDelayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelCloseTask();
        cancelPauseWatchdog();
        ctx.fireChannelInactive();
    }

    /**
     * 进入暂停读取：撤销心跳判定，改为按背压时长计时。
     *
     * <p>只做「作废本轮 + 起看门狗」，不主动发任何帧——此时入站读不到，出站发给谁都没意义。
     */
    private void onBackpressurePaused() {
        cancelPauseWatchdog();
        cancelCloseTask();
        // closeTask 被撤销时 pinged 仍是 true，必须复位，否则恢复读取后第一次空闲会被「已探测过」挡掉
        pinged = false;
        if (backpressureTimeoutMs <= 0 || ctx == null || !ctx.channel().isActive()) {
            return;
        }
        pauseWatchdog = ctx.executor().schedule(() -> {
            pauseWatchdog = null;
            if (ctx == null || !ctx.channel().isActive() || !isBackpressured()) {
                return;
            }
            NettyClientSession session = ctx.channel().attr(SessionAttributes.SESSION).get();
            log.warn("backpressure lasted {}ms (limit {}ms, pending frames not drained), closing {}",
                    backpressure.pausedForMillis(), backpressureTimeoutMs,
                    session == null ? ctx.channel().id().asShortText() : session);
            metrics.backpressureTimeout();
            if (session != null) {
                session.close(CLOSE_REASON_BACKPRESSURE);
            } else {
                ctx.close();
            }
        }, backpressureTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 恢复读取：撤销背压看门狗，后续心跳按正常流程重新计时 */
    private void onBackpressureResumed() {
        cancelPauseWatchdog();
    }

    private boolean isBackpressured() {
        return backpressure != null && backpressure.isPaused();
    }

    private void cancelCloseTask() {
        if (closeTask != null) {
            closeTask.cancel(false);
            closeTask = null;
        }
    }

    private void cancelPauseWatchdog() {
        if (pauseWatchdog != null) {
            pauseWatchdog.cancel(false);
            pauseWatchdog = null;
        }
    }

    /** 背压回调可能来自虚拟线程：非 event loop 线程必须切回去，否则 pinged/closeTask 有可见性问题 */
    private void runOnEventLoop(Runnable task) {
        if (ctx == null) {
            return;
        }
        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            task.run();
        } else {
            executor.execute(task);
        }
    }
}
