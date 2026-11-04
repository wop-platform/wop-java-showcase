package com.wanlianyida.wop.showcase.config;

import com.wanlianyida.wop.WopClient;
import com.wanlianyida.wop.config.WopSdkConfig;
import com.wanlianyida.wop.config.WopSdkConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WOP SDK 与 Spring Boot 的装配层 —— 商户侧集成的全部配置代码。
 * <p>
 * 配置文件位于 classpath：{@code config/wopSdkConfig.json}（随 jar 打包，
 * 字段见 wop-sdk-config-spec §3），加载期即 fail-fast 校验
 * （JSON 语法 / 必填 / HTTPS / 套件 / 密钥格式，任一不过启动失败）。
 * <p>
 * 仓库内 classpath 配置只放<b>演示凭证</b>（私钥为一次性生成的示例钥，不可用于真实联调）。
 * 真实凭证不要提交：放到仓库外的本地文件，用下面的方式覆盖。
 * <pre>{@code
 * # 方式一：启动参数 / JVM 属性
 * mvn spring-boot:run -Dwop.sdk.config=/etc/wop/wopSdkConfig.json
 * # 方式二：环境变量
 * export WOP_SDK_CONFIG=/etc/wop/wopSdkConfig.json
 * }</pre>
 * 生产环境直接改为外置文件亦可（{@code WopSdkConfigLoader.load(...)}）。
 */
@Configuration
public class WopClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WopClientConfiguration.class);

    /** classpath 配置文件位置（打包资源，仅含演示凭证）。 */
    public static final String CONFIG_LOCATION = "classpath:config/wopSdkConfig.json";

    /** 本地/生产配置覆盖：JVM 属性或环境变量，值为文件路径，优先于 {@link #CONFIG_LOCATION}。 */
    public static final String CONFIG_OVERRIDE_PROPERTY = "wop.sdk.config";
    public static final String CONFIG_OVERRIDE_ENV = "WOP_SDK_CONFIG";

    @Bean
    public WopSdkConfig wopSdkConfig() {
        String location = resolveConfigLocation();
        WopSdkConfig config = WopSdkConfigLoader.load(location);
        log.info("WOP SDK 配置加载成功（来源 {}）：{}", location, config.toMaskedString());
        return config;
    }

    /**
     * 配置来源：{@code -Dwop.sdk.config=<path>} / {@code WOP_SDK_CONFIG=<path>} 优先，
     * 否则回落到 classpath 内置配置。
     */
    public static String resolveConfigLocation() {
        String override = System.getProperty(CONFIG_OVERRIDE_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv(CONFIG_OVERRIDE_ENV);
        }
        return (override == null || override.isBlank()) ? CONFIG_LOCATION : override.strip();
    }

    /**
     * 商户客户端 Bean：之后业务代码直接注入 {@link WopClient}，
     * 一行 {@code execute(...)} 完成 签名 → HTTPS → 非 2xx 拦截 → 验签解密。
     * 传输适配器由 SPI 自动发现（classpath 恰一：wop-sdk-jdkhttp）。
     */
    @Bean
    public WopClient wopClient(WopSdkConfig config) {
        return WopClient.fromConfig(config);
    }
}
