package com.github.im.storage.jdbc;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.im.server.group.GroupInvite;
import com.github.im.server.group.GroupJoinRequest;
import com.github.im.server.group.GroupJoinStore;
import com.github.im.storage.jdbc.entity.GroupInviteEntity;
import com.github.im.storage.jdbc.entity.GroupJoinRequestEntity;
import com.github.im.storage.jdbc.mapper.GroupInviteMapper;
import com.github.im.storage.jdbc.mapper.GroupJoinRequestMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 版入群申请与邀请码存储（{@code im.storage=jdbc}）。
 *
 * <pre>
 * im_group_join_request   id = groupId:applicantId，一行 = 一条申请（status 是状态机的当前态）
 * im_group_invite         code 为主键，一行 = 一个邀请码
 * </pre>
 *
 * <h2>为什么这一组状态该进业务库</h2>
 *
 * <p>待审申请和邀请码原先由 Redis 承载，理由是「短生命周期、过期即无意义」。但这两个东西
 * 决定的是<b>谁能进这个群</b>：Redis 单点挂掉或主从切换丢一段内存，后果不是「少一条缓存」，
 * 而是管理员审批过的申请状态消失、限量链接的次数被重置（{@code maxUses=1} 的链接又能多用一次）。
 * 相比之下「表里留几条过期行」的代价小得多——所以这里选择写库，用查询过滤 + 惰性删除
 * 代替 Redis 的 key 过期。
 *
 * <h2>与 Redis 实现的语义对应</h2>
 *
 * <ul>
 *   <li>Hash 的「整键 TTL」→ 申请的 {@code request_ttl} 变成查询条件 {@code created_at >= oldest}，
 *       过期行不会被返回（也不需要立刻删干净）；</li>
 *   <li>邀请码的 key TTL → {@code expires_at} 字段判断，{@code expires_at=0} 的长期码用
 *       {@code invite_ttl} 兜底，与 Redis 版「长期码也留个上限」的规则一致；</li>
 *   <li>Lua 的原子自增 → 一条带 {@code WHERE uses < max_uses} 的 UPDATE（行锁保证串行）。</li>
 * </ul>
 *
 * @author zhaorujie
 * @since 1.0.0
 */
