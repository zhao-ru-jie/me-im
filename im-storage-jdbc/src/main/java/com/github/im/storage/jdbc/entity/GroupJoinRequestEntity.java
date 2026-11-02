package com.github.im.storage.jdbc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code im_group_join_request} 的持久化对象。
 *
 * <p>主键是 {@code groupId:applicantId} 的字符串而不是自增 id：这条记录天然被
 * 「群 + 申请人」唯一确定，用复合语义做主键后「重复申请」就是一次覆盖写，
 * 不需要再查一次「他申请过没有」。
 *
 * @author zhaorujie
 * @since 1.0.0
 */
@TableName("im_group_join_request")
public class GroupJoinRequestEntity {

    /** {@code groupId + ':' + applicantId} */
    @TableId(type = IdType.INPUT)
    private String id;

    private String groupId;

    private String applicantId;

    /** PENDING / APPROVED / REJECTED */
    private String status;

    private String message;

    /** 首次申请时间：重新申请不刷新（与领域对象的语义一致） */
    private Long createdAt;

    private Long updatedAt;

    public static String idOf(String groupId, String applicantId) {
        return groupId + ":" + applicantId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getApplicantId() {
        return applicantId;
    }

    public void setApplicantId(String applicantId) {
        this.applicantId = applicantId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
