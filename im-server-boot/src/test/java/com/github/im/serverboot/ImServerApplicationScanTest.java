package com.github.im.serverboot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住 {@link ImServerApplication} 的组件扫描白名单。
 *
 * <p>为什么值得单独一个测试：{@code scanBasePackages} 一旦写了就不再按启动类所在包推导，
 * 而「漏列一个存储实现包」的后果不是编译错误，而是<b>运行时</b>某个形态下启动失败——
 * 例如只列 jdbc、却把 {@code im.storage} 配成 redis，报的是
 * 「MessageRepository required a bean that could not be found」，离真正原因很远。
 * 存储实现是插件（以后还可能再加一种介质），这里用注解断言把「新增介质必须同时登记」变成红灯。
 */
class ImServerApplicationScanTest {

    private static List<String> scannedPackages() {
        SpringBootApplication annotation = ImServerApplication.class.getAnnotation(SpringBootApplication.class);
        return List.of(annotation.scanBasePackages());
    }

    @Test
    @DisplayName("扫描范围必须同时包含全部存储实现包")
    void scansAllStoragePackages() {
        List<String> packages = scannedPackages();

        assertTrue(packages.contains("com.github.im.server"),
                "IM 服务包必须被扫描，实际：" + packages);
        assertTrue(packages.contains("com.github.im.storage.jdbc"),
                "少了 com.github.im.storage.jdbc：im.storage=jdbc 时会报 MessageRepository 找不到，实际：" + packages);
        assertTrue(packages.contains("com.github.im.storage.mongodb"),
                "少了 com.github.im.storage.mongodb：im.storage=mongodb（缺省）时会报 MessageRepository 找不到，实际：" + packages);
        assertTrue(packages.contains("com.github.im.serverboot"),
                "入口自己的包（本地装配类 / 健康检查）也必须被扫描，实际：" + packages);
    }

    @Test
    @DisplayName("Redis 形态的存储实现已移除：扫描白名单里不该再出现它")
    void doesNotScanRemovedRedisStoragePackage() {
        List<String> packages = scannedPackages();

        assertFalse(packages.contains("com.github.im.storage.redis"),
                "im-storage-redis 已从项目移除，扫描残留会让启动报 ClassNotFound / 空扫描，实际：" + packages);
    }
}
