package com.github.im.storage.mongodb.entity;

import com.github.im.storage.mongodb.MongoCollections;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * {@code im_group_invite} 的持久化对象（一条文档 = 一个邀请码）。
 *
 * <p>{@code _id} 就是邀请码本身：扫码的人手里只有码，这是唯一的入口。
 *
 * <p>{@code uses} 用 {@code $inc} 配合条件更新（{@code uses < maxUses} 与
 * {@code expiresAt} 一起写进 query），单文档更新的原子性保证限量在并发下成立——
 * 这与 MySQL 形态的行级 {@code UPDATE ... WHERE} 是同一件事。
 *
 * @author zhaorujie
 * @since 1.0.0
 */
@Document(collection = MongoCollections.GROUP_INVITE)
@CompoundIndex(name = "idx_invite_group", def = "{'groupId': 1, 'createdAt': -1}")
public class GroupInviteEntity {

    @MongoId
    private String code;

    private String groupId;

    private String inviterId;

    private long createdAt;

    /** 0 = 长期有效（由 {@code im.group.invite-ttl} 兜底判定） */
    private long expiresAt;

    /** 0 = 不限次数 */
    private int maxUses;

    private int uses;

    /**
     * 剩余可用次数：{@code -1} = 不限，{@code 0} = 已用尽。
     *
     * <p>为什么不直接在 query 里写 {@code uses < maxUses}：那是「两个字段比较」，
     * 在 MQL 里只能用 {@code $expr}，而 {@code $expr} 会绕开索引、也不便于离线排查。
     * 把差额落成一个字段后，限量判断退化成 {@code remaining != 0} 加一次 {@code $inc}，
     * 单文档更新的原子性照样保证 {@code maxUses=1} 的链接不会被用两次。
     */
    private int remaining;

    /** true = 用这个码进来还要管理员审批 */
    private boolean approvalRequired;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getInviterId() {
        return inviterId;
    }

    public void setInviterId(String inviterId) {
        this.inviterId = inviterId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public void setMaxUses(int maxUses) {
        this.maxUses = maxUses;
    }

    public int getUses() {
        return uses;
    }

    public void setUses(int uses) {
        this.uses = uses;
    }

    public int getRemaining() {
        return remaining;
    }

    public void setRemaining(int remaining) {
        this.remaining = remaining;
    }

    public boolean isApprovalRequired() {
        return approvalRequired;
    }

    public void setApprovalRequired(boolean approvalRequired) {
        this.approvalRequired = approvalRequired;
    }
}
