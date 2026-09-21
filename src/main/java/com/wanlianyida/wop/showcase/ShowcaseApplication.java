package com.wanlianyida.wop.showcase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WOP Java SDK 0.2.0 商户侧 Spring Boot 集成演示。
 * <p>
 * 配置文件 {@code resources/config/wopSdkConfig.json}（随 jar 打包，加载期 fail-fast 校验）→
 * {@code @Bean WopClient} → REST 接口演示一站式 / 分步 / 每笔覆盖 / 请求预览。
 * 所有调用接口均为 POST、参数在 JSON body，请求统一以 POST 发往网关：
 * <pre>
 * # 请求预览：返回将发往网关的完整 header + body（零网络 IO；securityLevel 缺省返回 L0 与 L2 两份对比）
 * curl -X POST localhost:8080/api/wop/preview -H "Content-Type: application/json" \
 *   -d '{"path":"/logistics/waybill/query-waybill-info","securityLevel":"L2","body":{"waybillId":"TD2026062200000054"}}'
 *
 * # 一站式调用（签名 → HTTPS → 非 2xx 拦截 → 验签解密，一行完成）
 * curl -X POST localhost:8080/api/wop/one-stop -H "Content-Type: application/json" \
 *   -d '{"path":"/logistics/waybill/query-waybill-info","securityLevel":"L2","body":{"waybillId":"TD2026062200000054"}}'
 *
 * # 分步 API 调用（buildRequest → 传输适配器 send → verifyResponse）
 * curl -X POST localhost:8080/api/wop/step-by-step -H "Content-Type: application/json" \
 *   -d '{"path":"/logistics/waybill/query-waybill-info","securityLevel":"L0","body":{"waybillId":"TD2026062200000054"}}'
 *
 * # 每笔请求覆盖：超时 / appKey / 主域名
 * curl -X POST localhost:8080/api/wop/override/timeout -H "Content-Type: application/json" \
 *   -d '{"path":"/gateway/demo","body":{},"connectTimeout":5000,"readTimeout":8000}'
 * curl -X POST localhost:8080/api/wop/override/app-key -H "Content-Type: application/json" \
 *   -d '{"path":"/gateway/demo","body":{},"appKey":"app_xxx"}'
 * curl -X POST localhost:8080/api/wop/override/server-root -H "Content-Type: application/json" \
 *   -d '{"path":"/gateway/demo","body":{},"serverRoot":"https://openapi-hw.10000da.vip:443"}'
 *
 * # 当前生效配置（密钥打码，唯一 GET）
 * curl localhost:8080/api/wop/config
 * </pre>
 */
@SpringBootApplication
public class ShowcaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShowcaseApplication.class, args);
    }
}
