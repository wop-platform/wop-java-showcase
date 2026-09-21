package com.wanlianyida.wop.showcase.web.dto;

import java.util.Map;

/**
 * 统一请求体（所有 POST 接口共用）。所有接口恒以 POST 发送。
 *
 * @param path           网关 API 路径，必填（以 / 开头的纯路径，如 /logistics/waybill/query-waybill-info）
 * @param securityLevel  安全级别 L0 / L2，缺省 L0
 * @param body           业务 JSON 对象，可为空对象（L2 需非空 body）
 * @param connectTimeout 连接超时毫秒（仅 override/timeout 接口消费，&gt;0 生效）
 * @param readTimeout    读超时毫秒（仅 override/timeout 接口消费，&gt;0 生效）
 * @param appKey         覆盖用 appKey（仅 override/app-key 接口消费）
 * @param serverRoot     覆盖用主域名（仅 override/server-root 接口消费）
 */
public class WopCallRequest {

    private String path;
    private String securityLevel;
    private Map<String, Object> body;
    private Integer connectTimeout;
    private Integer readTimeout;
    private String appKey;
    private String serverRoot;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getSecurityLevel() {
        return securityLevel;
    }

    public void setSecurityLevel(String securityLevel) {
        this.securityLevel = securityLevel;
    }

    public Map<String, Object> getBody() {
        return body;
    }

    public void setBody(Map<String, Object> body) {
        this.body = body;
    }

    public Integer getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Integer connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Integer getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Integer readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getServerRoot() {
        return serverRoot;
    }

    public void setServerRoot(String serverRoot) {
        this.serverRoot = serverRoot;
    }
}
