package com.wanlianyida.wop.showcase.web;

import com.wanlianyida.wop.WopError;
import com.wanlianyida.wop.WopGatewayResponseException;
import com.wanlianyida.wop.showcase.service.WopAppService;
import com.wanlianyida.wop.showcase.web.dto.WopCallRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller 全场景覆盖：请求绑定（method/path/securityLevel/body 对象）、
 * 一站式/分步/三类覆盖/预览/config、400 校验、非 2xx 与验签失败的应答映射。
 */
@WebMvcTest(WopDemoController.class)
class WopDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WopAppService appService;

    // ==================== 请求绑定 ====================

    @Test
    void oneStopBindsUserSamplePayload() throws Exception {
        // 用户样例报文（含 method 字段）：method 恒为 POST，字段被忽略不影响绑定
        when(appService.oneStop(any())).thenReturn("{}");

        mockMvc.perform(post("/api/wop/one-stop").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "method": "POST",
                                    "path": "/logistics/waybill/query-waybill-info",
                                    "securityLevel": "L2",
                                    "body": {"waybillId": "TD2026062200000054"}
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<WopCallRequest> captor = ArgumentCaptor.forClass(WopCallRequest.class);
        verify(appService).oneStop(captor.capture());
        WopCallRequest req = captor.getValue();
        assertThat(req.getPath()).isEqualTo("/logistics/waybill/query-waybill-info");
        assertThat(req.getSecurityLevel()).isEqualTo("L2");
        assertThat(req.getBody()).containsEntry("waybillId", "TD2026062200000054");
    }

    @Test
    void defaultsApplyWhenSecurityLevelAbsent() throws Exception {
        when(appService.oneStop(any())).thenReturn("{}");

        mockMvc.perform(post("/api/wop/one-stop").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<WopCallRequest> captor = ArgumentCaptor.forClass(WopCallRequest.class);
        verify(appService).oneStop(captor.capture());
        assertThat(captor.getValue().getSecurityLevel()).isNull();
    }

    // ==================== 一站式调用 ====================

    @Test
    void oneStopReturnsPlaintextOnSuccess() throws Exception {
        when(appService.oneStop(any())).thenReturn("{\"code\":\"00000\"}");

        mockMvc.perform(post("/api/wop/one-stop").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\",\"body\":{\"a\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data").value("{\"code\":\"00000\"}"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void oneStopMapsGatewayNon2xxToStructuredError() throws Exception {
        byte[] body = "{\"code\":\"OP_GW_1001\"}".getBytes(StandardCharsets.UTF_8);
        when(appService.oneStop(any())).thenThrow(new WopGatewayResponseException(401, body));

        mockMvc.perform(post("/api/wop/one-stop").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\",\"body\":{\"a\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.gatewayStatus").value(401))
                .andExpect(jsonPath("$.data").value("{\"code\":\"OP_GW_1001\"}"))
                .andExpect(jsonPath("$.error").value("网关 HTTP 401"));
    }

    @Test
    void oneStopMapsVerifyFailureToFuzzyError() throws Exception {
        when(appService.oneStop(any()))
                .thenThrow(new IllegalStateException("WOP 响应校验失败: SIGNATURE_FAILED（签名验证失败）"));

        mockMvc.perform(post("/api/wop/one-stop").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\",\"body\":{\"a\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.gatewayStatus").doesNotExist())
                .andExpect(jsonPath("$.error").value("WOP 响应校验失败: SIGNATURE_FAILED（签名验证失败）"));
    }

    @Test
    void oneStopRejectsMissingPathWith400() throws Exception {
        when(appService.oneStop(any()))
                .thenThrow(new IllegalArgumentException("path 必填（以 / 开头的网关 API 路径）"));

        mockMvc.perform(post("/api/wop/one-stop").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":{\"a\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void oneStopRejectsIllegalSecurityLevelWith400() throws Exception {
        when(appService.oneStop(any())).thenThrow(
                WopError.configuration("securityLevel 仅支持 L0 / L2: L9"));

        mockMvc.perform(post("/api/wop/one-stop").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\",\"securityLevel\":\"L9\"}"))
                .andExpect(status().isBadRequest());
    }

    // ==================== 分步 API ====================

    @Test
    void stepByStepReturnsPlaintextOnSuccess() throws Exception {
        when(appService.stepByStep(any())).thenReturn("{\"code\":\"00000\"}");

        mockMvc.perform(post("/api/wop/step-by-step").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\",\"body\":{\"a\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data").value("{\"code\":\"00000\"}"));
    }

    @Test
    void stepByStepMapsGatewayError() throws Exception {
        when(appService.stepByStep(any()))
                .thenThrow(new WopGatewayResponseException(502, "bad gateway".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(post("/api/wop/step-by-step").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\",\"body\":{\"a\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.gatewayStatus").value(502));
    }

    // ==================== 每笔覆盖①：超时 ====================

    @Test
    void overrideTimeoutRejectsMissingTimeoutsWith400() throws Exception {
        mockMvc.perform(post("/api/wop/override/timeout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("connectTimeout / readTimeout 至少填一项（毫秒）"));
        verifyNoInteractions(appService);
    }

    @Test
    void overrideTimeoutAcceptsConnectTimeoutOnly() throws Exception {
        when(appService.overrideTimeout(any())).thenReturn("{}");

        mockMvc.perform(post("/api/wop/override/timeout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\",\"body\":{\"a\":1},\"connectTimeout\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        ArgumentCaptor<WopCallRequest> captor = ArgumentCaptor.forClass(WopCallRequest.class);
        verify(appService).overrideTimeout(captor.capture());
        assertThat(captor.getValue().getConnectTimeout()).isEqualTo(5000);
        assertThat(captor.getValue().getReadTimeout()).isNull();
    }

    // ==================== 每笔覆盖②：appKey ====================

    @Test
    void overrideAppKeyRejectsMissingAppKeyWith400() throws Exception {
        mockMvc.perform(post("/api/wop/override/app-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("appKey 必填"));
        verifyNoInteractions(appService);
    }

    @Test
    void overrideAppKeyPassesAppKeyToService() throws Exception {
        when(appService.overrideAppKey(any())).thenReturn("{}");

        mockMvc.perform(post("/api/wop/override/app-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\",\"body\":{\"a\":1},\"appKey\":\"app_other\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<WopCallRequest> captor = ArgumentCaptor.forClass(WopCallRequest.class);
        verify(appService).overrideAppKey(captor.capture());
        assertThat(captor.getValue().getAppKey()).isEqualTo("app_other");
    }

    // ==================== 每笔覆盖③：主域名 ====================

    @Test
    void overrideServerRootRejectsMissingServerRootWith400() throws Exception {
        mockMvc.perform(post("/api/wop/override/server-root").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("serverRoot 必填"));
        verifyNoInteractions(appService);
    }

    @Test
    void overrideServerRootPassesServerRootToService() throws Exception {
        when(appService.overrideServerRoot(any())).thenReturn("{}");

        mockMvc.perform(post("/api/wop/override/server-root").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\",\"body\":{\"a\":1},"
                                + "\"serverRoot\":\"https://openapi-hw.10000da.vip:443\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<WopCallRequest> captor = ArgumentCaptor.forClass(WopCallRequest.class);
        verify(appService).overrideServerRoot(captor.capture());
        assertThat(captor.getValue().getServerRoot()).isEqualTo("https://openapi-hw.10000da.vip:443");
    }

    // ==================== 预览与配置 ====================

    @Test
    void previewReturnsPreviewsFromService() throws Exception {
        Map<String, Object> l0 = new LinkedHashMap<>();
        l0.put("level", "L0");
        l0.put("headers", Map.of("x-wop-sign", "sig"));
        l0.put("wireBody", "{\"a\":1}");
        Map<String, Object> l2 = new LinkedHashMap<>();
        l2.put("level", "L2");
        l2.put("wireBody", "{\"encrypted\":\"xx\"}");
        when(appService.preview(any())).thenReturn(List.of(l0, l2));

        mockMvc.perform(post("/api/wop/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/gateway/demo\",\"body\":{\"a\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previews[0].level").value("L0"))
                .andExpect(jsonPath("$.previews[1].level").value("L2"))
                .andExpect(jsonPath("$.previews[1].wireBody").value("{\"encrypted\":\"xx\"}"));
    }

    @Test
    void previewRejectsMissingPathWith400() throws Exception {
        when(appService.preview(any()))
                .thenThrow(new IllegalArgumentException("path 必填（以 / 开头的网关 API 路径）"));

        MvcResult result = mockMvc.perform(post("/api/wop/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":{\"a\":1}}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("path 必填");
    }

    @Test
    void configReturnsMaskedConfig() throws Exception {
        when(appService.maskedConfig()).thenReturn("WopSdkConfig[appKey=app_demo, merchantPrivateKey=****]");

        mockMvc.perform(get("/api/wop/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config").value("WopSdkConfig[appKey=app_demo, merchantPrivateKey=****]"));
    }
}
