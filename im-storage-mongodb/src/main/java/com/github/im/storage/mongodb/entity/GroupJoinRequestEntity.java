package com.github.im.storage.mongodb.entity;

import com.github.im.storage.mongodb.MongoCollections;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * {@code im_group_join_request} 的持久化对象（一条文档 = 一条入群申请）。
 *
 * <p>{@code _id} 是 {@code groupId:applicantId}：这条记录天然被「群 + 申请人」唯一确定，
 * 主键即语义，重复申请就是一次覆盖写（{@code $set} 消息与状态，{@code $setOnInsert} 首次申请时间）。
 *
 * @author zhaorujie
 * @since 1.0.0
 */
@Document(collection = MongoCollections.GROUP_JOIN_REQUEST)
@CompoundIndex(name = "idx_join_req_pending", def = "{'groupId': 1, 'status': 1, 'createdAt': 1}")
public class GroupJoinRequestEntity {

    /** {@code groupId + ':' + applicantId} */
    @MongoId
    private String id;

    private String groupId;

    private String applicantId;

    /** PENDING / APPROVED / REJECTED */
    private String status;

    private String message;

    /** 首次申请时间：重新申请不刷新 */
    private long createdAt;

    private long updatedAt;

    public static String idOf(String groupId, String applicantId) {
        return groupId + ':' + applicantId;
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

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
