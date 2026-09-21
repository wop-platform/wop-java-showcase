package com.wanlianyida.wop.showcase.web;

import com.wanlianyida.wop.WopError;
import com.wanlianyida.wop.WopGatewayResponseException;
import com.wanlianyida.wop.showcase.service.WopAppService;
import com.wanlianyida.wop.showcase.web.dto.WopCallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * WOP SDK 接入演示接口（除 config 查询外均为 POST，参数统一放 JSON body）。
 */
@RestController
@RequestMapping("/api/wop")
public class WopDemoController {

    private static final Logger log = LoggerFactory.getLogger(WopDemoController.class);

    private final WopAppService appService;

    public WopDemoController(WopAppService appService) {
        this.appService = appService;
    }

    /** 一站式调用。curl -X POST localhost:8080/api/wop/one-stop -d '{"path":"/gateway/demo","level":"L0","body":"{...}"}' */
    @PostMapping("/one-stop")
    public ResponseEntity<WopCallResponse> oneStop(@RequestBody WopCallRequest req) {
        return call(() -> WopCallResponse.ok(appService.oneStop(req)));
    }

    /** 分步 API 调用（buildRequest → 传输适配器发送 → verifyResponse）。 */
    @PostMapping("/step-by-step")
    public ResponseEntity<WopCallResponse> stepByStep(@RequestBody WopCallRequest req) {
        return call(() -> WopCallResponse.ok(appService.stepByStep(req)));
    }

    /** 每笔覆盖①：替换超时时间。请求体需含 connectTimeout 和/或 readTimeout（毫秒）。 */
    @PostMapping("/override/timeout")
    public ResponseEntity<WopCallResponse> overrideTimeout(@RequestBody WopCallRequest req) {
        boolean hasTimeout = (req.getConnectTimeout() != null && req.getConnectTimeout() > 0)
                || (req.getReadTimeout() != null && req.getReadTimeout() > 0);
        if (!hasTimeout) {
            return ResponseEntity.badRequest().body(WopCallResponse.fail("connectTimeout / readTimeout 至少填一项（毫秒）"));
        }
        return call(() -> WopCallResponse.ok(appService.overrideTimeout(req)));
    }

    /** 每笔覆盖②：替换 appKey。请求体需含 appKey。 */
    @PostMapping("/override/app-key")
    public ResponseEntity<WopCallResponse> overrideAppKey(@RequestBody WopCallRequest req) {
        if (req.getAppKey() == null || req.getAppKey().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(WopCallResponse.fail("appKey 必填"));
        }
        return call(() -> WopCallResponse.ok(appService.overrideAppKey(req)));
    }

    /** 每笔覆盖③：替换主域名。请求体需含 serverRoot（HTTPS 绝对地址）。 */
    @PostMapping("/override/server-root")
    public ResponseEntity<WopCallResponse> overrideServerRoot(@RequestBody WopCallRequest req) {
        if (req.getServerRoot() == null || req.getServerRoot().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(WopCallResponse.fail("serverRoot 必填"));
        }
        return call(() -> WopCallResponse.ok(appService.overrideServerRoot(req)));
    }

    /**
     * 请求预览：返回将发往网关的完整 header + body（零网络 IO）。
     * level 缺省时一次返回 L0 与 L2 两份对比。
     */
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody WopCallRequest req) {
        try {
            return ResponseEntity.ok(Map.of("path", req.getPath() == null ? "" : req.getPath(),
                    "previews", appService.preview(req)));
        } catch (IllegalArgumentException | WopError e) {
            return ResponseEntity.badRequest().body(WopCallResponse.fail(e.getMessage()));
        }
    }

    /** 当前生效配置（密钥打码）——唯一 GET。 */
    @GetMapping("/config")
    public Map<String, String> config() {
        return Map.of("config", appService.maskedConfig());
    }

    private ResponseEntity<WopCallResponse> call(java.util.function.Supplier<WopCallResponse> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (WopGatewayResponseException e) {
            // 非 2xx 不进验签（K5）：网关系统类错误，含状态码与响应体
            String body = new String(e.body(), StandardCharsets.UTF_8);
            log.warn("网关返回非 2xx: status={}, body={}", e.statusCode(), body);
            return ResponseEntity.ok(WopCallResponse.gatewayError(e.statusCode(), body));
        } catch (IllegalStateException e) {
            // 验签/解密失败（I7 模糊）：响应不可信
            log.error("响应校验失败: {}", e.getMessage());
            return ResponseEntity.ok(WopCallResponse.fail(e.getMessage()));
        } catch (IllegalArgumentException | WopError e) {
            // 参数/协议配置类错误（path 非法、level 非法、L2 空 body、覆盖复校验不过等）
            return ResponseEntity.badRequest().body(WopCallResponse.fail(e.getMessage()));
        } catch (com.wanlianyida.wop.WopSdkException e) {
            // 传输类系统错误（连接失败、超时、响应截断等）
            log.error("传输失败: {}", e.getMessage());
            return ResponseEntity.ok(WopCallResponse.fail("传输失败: " + e.getMessage()));
        }
    }

    /** 统一应答体。 */
    public record WopCallResponse(boolean ok, String data, Integer gatewayStatus, String error) {

        static WopCallResponse ok(String data) {
            return new WopCallResponse(true, data, null, null);
        }

        static WopCallResponse fail(String error) {
            return new WopCallResponse(false, null, null, error);
        }

        static WopCallResponse gatewayError(int status, String body) {
            return new WopCallResponse(false, body, status, "网关 HTTP " + status);
        }
    }
}
