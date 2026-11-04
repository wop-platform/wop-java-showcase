package com.wanlianyida.wop.showcase.uat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanlianyida.wop.SecurityLevel;
import com.wanlianyida.wop.VerifyResult;
import com.wanlianyida.wop.WopClient;
import com.wanlianyida.wop.WopGatewayResponseException;
import com.wanlianyida.wop.config.WopSdkConfig;
import com.wanlianyida.wop.config.WopSdkConfigLoader;
import com.wanlianyida.wop.showcase.config.WopClientConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UAT 真网关契约测试（IT = Integration Test）：按 {@code src/test/resources/uat/cases.json}
 * 逐个调用线上接口。
 * <p>
 * 不在默认 {@code mvn test} 中执行（类名以 IT 结尾，Surefire 默认不包含）。手动运行：
 * <pre>{@code mvn test -Dtest=WopUatGatewayIT}</pre>
 * 用例文件与凭证都可用 JVM 属性 / 环境变量指向本地真实副本（仓库内为脱敏示例）：
 * <pre>{@code
 * mvn test -Dtest=WopUatGatewayIT \
 *     -Dwop.sdk.config=/path/to/wopSdkConfig.json \
 *     -Dwop.uat.cases=/path/to/cases.json
 * }</pre>
 * <p>
 * 断言尺度：用例通过 = <b>接口连通</b>，即验签通过且网关返回 2xx（说明请求已到达业务方并拿到业务应答）；
 * 业务返回码不是 {@code OK-000}（如 {@code GE-001}、{@code BCOM0401 未授权}、参数/数据不符）只作标注，<b>不判失败</b>。
 * 网关非 2xx（如 {@code OP_GW_5009} 未发布、{@code OP_GW_4001} 下游 404）判未通。
 * <p>
 * 安全闸：serverRoot 必须包含 {@code uat}，防止误连生产。
 */
class WopUatGatewayIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String OK_CODE = "OK-000";

    /** 用例文件覆盖：仓库内为脱敏示例，真实入参放本地文件后用 JVM 属性/环境变量指向它。 */
    private static final String CASES_LOCATION = "/uat/cases.json";
    private static final String CASES_OVERRIDE_PROPERTY = "wop.uat.cases";
    private static final String CASES_OVERRIDE_ENV = "WOP_UAT_CASES";

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void runAllCasesAgainstUat() throws Exception {
        // 与 Spring 装配同一套解析：-Dwop.sdk.config / WOP_SDK_CONFIG 优先，否则用 classpath 演示配置
        WopSdkConfig config = WopSdkConfigLoader.load(WopClientConfiguration.resolveConfigLocation());
        assertThat(config.serverRoot())
                .as("安全闸：UAT 契约测试只允许连包含 uat 的网关，当前 serverRoot=%s", config.serverRoot())
                .containsIgnoringCase("uat");

        WopClient client = WopClient.fromConfig(config);
        JsonNode cases = loadCases();
        assertThat(cases.size()).as("用例数量").isPositive();

        List<CaseResult> results = new ArrayList<>();
        for (JsonNode c : cases) {
            results.add(run(client, c));
        }

        writeReport(config, results);
        results.forEach(r -> System.out.println(r.line()));

        List<String> failures = results.stream().filter(r -> !r.pass()).map(CaseResult::line).toList();
        long bizOk = results.stream().filter(r -> OK_CODE.equals(r.code())).count();
        System.out.printf("%nUAT 接口连通：共 %d，通 %d，未通 %d（业务返回 OK-000：%d）%n",
                results.size(), results.size() - failures.size(), failures.size(), bizOk);
        System.out.println("报告：target/uat-contract-report.md");
        assertThat(failures).as("UAT 接口未通：\n%s", String.join("\n", failures)).isEmpty();
    }

    private static JsonNode loadCases() throws Exception {
        String override = System.getProperty(CASES_OVERRIDE_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv(CASES_OVERRIDE_ENV);
        }
        if (override != null && !override.isBlank()) {
            return JSON.readTree(Files.readString(Path.of(override.strip()), StandardCharsets.UTF_8));
        }
        try (InputStream in = WopUatGatewayIT.class.getResourceAsStream(CASES_LOCATION)) {
            assertThat(in).as("缺少用例文件 classpath:%s", CASES_LOCATION).isNotNull();
            return JSON.readTree(in);
        }
    }

    private CaseResult run(WopClient client, JsonNode c) {
        String id = c.path("id").asText();
        String name = c.path("name").asText();
        String path = c.path("path").asText();
        String expect = c.path("expect").asText("reachable");
        SecurityLevel level = SecurityLevel.valueOf(
                c.path("securityLevel").asText("L0").toUpperCase(Locale.ROOT));

        Integer http = null;
        String code = "";
        String message = "";
        Boolean bizSuccess = null;
        long start = System.nanoTime();
        boolean pass;

        try {
            byte[] body = c.hasNonNull("body") ? JSON.writeValueAsBytes(c.get("body")) : null;
            VerifyResult result = client.execute("POST", path, body, level);
            http = 200;
            if (result.ok()) {
                Biz biz = parseBiz(result.plaintextAsUtf8());
                code = biz.code();
                message = biz.message();
                bizSuccess = biz.success();
            } else {
                code = result.reason() == null ? "" : result.reason().name();
                message = result.message();
            }
            // 只要请求到达业务方并拿到应答（HTTP 2xx + 验签通过）即算接口通；业务码非 OK-000 仅标注
            pass = result.ok();
        } catch (WopGatewayResponseException e) {
            http = e.statusCode();
            String raw = new String(e.body(), StandardCharsets.UTF_8);
            Biz biz = parseBiz(raw);
            code = biz.code();
            message = biz.message();
            pass = false;
            if (matchesExpectedUnavailable(expect, http, raw)) {
                message = message + "（已知未就绪：" + expect + "）";
            }
        } catch (Exception e) {
            message = e.getClass().getSimpleName() + ": " + e.getMessage();
            pass = false;
        }

        long ms = (System.nanoTime() - start) / 1_000_000;
        return new CaseResult(id, name, path, expect, http, code, message, bizSuccess, ms, pass);
    }

    /** 已知未就绪接口：当前必须仍返回对应网关错误，平台修复后测试会失败以提醒更新预期。 */
    private static boolean matchesExpectedUnavailable(String expect, int status, String rawBody) {
        return switch (expect) {
            case "unavailable-5009" -> status == 500 && rawBody.contains("OP_GW_5009");
            case "unavailable-4001" -> status == 504 && rawBody.contains("OP_GW_4001");
            default -> false;
        };
    }

    private static Biz parseBiz(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return new Biz("", "", null);
        }
        try {
            JsonNode n = JSON.readTree(plaintext);
            String code = firstText(n, "code", "errCode", "errMsgCode");
            String message = firstText(n, "message", "errMsg");
            Boolean success = n.has("succeed") ? n.get("succeed").asBoolean() : ("OK-000".equals(code) ? Boolean.TRUE : null);
            return new Biz(code, message, success);
        } catch (Exception e) {
            return new Biz("", plaintext.length() > 200 ? plaintext.substring(0, 200) : plaintext, null);
        }
    }

    private static String firstText(JsonNode n, String... names) {
        for (String name : names) {
            if (n.hasNonNull(name)) {
                return n.get(name).asText();
            }
        }
        return "";
    }

    private static void writeReport(WopSdkConfig config, List<CaseResult> results) throws Exception {
        long passed = results.stream().filter(CaseResult::pass).count();
        long bizOk = results.stream().filter(r -> OK_CODE.equals(r.code())).count();
        StringBuilder md = new StringBuilder();
        md.append("# WOP UAT 契约测试报告\n\n");
        md.append("- 执行时间：").append(LocalDateTime.now().format(TS)).append('\n');
        md.append("- 网关：").append(config.serverRoot()).append('\n');
        md.append("- 应用：").append(config.appKey()).append('\n');
        md.append("- 用例：").append(results.size())
                .append("，接口通：").append(passed)
                .append("，接口未通：").append(results.size() - passed)
                .append("，业务 OK-000：").append(bizOk).append("\n\n");
        md.append("> 断言尺度：用例通过 = 接口连通（验签通过 + 网关 2xx，请求已到达业务方并拿到应答）。\n")
                .append("> 业务返回码非 `OK-000` 仅标注\"业务未通过\"，不计入失败；网关非 2xx 判接口未通。\n\n");
        md.append("| id | 接口 | 路径 | 预期 | HTTP | 业务码 | 业务消息 | 业务 OK-000 | 耗时(ms) | 接口连通 |\n");
        md.append("|----|------|------|------|------|--------|----------|-------------|----------|----------|\n");
        for (CaseResult r : results) {
            md.append("| ").append(r.id())
                    .append(" | ").append(escape(r.name()))
                    .append(" | `").append(r.path()).append('`')
                    .append(" | ").append(r.expect())
                    .append(" | ").append(r.http() == null ? "-" : r.http())
                    .append(" | ").append(escape(r.code()))
                    .append(" | ").append(escape(r.message()))
                    .append(" | ").append(OK_CODE.equals(r.code()) ? "✅" : "⚠️ 业务未通过")
                    .append(" | ").append(r.ms())
                    .append(" | ").append(r.pass() ? "✅ PASS" : "❌ FAIL")
                    .append(" |\n");
        }
        Path report = Path.of("target", "uat-contract-report.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, md.toString(), StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private record Biz(String code, String message, Boolean success) {
    }

    private record CaseResult(String id, String name, String path, String expect, Integer http,
                              String code, String message, Boolean bizSuccess, long ms, boolean pass) {
        String line() {
            return String.format("[%s] %-2s %-28s %-70s -> %s %s %s (%dms)",
                    pass ? "PASS" : "FAIL", id, name, path,
                    http == null ? "NO_HTTP" : http,
                    code, message, ms);
        }
    }
}
