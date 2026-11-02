package com.github.im.storage.mongodb;

import com.github.im.server.group.GroupInvite;
import com.github.im.server.group.GroupJoinRequest;
import com.github.im.server.group.GroupJoinStore;
import com.github.im.storage.mongodb.entity.GroupInviteEntity;
import com.github.im.storage.mongodb.entity.GroupJoinRequestEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB 版入群申请与邀请码存储（{@code im.storage=mongodb}）。
 *
 * <pre>
 * im_group_join_request   _id = groupId:applicantId，一条文档 = 一条申请
 * im_group_invite         _id = code，一条文档 = 一个邀请码
 * </pre>
 *
 * <h2>过期与限量的实现口径</h2>
 *
 * <ul>
 *   <li>申请单的 TTL → 查询条件 {@code createdAt >= now - requestTtl}，过期文档只是不返回；
 *       不建 TTL 索引，因为「过期」的判定口径属于业务（改大 ttl 时不该把数据已经删没了）；</li>
 *   <li>邀请码的过期 → {@code expiresAt} 字段；{@code expiresAt=0} 的长期码用
 *       {@code inviteTtl} 兜底，长期码也该有个上限；</li>
 *   <li>次数自增的原子性 → 带条件的 {@code $inc}（见 {@link #useInvite}）。</li>
 * </ul>
 *
 * @author zhaorujie
 * @since 1.0.0
 */
public class MongoGroupJoinStore implements GroupJoinStore {

    private static final Logger log = LoggerFactory.getLogger(MongoGroupJoinStore.class);

    private final MongoTemplate mongo;
    private final Duration requestTtl;
    private final Duration inviteTtl;

    public MongoGroupJoinStore(MongoTemplate mongo, Duration requestTtl, Duration inviteTtl) {
        this.mongo = mongo;
        this.requestTtl = positiveOr(requestTtl, Duration.ofDays(7));
        this.inviteTtl = positiveOr(inviteTtl, Duration.ofDays(30));
    }

    // ==== 入群申请 ====

    /**
     * 覆盖写入一条申请。
     *
     * <p>{@code $setOnInsert} 保住 {@code createdAt}：重新申请不该把这个人从
     * 「等得最久」挪到队尾（与 Redis 版「保留首次申请时间」的规则一致）。
     */
    @Override
    public void saveRequest(GroupJoinRequest request) {
        if (request == null || request.groupId() == null || request.applicantId() == null) {
            return;
        }
        String id = GroupJoinRequestEntity.idOf(request.groupId(), request.applicantId());
        Query query = Query.query(Criteria.where("_id").is(id));
        Update update = new Update()
                .set("groupId", request.groupId())
                .set("applicantId", request.applicantId())
                .set("status", request.status())
                .set("message", request.message())
                .set("updatedAt", request.updatedAt())
                .setOnInsert("createdAt", request.createdAt());
        mongo.upsert(query, update, GroupJoinRequestEntity.class);
    }

    @Override
    public GroupJoinRequest findRequest(String groupId, String applicantId) {
        if (groupId == null || applicantId == null) {
            return null;
        }
        GroupJoinRequestEntity entity =
                mongo.findById(GroupJoinRequestEntity.idOf(groupId, applicantId), GroupJoinRequestEntity.class);
        return entity == null ? null : toRequest(entity);
    }

    @Override
    public List<GroupJoinRequest> pendingRequests(String groupId) {
        if (groupId == null) {
            return List.of();
        }
        long oldest = System.currentTimeMillis() - requestTtl.toMillis();
        Query query = Query.query(Criteria.where("groupId").is(groupId)
                        .and("status").is(GroupJoinRequest.STATUS_PENDING)
                        .and("createdAt").gte(oldest))
                .with(Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by(Sort.Direction.ASC, "applicantId")));
        List<GroupJoinRequestEntity> rows = mongo.find(query, GroupJoinRequestEntity.class);
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
        mongo.remove(Query.query(Criteria.where("_id").is(GroupJoinRequestEntity.idOf(groupId, applicantId))),
                GroupJoinRequestEntity.class);
    }

    // ==== 邀请码 ====

    @Override
    public void saveInvite(GroupInvite invite) {
        if (invite == null || invite.code() == null || invite.groupId() == null) {
            return;
        }
        Query query = Query.query(Criteria.where("_id").is(invite.code()));
        Update update = new Update()
                .set("groupId", invite.groupId())
                .set("inviterId", invite.inviterId() == null ? "" : invite.inviterId())
                .set("createdAt", invite.createdAt())
                .set("expiresAt", invite.expiresAt())
                .set("maxUses", invite.maxUses())
                .set("uses", invite.uses())
                // 剩余次数是派生字段：maxUses=0 记 -1（不限），否则 maxUses - uses
                .set("remaining", invite.maxUses() == 0 ? -1 : Math.max(invite.maxUses() - invite.uses(), 0))
                .set("approvalRequired", invite.approvalRequired());
        mongo.upsert(query, update, GroupInviteEntity.class);
    }

    @Override
    public GroupInvite findInvite(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        GroupInviteEntity entity = mongo.findById(code, GroupInviteEntity.class);
        return entity == null ? null : toInvite(entity);
    }

    @Override
    public List<GroupInvite> invitesOf(String groupId) {
        if (groupId == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        // 文档不会像 Redis key 那样自己消失：惰性清理（只删「显式过期」的，长期码交给兜底 ttl 判定）
        long removed = mongo.remove(
                Query.query(Criteria.where("groupId").is(groupId).and("expiresAt").gt(0L).lte(now)),
                GroupInviteEntity.class).getDeletedCount();
        if (removed > 0) {
            log.debug("[group] 清理过期邀请码 {} 个 group={}", removed, groupId);
        }
        long fallbackOldest = now - inviteTtl.toMillis();
        Criteria usable = new Criteria().orOperator(
                Criteria.where("expiresAt").gt(now),
                new Criteria().andOperator(
                        Criteria.where("expiresAt").is(0L),
                        Criteria.where("createdAt").gte(fallbackOldest)));
        Query query = Query.query(new Criteria().andOperator(Criteria.where("groupId").is(groupId), usable))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<GroupInviteEntity> rows = mongo.find(query, GroupInviteEntity.class);
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
        Criteria criteria = Criteria.where("_id").is(code);
        if (groupId != null) {
            // 只有码所属的群能吊销它，避免「知道码就能删别人的」
            criteria = criteria.and("groupId").is(groupId);
        }
        mongo.remove(Query.query(criteria), GroupInviteEntity.class);
    }

    /**
     * 原子取用一次邀请码。
     *
     * <p>把「未过期」「未超次数」两个条件写进 <b>query</b> 而不是先在内存里读出来判断：
     * MongoDB 的单文档更新是原子的，{@code modifiedCount=1} 才说明这一次真的占到了名额。
     * 若走「find 出来判一下再 save」，{@code maxUses=1} 的链接在并发下会被用两次。
     */
    @Override
    public InviteUse useInvite(String code, long now) {
        if (code == null || code.isBlank()) {
            return InviteUse.of(InviteUse.Status.NOT_FOUND, null);
        }
        Criteria notExpired = new Criteria().orOperator(
                Criteria.where("expiresAt").is(0L),
                Criteria.where("expiresAt").gt(now));
        long modified;
        try {
            // 分两次条件更新，而不是一次 $expr 比较两个字段：
            // 第一次只命中「不限次数」（remaining = -1），第二次命中「还有剩余」（remaining > 0）。
            // 每次都是单文档原子更新，剩余次数与 uses 在同一个 $inc 里一起动，不会出现两者不一致。
            long unlimited = mongo.updateFirst(
                    Query.query(new Criteria().andOperator(
                            Criteria.where("_id").is(code),
                            Criteria.where("remaining").is(-1),
                            notExpired)),
                    new Update().inc("uses", 1), GroupInviteEntity.class).getModifiedCount();
            if (unlimited > 0) {
                return InviteUse.of(InviteUse.Status.OK, findInvite(code));
            }
            modified = mongo.updateFirst(
                    Query.query(new Criteria().andOperator(
                            Criteria.where("_id").is(code),
                            Criteria.where("remaining").gt(0),
                            notExpired)),
                    new Update().inc("uses", 1).inc("remaining", -1),
                    GroupInviteEntity.class).getModifiedCount();
        } catch (RuntimeException e) {
            // 存储抖动时不要给出「可用」：宁可让调用方看到失败，也不要让限量链接多放人进来
            log.warn("[group] 取用邀请码失败 code={} err={}", code, e.getMessage());
            return InviteUse.of(InviteUse.Status.NOT_FOUND, null);
        }
        if (modified > 0) {
            return InviteUse.of(InviteUse.Status.OK, findInvite(code));
        }
        GroupInvite current = findInvite(code);
        if (current == null) {
            return InviteUse.of(InviteUse.Status.NOT_FOUND, null);
        }
        if (current.expired(now) || expiredByFallback(current, now)) {
            return InviteUse.of(InviteUse.Status.EXPIRED, null);
        }
        // 走到这里只可能是「并发下别人先占掉了最后一次」
        return InviteUse.of(InviteUse.Status.EXHAUSTED, null);
    }

    // ==== 内部 ====

    private boolean expiredByFallback(GroupInvite invite, long now) {
        return invite.expiresAt() == 0L && now - invite.createdAt() >= inviteTtl.toMillis();
    }

    private static GroupJoinRequest toRequest(GroupJoinRequestEntity entity) {
        return new GroupJoinRequest(entity.getGroupId(), entity.getApplicantId(), entity.getStatus(),
                entity.getMessage() == null ? "" : entity.getMessage(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static GroupInvite toInvite(GroupInviteEntity entity) {
        return new GroupInvite(entity.getCode(), entity.getGroupId(), entity.getInviterId(),
                entity.getCreatedAt(), entity.getExpiresAt(), entity.getMaxUses(), entity.getUses(),
                entity.isApprovalRequired());
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
