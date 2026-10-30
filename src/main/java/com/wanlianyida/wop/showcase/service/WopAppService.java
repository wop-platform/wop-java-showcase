package com.wanlianyida.wop.showcase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanlianyida.wop.RequestDraft;
import com.wanlianyida.wop.SecurityLevel;
import com.wanlianyida.wop.Transport;
import com.wanlianyida.wop.TransportFactory;
import com.wanlianyida.wop.TransportResponse;
import com.wanlianyida.wop.VerifyResult;
import com.wanlianyida.wop.WopClient;
import com.wanlianyida.wop.WopRequestOptions;
import com.wanlianyida.wop.config.WopSdkConfig;
import com.wanlianyida.wop.showcase.web.dto.WopCallRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商户接入演示服务：一站式调用 / 分步 API 调用 / 每笔请求覆盖（超时、appKey、主域名）/ 请求预览。
 * <p>
 * 签名、摘要、L2 数字信封、验签解密全部由 SDK 内部完成，业务代码只面对明文与 {@link VerifyResult}。
 */
@Service
public class WopAppService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 所有接口恒以 POST 发送。 */
    private static final String METHOD_POST = "POST";

    private final WopClient client;
    private final WopSdkConfig config;
    private final Transport transport;

    /** 生产构造：传输适配器经 SPI 自动发现（classpath 恰一）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public WopAppService(WopClient client, WopSdkConfig config) {
        this(client, config, TransportFactory.discover().create(config.serverRoot()));
    }

    /** 测试构造：注入进程内传输，避免真实网络。 */
    WopAppService(WopClient client, WopSdkConfig config, Transport transport) {
        this.client = client;
        this.config = config;
        this.transport = transport;
    }

    /**
     * 一站式调用：签名 → HTTPS 发送 → 非 2xx 拦截 → 验签解密，一行完成。
     *
     * @return 平台响应明文（验签通过后解出的业务 JSON）
     */
    public String oneStop(WopCallRequest req) {
        WopRequestOptions options = optionsOrNull(req);
        VerifyResult result = options == null
                ? client.execute(METHOD_POST, pathOf(req), bodyOf(req), levelOf(req))
                : client.execute(METHOD_POST, pathOf(req), bodyOf(req), levelOf(req), options);
        return unwrap(result);
    }

    /**
     * 分步 API 调用：① buildRequest 构造签名草稿（零网络 IO）
     * → ② 传输适配器发送 → ③ verifyResponse 校验响应（F6 顺序）。
     * 需要自定义 HTTP 栈或旁路观测报文时用这种。
     */
    public String stepByStep(WopCallRequest req) {
        WopRequestOptions options = optionsOrNull(req);
        RequestDraft draft = options == null
                ? client.buildRequest(METHOD_POST, pathOf(req), bodyOf(req), levelOf(req))
                : client.buildRequest(METHOD_POST, pathOf(req), bodyOf(req), levelOf(req), options);
        TransportResponse response = transport.send(draft);
        return unwrap(client.verifyResponse(response, draft));
    }

    /** 每笔请求覆盖①：替换超时时间（connectTimeout / readTimeout 毫秒，仅本笔生效）。 */
    public String overrideTimeout(WopCallRequest req) {
        WopRequestOptions.Builder options = baseOptions(req);
        if (req.getConnectTimeout() != null && req.getConnectTimeout() > 0) {
            options.connectTimeout(req.getConnectTimeout());
        }
        if (req.getReadTimeout() != null && req.getReadTimeout() > 0) {
            options.readTimeout(req.getReadTimeout());
        }
        return executeWithOptions(req, options.build());
    }

    /** 每笔请求覆盖②：替换 appKey（本笔以另一 appKey 加签，x-wop-appkey 头随之变化）。 */
    public String overrideAppKey(WopCallRequest req) {
        if (req.getAppKey() == null || req.getAppKey().trim().isEmpty()) {
            throw new IllegalArgumentException("appKey 必填");
        }
        return executeWithOptions(req, baseOptions(req).appKey(req.getAppKey().trim()).build());
    }

    /** 每笔请求覆盖③：替换主域名（本笔只请求该域名，Failover 关闭，K3）。 */
    public String overrideServerRoot(WopCallRequest req) {
        if (req.getServerRoot() == null || req.getServerRoot().trim().isEmpty()) {
            throw new IllegalArgumentException("serverRoot 必填");
        }
        return executeWithOptions(req, baseOptions(req).serverRoot(req.getServerRoot().trim()).build());
    }

    /**
     * 请求预览：用与真实发送完全相同的 buildRequest 构造报文草稿（零网络 IO），
     * 返回将发往网关的完整 header 与 body。
     * securityLevel 缺省时一次返回 L0 与 L2 两份（L2 为 {"encrypted":"..."} 信封 + x-wop-encrypt 头）。
     */
    public List<Map<String, Object>> preview(WopCallRequest req) {
        String path = pathOf(req);
        byte[] body = bodyOf(req);
        WopRequestOptions options = optionsOrNull(req);
        List<Map<String, Object>> previews = new ArrayList<>();
        if (req.getSecurityLevel() == null || req.getSecurityLevel().trim().isEmpty()) {
            previews.add(previewOf(path, body, SecurityLevel.L0, options));
            if (body != null) {
                previews.add(previewOf(path, body, SecurityLevel.L2, options));
            }
        } else {
            previews.add(previewOf(path, body, levelOf(req), options));
        }
        return previews;
    }

    /** 当前生效配置（密钥打码）。 */
    public String maskedConfig() {
        return config.toMaskedString();
    }

    // ==================== 内部工具 ====================

    private String executeWithOptions(WopCallRequest req, WopRequestOptions options) {
        VerifyResult result = client.execute(METHOD_POST, pathOf(req), bodyOf(req), levelOf(req), options);
        return unwrap(result);
    }

    private Map<String, Object> previewOf(String path, byte[] body, SecurityLevel level,
                                          WopRequestOptions options) {
        RequestDraft draft = options == null
                ? client.buildRequest(METHOD_POST, path, body, level)
                : client.buildRequest(METHOD_POST, path, body, level, options);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("level", level.name());
        item.put("method", draft.method());
        item.put("path", draft.path());
        item.put("headers", draft.headers());
        item.put("wireBody", draft.wireBody() == null
                ? null : new String(draft.wireBody(), StandardCharsets.UTF_8));
        return item;
    }

    /**
     * 请求级选项基座：仅承载 subMchId（ISV 子商户身份头），其余覆盖由各 override 接口追加。
     */
    private static WopRequestOptions.Builder baseOptions(WopCallRequest req) {
        WopRequestOptions.Builder builder = WopRequestOptions.builder();
        if (req.getSubMchId() != null && !req.getSubMchId().trim().isEmpty()) {
            builder.subMchId(req.getSubMchId().trim());
        }
        return builder;
    }

    /**
     * 无任何请求级覆盖时返回 null，走 SDK 无 options 的调用路径（保持既有语义与日志）。
     */
    private static WopRequestOptions optionsOrNull(WopCallRequest req) {
        WopRequestOptions options = baseOptions(req).build();
        return options.isEmpty() ? null : options;
    }

    private static String pathOf(WopCallRequest req) {
        if (req.getPath() == null || req.getPath().trim().isEmpty()) {
            throw new IllegalArgumentException("path 必填（以 / 开头的网关 API 路径）");
        }
        return req.getPath().trim();
    }

    private static SecurityLevel levelOf(WopCallRequest req) {
        if (req.getSecurityLevel() == null || req.getSecurityLevel().trim().isEmpty()) {
            return SecurityLevel.L0;
        }
        try {
            return SecurityLevel.valueOf(req.getSecurityLevel().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("securityLevel 仅支持 L0 / L2: " + req.getSecurityLevel());
        }
    }



    /** body JSON 对象序列化为请求字节；null = 无 body（GET 语义）；L2 需非空 body（SDK 侧也会校验）。 */
    private static byte[] bodyOf(WopCallRequest req) {
        if (req.getBody() == null) {
            return null;
        }
        try {
            return JSON.writeValueAsBytes(req.getBody());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("body 序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    /** 结果展开：非 2xx 已抛 WopGatewayResponseException；验签/解密失败抛 IllegalStateException。 */
    private static String unwrap(VerifyResult result) {
        if (!result.ok()) {
            // I7：验签/解密失败原因对外模糊，响应不可信，应记录 reason 并告警
            throw new IllegalStateException("WOP 响应校验失败: " + result.reason() + "（" + result.message() + "）");
        }
        return result.plaintextAsUtf8();
    }
}