public class JdbcGroupJoinStore implements GroupJoinStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcGroupJoinStore.class);

    private final GroupJoinRequestMapper requestMapper;
    private final GroupInviteMapper inviteMapper;
    /** 申请单的存活时长：过了这个时间没人处理就不再出现在审批列表里 */
    private final Duration requestTtl;
    /** 长期有效（{@code expiresAt=0}）的邀请码的兜底上限 */
    private final Duration inviteTtl;

    public JdbcGroupJoinStore(GroupJoinRequestMapper requestMapper, GroupInviteMapper inviteMapper,
                              Duration requestTtl, Duration inviteTtl) {
        this.requestMapper = requestMapper;
        this.inviteMapper = inviteMapper;
        this.requestTtl = positiveOr(requestTtl, Duration.ofDays(7));
        this.inviteTtl = positiveOr(inviteTtl, Duration.ofDays(30));
    }

    // ==== 入群申请 ====

    @Override
    public void saveRequest(GroupJoinRequest request) {
        if (request == null || request.groupId() == null || request.applicantId() == null) {
            return;
        }
        requestMapper.upsert(toEntity(request));
    }

    @Override
    public GroupJoinRequest findRequest(String groupId, String applicantId) {
        if (groupId == null || applicantId == null) {
            return null;
        }
        GroupJoinRequestEntity entity =
                requestMapper.selectById(GroupJoinRequestEntity.idOf(groupId, applicantId));
        return entity == null ? null : toRequest(entity);
    }

    /**
     * 该群的待审申请，先来先审。
     *
     * <p>{@code created_at >= oldest} 就是「TTL」：超过存活期的申请留在表里但不再返回，
     * 由 {@link #deleteRequest} 之外的路径自然老化，不需要定时任务。
     */
    @Override
    public List<GroupJoinRequest> pendingRequests(String groupId) {
        if (groupId == null) {
            return List.of();
        }
        long oldest = System.currentTimeMillis() - requestTtl.toMillis();
        List<GroupJoinRequestEntity> rows = requestMapper.selectList(
                Wrappers.<GroupJoinRequestEntity>lambdaQuery()
                        .eq(GroupJoinRequestEntity::getGroupId, groupId)
                        .eq(GroupJoinRequestEntity::getStatus, GroupJoinRequest.STATUS_PENDING)
                        .ge(GroupJoinRequestEntity::getCreatedAt, oldest)
                        .orderByAsc(GroupJoinRequestEntity::getCreatedAt)
                        .orderByAsc(GroupJoinRequestEntity::getApplicantId));
        List<GroupJoinRequest> result = new ArrayList<>(rows.size());
        for (GroupJoinRequestEntity row : rows) {
            result.add(toRequest(row));
        }
        return result;
    }

    @Override
    public void deleteRequest(String groupId, String applicantId) {
        if (groupId == null || applicantId == null) {
            return;
        }
        requestMapper.deleteById(GroupJoinRequestEntity.idOf(groupId, applicantId));
    }

    // ==== 邀请码 ====

    @Override
    public void saveInvite(GroupInvite invite) {
        if (invite == null || invite.code() == null || invite.groupId() == null) {
            return;
        }
        inviteMapper.upsert(toEntity(invite));
    }

    @Override
    public GroupInvite findInvite(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        GroupInviteEntity entity = inviteMapper.selectById(code);
        return entity == null ? null : toInvite(entity);
    }

    @Override
    public List<GroupInvite> invitesOf(String groupId) {
        if (groupId == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        // 惰性清理：表不会像 Redis key 那样自己消失
        int removed = inviteMapper.deleteExpired(groupId, now);
        if (removed > 0) {
            log.debug("[group] 清理过期邀请码 {} 个 group={}", removed, groupId);
        }
        long fallbackOldest = now - inviteTtl.toMillis();
        List<GroupInviteEntity> rows = inviteMapper.selectList(
                Wrappers.<GroupInviteEntity>lambdaQuery()
                        .eq(GroupInviteEntity::getGroupId, groupId)
                        // 显式过期的按 expires_at 判；长期码（expires_at=0）按兜底 TTL 判
                        .and(w -> w.gt(GroupInviteEntity::getExpiresAt, now)
                                .or(o -> o.eq(GroupInviteEntity::getExpiresAt, 0L)
                                        .ge(GroupInviteEntity::getCreatedAt, fallbackOldest)))
                        .orderByDesc(GroupInviteEntity::getCreatedAt));
        List<GroupInvite> result = new ArrayList<>(rows.size());
        for (GroupInviteEntity row : rows) {
            result.add(toInvite(row));
        }
        return result;
    }

    @Override
    public void revokeInvite(String groupId, String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        // 带上 groupId 条件：只有一个群能吊销自己放出去的码，避免「知道码就能删别人的」
        if (groupId == null) {
            inviteMapper.deleteById(code);
            return;
        }
        inviteMapper.delete(Wrappers.<GroupInviteEntity>lambdaQuery()
                .eq(GroupInviteEntity::getCode, code)
                .eq(GroupInviteEntity::getGroupId, groupId));
    }

    /**
     * 原子取用一次邀请码。
     *
     * <p>{@code tryUse} 的 {@code WHERE} 同时约束「未过期」与「未超次数」，
     * 受影响行数为 1 才说明这一次真的占到了名额；为 0 时再查一次表把原因分成
     * NOT_FOUND / EXPIRED / EXHAUSTED —— 分成三种而不是统一回「不可用」，
     * 是因为客户端要把「链接过期了」和「名额抢完了」展示成不同文案。
     */
    @Override
    public InviteUse useInvite(String code, long now) {
        if (code == null || code.isBlank()) {
            return InviteUse.of(InviteUse.Status.NOT_FOUND, null);
        }
        int affected;
        try {
            affected = inviteMapper.tryUse(code, now);
        } catch (RuntimeException e) {
            // 存储抖动时不要给出「可用」：宁可让调用方看到失败，也不要让限量链接多放人进来
            log.warn("[group] 取用邀请码失败 code={} err={}", code, e.getMessage());
            return InviteUse.of(InviteUse.Status.NOT_FOUND, null);
        }
        if (affected > 0) {
            return InviteUse.of(InviteUse.Status.OK, findInvite(code));
        }
        GroupInvite current = findInvite(code);
        if (current == null) {
            return InviteUse.of(InviteUse.Status.NOT_FOUND, null);
        }
        if (current.expired(now) || expiredByFallback(current, now)) {
            return InviteUse.of(InviteUse.Status.EXPIRED, null);
        }
        if (current.exhausted()) {
            return InviteUse.of(InviteUse.Status.EXHAUSTED, null);
        }
        // 走到这里说明是并发下的「别人先占掉了最后一次」，按次数用尽处理
        return InviteUse.of(InviteUse.Status.EXHAUSTED, null);
    }

    // ==== 内部 ====

    /** 长期码的兜底过期：与 Redis 版「expires_at=0 也给 inviteTtl」保持同一结论 */
    private boolean expiredByFallback(GroupInvite invite, long now) {
        return invite.expiresAt() == 0L && now - invite.createdAt() >= inviteTtl.toMillis();
    }

    private static GroupJoinRequestEntity toEntity(GroupJoinRequest request) {
        GroupJoinRequestEntity entity = new GroupJoinRequestEntity();
        entity.setId(GroupJoinRequestEntity.idOf(request.groupId(), request.applicantId()));
        entity.setGroupId(request.groupId());
        entity.setApplicantId(request.applicantId());
        entity.setStatus(request.status());
        entity.setMessage(request.message());
        entity.setCreatedAt(request.createdAt());
        entity.setUpdatedAt(request.updatedAt());
        return entity;
    }

    private static GroupJoinRequest toRequest(GroupJoinRequestEntity entity) {
        return new GroupJoinRequest(entity.getGroupId(), entity.getApplicantId(), entity.getStatus(),
                entity.getMessage() == null ? "" : entity.getMessage(),
                number(entity.getCreatedAt()), number(entity.getUpdatedAt()));
    }

    private static GroupInviteEntity toEntity(GroupInvite invite) {
        GroupInviteEntity entity = new GroupInviteEntity();
        entity.setCode(invite.code());
        entity.setGroupId(invite.groupId());
        entity.setInviterId(invite.inviterId());
        entity.setCreatedAt(invite.createdAt());
        entity.setExpiresAt(invite.expiresAt());
        entity.setMaxUses(invite.maxUses());
        entity.setUses(invite.uses());
        entity.setApprovalRequired(invite.approvalRequired() ? 1 : 0);
        return entity;
    }

    private static GroupInvite toInvite(GroupInviteEntity entity) {
        return new GroupInvite(entity.getCode(), entity.getGroupId(), entity.getInviterId(),
                number(entity.getCreatedAt()), number(entity.getExpiresAt()),
                (int) number(entity.getMaxUses()), (int) number(entity.getUses()),
                number(entity.getApprovalRequired()) != 0);
    }

    private static long number(Number value) {
        return value == null ? 0L : value.longValue();
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
