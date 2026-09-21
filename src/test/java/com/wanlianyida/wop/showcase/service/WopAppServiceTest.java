package com.wanlianyida.wop.showcase.service;

import com.wanlianyida.wop.RequestDraft;
import com.wanlianyida.wop.SecurityLevel;
import com.wanlianyida.wop.Transport;
import com.wanlianyida.wop.TransportResponse;
import com.wanlianyida.wop.VerifyResult;
import com.wanlianyida.wop.WopClient;
import com.wanlianyida.wop.WopRequestOptions;
import com.wanlianyida.wop.config.WopSdkConfig;
import com.wanlianyida.wop.showcase.web.dto.WopCallRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WopAppService 单元测试：经注入的进程内 Transport 隔离网络，
 * 验证 method/path/level/body 组装、一站式与分步调用、三类覆盖参数、预览结果。
 */
@ExtendWith(MockitoExtension.class)
class WopAppServiceTest {

    @Mock
    private WopClient client;

    @Mock
    private WopSdkConfig config;

    private TransportResponse lastSentResponse;
    private Transport stubTransport;
    private WopAppService service;

    @BeforeEach
    void setUp() {
        lastSentResponse = null;
        stubTransport = draft -> {
            if (lastSentResponse == null) {
                throw new AssertionError("测试未预设响应");
            }
            return lastSentResponse;
        };
        service = new WopAppService(client, config, stubTransport);
    }

    private static WopCallRequest request(String path, String securityLevel,
                                          Map<String, Object> body) {
        WopCallRequest req = new WopCallRequest();
        req.setPath(path);
        req.setSecurityLevel(securityLevel);
        req.setBody(body);
        return req;
    }

    private static VerifyResult ok(String plaintext) {
        return new VerifyResult(true, plaintext.getBytes(StandardCharsets.UTF_8), null, null);
    }

    private static VerifyResult fail(VerifyResult.Reason reason) {
        return new VerifyResult(false, null, reason, null);
    }

    // ==================== 一站式调用 ====================

    @Test
    void oneStopPassesMethodPathLevelAndSerializedBody() {
        when(client.execute(anyString(), anyString(), any(), any(SecurityLevel.class)))
                .thenReturn(ok("{\"code\":\"00000\"}"));

        String plaintext = service.oneStop(request("/logistics/waybill/query-waybill-info", "L2",
                Map.of("waybillId", "TD2026062200000054")));

        assertThat(plaintext).isEqualTo("{\"code\":\"00000\"}");
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(client).execute(eq("POST"), eq("/logistics/waybill/query-waybill-info"),
                bodyCaptor.capture(), eq(SecurityLevel.L2));
        assertThat(new String(bodyCaptor.getValue(), StandardCharsets.UTF_8))
                .contains("\"waybillId\":\"TD2026062200000054\"");
    }

    @Test
    void oneStopAlwaysUsesPost() {
        when(client.execute(anyString(), anyString(), any(), any(SecurityLevel.class))).thenReturn(ok("{}"));

        // 有 body
        service.oneStop(request("/gateway/demo", null, Map.of("a", 1)));
        verify(client).execute(eq("POST"), eq("/gateway/demo"), any(), eq(SecurityLevel.L0));
    }

    @Test
    void oneStopWithoutBodyStillUsesPost() {
        when(client.execute(anyString(), anyString(), any(), any(SecurityLevel.class))).thenReturn(ok("{}"));

        service.oneStop(request("/gateway/demo", null, null));
        verify(client).execute(eq("POST"), eq("/gateway/demo"), eq(null), eq(SecurityLevel.L0));
    }

