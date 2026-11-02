package com.github.im.storage.jdbc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.im.storage.jdbc.entity.GroupJoinRequestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code im_group_join_request} 的 Mapper。
 *
 * @author zhaorujie
 * @since 1.0.0
 */
@Mapper
public interface GroupJoinRequestMapper extends BaseMapper<GroupJoinRequestEntity> {

    /**
     * 覆盖写入一条申请。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 而不是「先查再决定 insert/update」：
     * 后者在两个管理员同时看到同一份待审列表时会出现「查到不存在 -> 双双 insert -> 一个撞主键」，
     * 而主键冲突的异常不该冒到审批接口上。
     *
     * <p>注意这里<b>不更新</b> {@code created_at}：它是「首次申请时间」，
     * 重新申请不该把这个人从「等得最久」挪到队尾。
     */
    @Insert("""
            INSERT INTO im_group_join_request
                (id, group_id, applicant_id, status, message, created_at, updated_at)
            VALUES
                (#{id}, #{groupId}, #{applicantId}, #{status}, #{message}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                status = VALUES(status),
                message = VALUES(message),
                updated_at = VALUES(updated_at)
            """)
    int upsert(GroupJoinRequestEntity entity);
}
