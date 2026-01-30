package com.anjia.unidbgserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * unidbg配置类
 *
 * @author AnJia
 * @since 2021-07-26 19:13
 */
@Data
@ConfigurationProperties(prefix = "application.unidbg")
public class UnidbgProperties {
    /**
     * 是否使用 DynarmicFactory
     */
    boolean dynarmic;
    /**
     * 是否打印调用信息
     */
    boolean verbose;

    /**
     * 是否使用异步多线程
     */
    boolean async = true;

    /**
     * signer（unidbg）全局重置最小间隔（ms）。
     * <p>
     * 用于抑制上游抖动导致的“频繁 reset -> 更慢 -> 更容易空响应”的风暴。
     * 设为 0 可禁用节流。
     */
    long resetCooldownMs = 2000L;

    /**
     * 番茄小说 APK 文件路径（建议使用 base.apk 的绝对路径）
     * 优先级高于 apkClasspath；适合本地或容器运行时挂载文件。
     */
    String apkPath;

    /**
     * 番茄小说 APK 的 classpath 资源路径（例如：com/dragon/read/oversea/gp/apk/base.apk）
     * 当 apkPath 未配置时使用；未配置时默认读取 classpath: com/dragon/read/oversea/gp/apk/base.apk
     */
    String apkClasspath;
}