    @Test
    void oneStopThrowsIllegalStateWhenVerifyFails() {
        when(client.execute(anyString(), anyString(), any(), any(SecurityLevel.class)))
                .thenReturn(fail(VerifyResult.Reason.SIGNATURE_FAILED));

        assertThatThrownBy(() -> service.oneStop(request("/gateway/demo", null, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIGNATURE_FAILED")
                .hasMessageContaining("签名验证失败");
    }

    @Test
    void oneStopRejectsMissingPath() {
        assertThatThrownBy(() -> service.oneStop(request(null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path 必填");
    }

    @Test
    void oneStopRejectsIllegalSecurityLevel() {
        assertThatThrownBy(() -> service.oneStop(request("/gateway/demo", "L9", Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("securityLevel");
    }

    // ==================== 分步 API ====================

    @Test
    void stepByStepBuildsSendsAndVerifies() {
        RequestDraft draft = new RequestDraft("POST", "/gateway/demo",
                Map.of("x-wop-sign", "sig"), "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        when(client.buildRequest("POST", "/gateway/demo", draft.wireBody(), SecurityLevel.L0))
                .thenReturn(draft);
        when(client.verifyResponse(any(TransportResponse.class), eq(draft))).thenReturn(ok("{\"ok\":true}"));
        lastSentResponse = new TransportResponse(200, Map.of("content-type", "application/json"),
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        String plaintext = service.stepByStep(request("/gateway/demo", "L0", Map.of("a", 1)));

        assertThat(plaintext).isEqualTo("{\"ok\":true}");
        verify(client).verifyResponse(any(TransportResponse.class), eq(draft));
    }

    @Test
    void stepByStepPropagatesNon2xxAsGatewayException() {
        RequestDraft draft = new RequestDraft("POST", "/gateway/demo", Map.of(), new byte[0]);
        when(client.buildRequest(anyString(), anyString(), any(), any(SecurityLevel.class))).thenReturn(draft);
        lastSentResponse = new TransportResponse(401, Map.of(),
                "{\"code\":\"OP_GW_1001\"}".getBytes(StandardCharsets.UTF_8));

        // 分步 API 手动发送不走 execute 的状态拦截，verifyResponse 对无签名头响应给出明确 reason
        when(client.verifyResponse(any(TransportResponse.class), eq(draft)))
                .thenReturn(fail(VerifyResult.Reason.MISSING_SIGN_HEADER));

        assertThatThrownBy(() -> service.stepByStep(request("/gateway/demo", "L0", Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MISSING_SIGN_HEADER");
    }

    // ==================== 每笔覆盖 ====================

    @Test
    void overrideTimeoutCarriesTimeoutsIntoOptions() {
        when(client.execute(anyString(), anyString(), any(), any(SecurityLevel.class),
                any(WopRequestOptions.class))).thenReturn(ok("{}"));

        WopCallRequest req = request("/gateway/demo", "L0", Map.of("a", 1));
        req.setConnectTimeout(5000);
        req.setReadTimeout(8000);
        service.overrideTimeout(req);

        ArgumentCaptor<WopRequestOptions> captor = ArgumentCaptor.forClass(WopRequestOptions.class);
        verify(client).execute(anyString(), anyString(), any(), eq(SecurityLevel.L0), captor.capture());
        assertThat(captor.getValue().connectTimeout()).isEqualTo(5000);
        assertThat(captor.getValue().readTimeout()).isEqualTo(8000);
    }

    @Test
    void overrideAppKeyCarriesAppKeyIntoOptions() {
        when(client.execute(anyString(), anyString(), any(), any(SecurityLevel.class),
                any(WopRequestOptions.class))).thenReturn(ok("{}"));

        WopCallRequest req = request("/gateway/demo", null, Map.of("a", 1));
        req.setAppKey("app_other");
        service.overrideAppKey(req);

        ArgumentCaptor<WopRequestOptions> captor = ArgumentCaptor.forClass(WopRequestOptions.class);
        verify(client).execute(anyString(), anyString(), any(), eq(SecurityLevel.L0), captor.capture());
        assertThat(captor.getValue().appKey()).isEqualTo("app_other");
    }

    @Test
    void overrideServerRootCarriesServerRootIntoOptions() {
        when(client.execute(anyString(), anyString(), any(), any(SecurityLevel.class),
                any(WopRequestOptions.class))).thenReturn(ok("{}"));

        WopCallRequest req = request("/gateway/demo", null, Map.of("a", 1));
        req.setServerRoot("https://openapi-hw.10000da.vip:443");
        service.overrideServerRoot(req);

        ArgumentCaptor<WopRequestOptions> captor = ArgumentCaptor.forClass(WopRequestOptions.class);
        verify(client).execute(anyString(), anyString(), any(), eq(SecurityLevel.L0), captor.capture());
        assertThat(captor.getValue().serverRoot()).isEqualTo("https://openapi-hw.10000da.vip:443");
    }

    @Test
    void overrideAppKeyRejectsBlankAppKey() {
        assertThatThrownBy(() -> service.overrideAppKey(request("/gateway/demo", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appKey 必填");
    }

    @Test
    void overrideServerRootRejectsBlankServerRoot() {
        assertThatThrownBy(() -> service.overrideServerRoot(request("/gateway/demo", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serverRoot 必填");
    }

    // ==================== 预览 ====================

    @Test
    void previewWithoutLevelReturnsL0AndL2() {
        when(client.buildRequest(anyString(), anyString(), any(), any(SecurityLevel.class)))
                .thenAnswer(inv -> {
                    String method = inv.getArgument(0);
                    String path = inv.getArgument(1);
                    SecurityLevel level = inv.getArgument(3);
                    Map<String, String> headers = level == SecurityLevel.L2
                            ? Map.of("x-wop-sign", "sig", "x-wop-encrypt", "L2;dek=x")
                            : Map.of("x-wop-sign", "sig", "x-wop-content-digest", "sha-256 abc");
                    byte[] wire = level == SecurityLevel.L2
                            ? "{\"encrypted\":\"xx\"}".getBytes(StandardCharsets.UTF_8)
                            : "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
                    return new RequestDraft(method, path, headers, wire);
                });

        List<Map<String, Object>> previews = service.preview(request("/gateway/demo", null, Map.of("a", 1)));

        assertThat(previews).hasSize(2);
        assertThat(previews.get(0)).containsEntry("level", "L0").containsEntry("method", "POST");
        assertThat(String.valueOf(previews.get(0).get("headers"))).contains("x-wop-content-digest");
        assertThat(previews.get(1)).containsEntry("level", "L2");
        assertThat(previews.get(1).get("wireBody")).isEqualTo("{\"encrypted\":\"xx\"}");
        assertThat(String.valueOf(previews.get(1).get("headers"))).contains("x-wop-encrypt");
    }

    @Test
    void previewWithExplicitLevelReturnsSingleEntry() {
        when(client.buildRequest(anyString(), anyString(), any(), any(SecurityLevel.class)))
                .thenAnswer(inv -> new RequestDraft(inv.getArgument(0), inv.getArgument(1),
                        Map.of(), new byte[0]));

        List<Map<String, Object>> previews = service.preview(
                request("/gateway/demo", "L2", Map.of("a", 1)));

        assertThat(previews).hasSize(1);
        assertThat(previews.get(0)).containsEntry("level", "L2");
    }

    @Test
    void previewWithoutBodyOnlyReturnsL0StillPost() {
        when(client.buildRequest(anyString(), anyString(), any(), any(SecurityLevel.class)))
                .thenAnswer(inv -> new RequestDraft(inv.getArgument(0), inv.getArgument(1), Map.of(), null));

        List<Map<String, Object>> previews = service.preview(request("/gateway/demo", null, null));

        assertThat(previews).hasSize(1);
        assertThat(previews.get(0)).containsEntry("level", "L0").containsEntry("method", "POST");
        assertThat(previews.get(0).get("wireBody")).isNull();
    }

    // ==================== 配置 ====================

    @Test
    void maskedConfigDelegatesToConfigObject() {
        when(config.toMaskedString()).thenReturn("WopSdkConfig[merchantPrivateKey=****]");
        assertThat(service.maskedConfig()).isEqualTo("WopSdkConfig[merchantPrivateKey=****]");
    }
}
