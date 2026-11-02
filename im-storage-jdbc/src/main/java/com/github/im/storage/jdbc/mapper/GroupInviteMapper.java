package com.github.im.storage.jdbc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.im.storage.jdbc.entity.GroupInviteEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * {@code im_group_invite} 的 Mapper。
 *
 * @author zhaorujie
 * @since 1.0.0
 */
@Mapper
public interface GroupInviteMapper extends BaseMapper<GroupInviteEntity> {

    /** 写入或覆盖一个邀请码（{@code uses} 随覆盖一起重置，与 Redis 的 HSET 语义一致） */
    @Insert("""
            INSERT INTO im_group_invite
                (code, group_id, inviter_id, created_at, expires_at, max_uses, uses, approval_required)
            VALUES
                (#{code}, #{groupId}, #{inviterId}, #{createdAt}, #{expiresAt}, #{maxUses}, #{uses}, #{approvalRequired})
            ON DUPLICATE KEY UPDATE
                group_id = VALUES(group_id),
                inviter_id = VALUES(inviter_id),
                expires_at = VALUES(expires_at),
                max_uses = VALUES(max_uses),
                uses = VALUES(uses),
                approval_required = VALUES(approval_required)
            """)
    int upsert(GroupInviteEntity entity);

    /**
     * 原子取用一次：校验有效期与次数上限，成功时 {@code uses + 1}。
     *
     * <p><b>为什么一条 UPDATE 就够</b>：MySQL 的行锁会把同一条码上的并发 UPDATE 串行化，
     * 而 {@code WHERE uses < max_uses} 是在<b>持有行锁之后</b>才求值的快照判断——
     * 所以 {@code maxUses=1} 的链接在并发下只有一个请求能让受影响行数为 1，
     * 其余请求看到的版本已经 {@code uses=1}，直接不匹配。
     *
     * <p>这与 Redis Lua 版承诺的是同一件事：限量必须原子，不能「读-判-写」。
     *
     * @return 1 = 取用成功；0 = 不存在 / 已过期 / 次数用尽（由调用方再查一次区分）
     */
    @Update("""
            UPDATE im_group_invite
               SET uses = uses + 1
             WHERE code = #{code}
               AND (max_uses = 0 OR uses < max_uses)
               AND (expires_at = 0 OR expires_at > #{now})
            """)
    int tryUse(@Param("code") String code, @Param("now") long now);

    /**
     * 惰性清理某群已过期的码。
     *
     * <p>与 Redis 相比这是 JDBC 形态多出来的一件事：Redis 的 key 自己会过期，表不会。
     * 这里放在「列出邀请码」的读路径上顺手清，不额外引入定时任务——
     * 清理任务是最容易没人维护的组件，而「看不到的过期码」本身没人关心。
     */
    @Delete("""
            DELETE FROM im_group_invite
             WHERE group_id = #{groupId}
               AND expires_at > 0
               AND expires_at <= #{now}
            """)
    int deleteExpired(@Param("groupId") String groupId, @Param("now") long now);
}
