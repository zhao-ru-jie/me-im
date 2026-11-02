package com.github.im.storage.jdbc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code im_group_invite} 的持久化对象。
 *
 * <p>{@code uses} 是可被并发自增的计数（{@code UPDATE ... WHERE uses < max_uses}），
 * 这也是它必须是一张独立表、而不是塞进群的 JSON 字段里的原因：只有行级更新的
 * {@code WHERE} 条件能让「限量」在并发下成立。
 *
 * @author zhaorujie
 * @since 1.0.0
 */
@TableName("im_group_invite")
public class GroupInviteEntity {

    /** 邀请码本身就是主键：扫码的人手里只有码 */
    @TableId(type = IdType.INPUT)
    private String code;

    private String groupId;

    private String inviterId;

    private Long createdAt;

    /** 0 = 长期有效（由 {@code im.group.invite-ttl} 兜底判断） */
    private Long expiresAt;

    /** 0 = 不限次数 */
    private Integer maxUses;

    private Integer uses;

    /** 1 = 用这个码进来还要管理员审批 */
    private Integer approvalRequired;

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

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getMaxUses() {
        return maxUses;
    }

    public void setMaxUses(Integer maxUses) {
        this.maxUses = maxUses;
    }

    public Integer getUses() {
        return uses;
    }

    public void setUses(Integer uses) {
        this.uses = uses;
    }

    public Integer getApprovalRequired() {
        return approvalRequired;
    }

    public void setApprovalRequired(Integer approvalRequired) {
        this.approvalRequired = approvalRequired;
    }
}
