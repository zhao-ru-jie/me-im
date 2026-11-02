package com.github.im.serverboot;

import com.github.im.server.group.GroupJoinStore;
import com.github.im.server.group.GroupMemberRepository;
import com.github.im.server.group.GroupRepository;
import com.github.im.server.message.MessageRepository;
import com.github.im.server.relation.FriendRequestRepository;
import com.github.im.server.relation.RelationRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.mockito.Mockito.mock;

/**
 * 起上下文时用的<b>存储替身</b>。
 *
 * <p>为什么需要它：{@code im.storage} 只剩 jdbc / mongodb 两种形态，两者都会在启动阶段
 * 真的去连一台外部数据库（MySQL 建连接池、MongoDB 先 ping 一次）。而本模块里那几个
 * 端到端用例（健康端点、装配形态）验证的是<b>装配与协议</b>，不该被「本机有没有 Mongo」
 * 绑架——CI 上没有数据库时它们会从「通过」变成「跳过」，最后没人再跑。
 *
 * <p>所以这些用例统一用 {@code im.storage=none}：两个 {@code @ConditionalOnProperty}
 * 都不命中，任何存储客户端都不会被创建，端口由这里的替身提供。
 *
 * <p>用 Mockito 而不是手写空实现：端口方法会随业务增长（{@code MessageRepository} 已经十几个），
 * 手写空实现的维护成本是「加一个方法就要来改一遍测试」，而这里只关心 Bean 存在。
 */
@TestConfiguration
public class TestStorageConfig {

    @Bean
    public MessageRepository messageRepository() {
        return mock(MessageRepository.class);
    }

    @Bean
    public GroupRepository groupRepository() {
        return mock(GroupRepository.class);
    }

    @Bean
    public GroupMemberRepository groupMemberRepository() {
        return mock(GroupMemberRepository.class);
    }

    @Bean
    public RelationRepository relationRepository() {
        return mock(RelationRepository.class);
    }

    @Bean
    public FriendRequestRepository friendRequestRepository() {
        return mock(FriendRequestRepository.class);
    }

    /** 入群申请 / 邀请码：jdbc 与 mongodb 两种形态各自提供，测试里用替身 */
    @Bean
    public GroupJoinStore groupJoinStore() {
        return mock(GroupJoinStore.class);
    }
}
