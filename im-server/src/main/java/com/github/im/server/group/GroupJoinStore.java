package com.github.im.server.group;

import java.util.List;

/**
 * 入群申请与邀请码的存储端口。
 *
 * <h2>它和其它端口一样跟着 {@code im.storage} 切介质</h2>
 *
 * <p>{@link GroupRepository} / {@link GroupMemberRepository} 存的是<b>领域真相</b>（这个群叫什么、
 * 谁是成员），换 MySQL 或 Mongo 都不改变语义。本端口也属于这一类：待审申请与邀请码决定
 * <b>「谁能进这个群」</b>，必须有备份、能查历史、跨重启不变，因此实现落在业务库
 * （{@code im.storage=jdbc} → {@code JdbcGroupJoinStore}，{@code =mongodb} → {@code MongoGroupJoinStore}）。
 *
 * <h2>为什么当初放过 Redis、后来又搬回来</h2>
 *
 * <p>它们的生命周期确实短（默认 7 天），看起来和离线队列、水位一样「过期即无意义」。
 * 但「谁能进这个群」不是可以重建的加速数据：Redis 被误清或主从切换丢写之后，
 * 没有任何地方能回答「这条申请批过没有、这张链接被用过几次」。
 * 省下的那个清理任务，其实是把风险从「运维」挪到了「无法恢复」。
 *
 * <p>过期语义不靠物理删除实现：申请按 {@code createdAt} + TTL 判定，邀请码按
 * {@code expiresAt} 判定，读过期的行只是不返回——库里留着还可以审计。
 *
 * <h2>申请：按群正查</h2>
 *
 * <p>审批人关心的是「这个群有哪些人在等」，所以正查是群维度；申请人查自己的状态时手里也有
 * {@code groupId}（他刚刚申请过），因此不需要反向索引——少一张表，也少一次双写不一致的机会。
 *
 * <h2>邀请码：码是唯一的入口</h2>
 *
 * <p>扫码的人手里只有码，所以 {@link #findInvite} / {@link #useInvite} 必须能<b>只凭码</b>
 * 一次定位。{@link #invitesOf} 是给管理员看「我这个群放出去了哪些链接」的辅助索引。
 *
 * @author zhaorujie
 * @since 1.0.0
 */
public interface GroupJoinStore {

    // ==== 入群申请 ====

    /** 覆盖写入一条申请（按 groupId + applicantId 唯一定位）：重复申请不会堆出多条 */
    void saveRequest(GroupJoinRequest request);

    /** @return 不存在返回 {@code null} */
    GroupJoinRequest findRequest(String groupId, String applicantId);

    /** 该群的待审申请，按 {@code createdAt} 升序（先来先审） */
    List<GroupJoinRequest> pendingRequests(String groupId);

    /** 删除一条申请（撤回 / 审批完成后清理）；不存在时静默成功 */
    void deleteRequest(String groupId, String applicantId);

    // ==== 邀请码 ====

    /** 写入（或覆盖）一个邀请码；过期时间由 {@code invite.expiresAt()} 决定 */
    void saveInvite(GroupInvite invite);

    /** @return 不存在或已被吊销返回 {@code null} */
    GroupInvite findInvite(String code);

    /** 该群当前仍有效的邀请码（已过期的不返回） */
    List<GroupInvite> invitesOf(String groupId);

    /** 吊销：码立即失效，并从群的索引里摘掉 */
    void revokeInvite(String groupId, String code);

    /**
     * 原子取用一次邀请码：校验有效期与次数上限，成功时把 {@code uses} 加一。
     *
     * <p><b>必须原子</b>：{@code maxUses=1} 的链接在并发扫码下若走「读-判-写」，
     * 两个请求都会看到 {@code uses=0} 而双双通过，限量就形同虚设。
     */
    InviteUse useInvite(String code, long now);

    /**
     * 取用结果。
     *
     * @param status {@link Status#OK} 时 {@code invite} 为已自增后的最新形态
     */
    record InviteUse(Status status, GroupInvite invite) {

        public enum Status {
            /** 校验通过，次数已自增 */
            OK,
            /** 码不存在或已被吊销 */
            NOT_FOUND,
            /** 已过期 */
            EXPIRED,
            /** 次数用尽 */
            EXHAUSTED
        }

        public static InviteUse of(Status status, GroupInvite invite) {
            return new InviteUse(status, invite);
        }

        public boolean ok() {
            return status == Status.OK;
        }
    }
}
