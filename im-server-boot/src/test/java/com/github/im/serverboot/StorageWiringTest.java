package com.github.im.serverboot;

import com.github.im.server.group.GroupJoinStore;
import com.github.im.server.group.GroupMemberRepository;
import com.github.im.server.group.GroupRepository;
import com.github.im.server.message.MessageRepository;
import com.github.im.server.relation.FriendRequestRepository;
import com.github.im.server.relation.RelationRepository;
import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证「classpath 上有某套存储依赖」不等于「就要用它的连接」。
 *
 * <h2>这条性质比它看起来重要</h2>
 *
 * <p>本进程的 classpath 上同时有 MyBatis/Hikari 与 Spring Data MongoDB，两者的自动装配
 * 都是「有就生效」。一旦漏排某套自动装配，后果不是启动失败，而是<b>多出一个没人用的连接</b>：
 * Hikari 会因为找不到 DSN 直接启动失败（好查），Mongo 客户端则会安静地连不上
 * {@code localhost:27017}，再经 Actuator 聚合把健康状态拉成 DOWN——网关据此把实例摘掉，
 * 表现是「服务活着但没人给它发消息」。
 *
 * <h2>为什么用 im.storage=none</h2>
 *
 * <p>Redis 形态的存储实现已移除，剩下两种形态都会在启动阶段真的连外部数据库。
 * 这个用例断言的是<b>缺席</b>（其它介质不建连接），所以给它一个两者都不命中的取值，
 * 端口由 {@link TestStorageConfig} 的替身提供——既不依赖外部数据库，也照样能验证
 * 「没被选中的介质一个 Bean 都不会产生」。
 */
@SpringBootTest(classes = {ImServerApplication.class, TestStorageConfig.class}, properties = {
        // 两个 @ConditionalOnProperty 都不命中：任何存储客户端都不该被创建
        "im.storage=none",
        "spring.grpc.server.port=19291",
        "im.node.grpc-port=19291",
        "server.port=0",
        "im.nacos.enabled=false",
        "spring.grpc.server.health.schedule.delay=100ms",
        "spring.grpc.server.health.schedule.period=200ms"
})
class StorageWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("未选中任何介质时不创建 MySQL 连接池与 Mongo 客户端")
    void noMediumFootprintWhenNoneSelected() {
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty(),
                "jdbc 形态未选中却建了 DataSource：im-storage-jdbc 在 classpath 上不等于要用它");
        assertTrue(context.getBeansOfType(MongoClient.class).isEmpty(),
                "mongodb 形态未选中却建了 MongoClient：它会安静地连不上 localhost:27017，把健康状态拖成 DOWN");
        assertTrue(context.getBeansOfType(MongoTemplate.class).isEmpty(),
                "mongodb 形态未选中却建了 MongoTemplate（同上，MongoAutoConfiguration 应已在 yml 里排除）");
    }

    @Test
    @DisplayName("每个存储端口恰好一个 Bean（不存在「两套实现同时上场」）")
    void oneBeanPerPort() {
        assertEquals(1, context.getBeansOfType(MessageRepository.class).size());
        assertEquals(1, context.getBeansOfType(GroupRepository.class).size());
        assertEquals(1, context.getBeansOfType(GroupMemberRepository.class).size());
        assertEquals(1, context.getBeansOfType(RelationRepository.class).size());
        assertEquals(1, context.getBeansOfType(FriendRequestRepository.class).size());
        assertEquals(1, context.getBeansOfType(GroupJoinStore.class).size());
    }
}
