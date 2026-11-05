package com.github.im.gateway.transport;

import com.github.im.gateway.metrics.GatewayMetrics;
import com.github.im.gateway.protocol.ClientFrames;
import com.github.im.gateway.session.NettyClientSession;
import com.github.im.gateway.session.SessionAttributes;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 上行背压与心跳的交叉行为。
 *
 * <p>核心不变式：<b>暂停读取期间读不到 pong，因此不能用「没收到上行」判定客户端已死</b>；
 * 但暂停过久（下游长时间不消化）仍必须回收连接，只是断连原因要记成背压而不是心跳。
 */
class HeartbeatHandlerBackpressureTest {

    /** ping 与 close 的间隔（= close-after - ping-after），与线上默认一致 */
    private static final long CLOSE_DELAY_MS = 45_000L;

    private final ClientFrames frames = new ClientFrames();
    private final GatewayMetrics metrics = mock(GatewayMetrics.class);
    private final BackpressureState backpressure = new BackpressureState();

    /** 暂停期间不发 ping：读不到 pong，发了既浪费一次写，也会让日志误报「探测失败」 */
    @Test
    void pausedConnectionMustNotSendPing() {
        EmbeddedChannel channel = newChannel(30_000L);

        backpressure.markPaused();
        fireIdle(channel);
        advance(channel, 20, TimeUnit.SECONDS);

        assertThat(channel.<String>readOutbound()).as("暂停期间不应该发 ping").isNull();
        assertThat(channel.isOpen()).as("暂停 20s 未到背压上限，连接不应被关闭").isTrue();
        verify(metrics, never()).sessionIdleClosed(1);
        verify(metrics, never()).backpressureTimeout();
    }

    /** 已挂的心跳关闭任务在进入暂停后必须作废，否则 pong 躺在内核缓冲区里会造成误杀 */
    @Test
    void pendingCloseTaskMustBeDroppedWhenPauseStarts() {
        // 背压上限设得足够大：本用例只验证心跳判定作废，不希望看门狗介入
        EmbeddedChannel channel = newChannel(200_000L);

        fireIdle(channel);
        assertThat(channel.<String>readOutbound()).as("暂停前正常发 ping").isNotNull();

        backpressure.markPaused();
        advance(channel, CLOSE_DELAY_MS, TimeUnit.MILLISECONDS);

        assertThat(channel.isOpen()).as("暂停期间到期的心跳关闭任务不应关闭连接").isTrue();
        verify(metrics, never()).sessionIdleClosed(1);

        // 恢复读取后心跳必须能重新计时：这里验证 closeTask 被撤销时 pinged 已复位，
        // 否则 userEventTriggered 的「已探测过」判断会把后续心跳永久挡掉
        backpressure.markResumed();
        advance(channel, 1, TimeUnit.SECONDS);
        fireIdle(channel);
        assertThat(channel.<String>readOutbound()).as("恢复后应重新下发 ping").isNotNull();
    }

    /** 暂停过久（下游彻底不消化）必须回收，否则留下「出站正常、入站卡死」的单向死连接 */
    @Test
    void pauseLongerThanLimitClosesWithBackpressureReason() {
        EmbeddedChannel channel = newChannel(30_000L);

        backpressure.markPaused();
        advance(channel, 30, TimeUnit.SECONDS);

        assertThat(channel.isOpen()).as("背压超时应主动断开").isFalse();
        verify(metrics).backpressureTimeout();
        verify(metrics, never()).sessionIdleClosed(1);
    }

    /** 恢复读取要撤销看门狗：否则一次已经结束的背压会在一分钟后把健康连接踢掉 */
    @Test
    void resumeCancelsWatchdog() {
        EmbeddedChannel channel = newChannel(30_000L);

        backpressure.markPaused();
        advance(channel, 10, TimeUnit.SECONDS);
        backpressure.markResumed();
        advance(channel, 60, TimeUnit.SECONDS);

        assertThat(channel.isOpen()).as("背压已恢复，看门狗不应再断开连接").isTrue();
        verify(metrics, never()).backpressureTimeout();
    }

    /** 基线：没有背压参与时，心跳超时行为保持原样 */
    @Test
    void heartbeatStillClosesIdleConnectionWithoutBackpressure() {
        EmbeddedChannel channel = newChannel(30_000L);

        fireIdle(channel);
        assertThat(channel.<String>readOutbound()).isNotNull();
        advance(channel, CLOSE_DELAY_MS, TimeUnit.MILLISECONDS);

        assertThat(channel.isOpen()).as("没有上行应答时应按心跳超时断开").isFalse();
        verify(metrics).sessionIdleClosed(1);
        verify(metrics, never()).backpressureTimeout();
    }

    private EmbeddedChannel newChannel(long backpressureTimeoutMs) {
        HeartbeatHandler handler =
                new HeartbeatHandler(frames, metrics, CLOSE_DELAY_MS, backpressure, backpressureTimeoutMs);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        // 心跳 handler 从 Channel 属性取会话，非 null 才进入「已认证」分支
        channel.attr(SessionAttributes.SESSION)
                .set(NettyClientSession.tcp(channel).bind("alice", "mobile"));
        return channel;
    }

    private static void fireIdle(EmbeddedChannel channel) {
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
    }

    /** EmbeddedChannel 的定时任务只在推进虚拟时间后执行，不会真的 sleep */
    private static void advance(EmbeddedChannel channel, long duration, TimeUnit unit) {
        channel.advanceTimeBy(duration, unit);
        channel.runScheduledPendingTasks();
    }
}
