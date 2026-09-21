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
 * 生产环境建议改为外置文件：
 * <pre>{@code
 * @Bean
 * public WopSdkConfig wopSdkConfig() {
 *     return WopSdkConfigLoader.load("/etc/wop/wopSdkConfig.json");   // 或 loadDefault() 走自动发现
 * }
 * }</pre>
 */
@Configuration
public class WopClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WopClientConfiguration.class);

    /** classpath 配置文件位置（打包资源）。 */
    public static final String CONFIG_LOCATION = "classpath:config/wopSdkConfig.json";

    @Bean
    public WopSdkConfig wopSdkConfig() {
        WopSdkConfig config = WopSdkConfigLoader.load(CONFIG_LOCATION);
        log.info("WOP SDK 配置加载成功：{}", config.toMaskedString());
        return config;
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
