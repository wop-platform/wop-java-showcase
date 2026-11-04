package com.wanlianyida.wop.showcase.uat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanlianyida.wop.SecurityLevel;
import com.wanlianyida.wop.VerifyResult;
import com.wanlianyida.wop.WopClient;
import com.wanlianyida.wop.WopGatewayResponseException;
import com.wanlianyida.wop.config.WopSdkConfig;
import com.wanlianyida.wop.config.WopSdkConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UAT 真网关契约测试：按 {@code src/test/resources/uat/cases.json} 逐个调用线上接口。
 * <p>
 * 不在默认 {@code mvn test} 中执行（类名以 IT 结尾，Surefire 默认不包含）。手动运行：
 * <pre>{@code mvn test -Dtest=WopUatContractIT}</pre>
 * <p>
 * 断言尺度：用例通过 = <b>接口连通</b>，即验签通过且网关返回 2xx（说明请求已到达业务方并拿到业务应答）；
 * 业务返回码不是 {@code OK-000}（如 {@code GE-001}、{@code BCOM0401 未授权}、参数/数据不符）只作标注，<b>不判失败</b>。
 * 网关非 2xx（如 {@code OP_GW_5009} 未发布、{@code OP_GW_4001} 下游 404）判未通。
 * <p>
 * 安全闸：serverRoot 必须包含 {@code uat}，防止误连生产。
 * <p>
 * 直连业务方：读取 {@code src/test/resources/uat/businessConfig.json} 的 baseUrl 与请求头，
 * 用 cases.json 中的 {@code x-woa-path} 拼出实际地址再 POST 一次同一报文，仅记录结果（不参与 PASS/FAIL 判定）。
 * {@code x-woa-path} 为空的用例跳过并在报告中标注。
 */
class WopUatContractIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String OK_CODE = "OK-000";
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 每次运行按时间戳生成独立报告，避免覆盖上一次结果。 */
    private static Path mdReport;
    private static Path csvReport;

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void runAllCasesAgainstUat() throws Exception {
        String runTs = LocalDateTime.now().format(FILE_TS);
        mdReport = Path.of("target", "uat-contract-report-" + runTs + ".md");
        csvReport = Path.of("target", "uat-contract-report-" + runTs + ".csv");
        WopSdkConfig config = WopSdkConfigLoader.load("classpath:config/wopSdkConfig.json");
        assertThat(config.serverRoot())
                .as("安全闸：UAT 契约测试只允许连包含 uat 的网关，当前 serverRoot=%s", config.serverRoot())
                .containsIgnoringCase("uat");

        WopClient client = WopClient.fromConfig(config);
        BusinessClient business = loadBusinessClient();
        JsonNode cases = loadCases();
        assertThat(cases.size()).as("用例数量").isPositive();

        List<CaseResult> results = new ArrayList<>();
        for (JsonNode c : cases) {
            results.add(run(client, business, c));
        }

        writeReport(config, results);
        writeCsv(results);
        results.forEach(r -> {
            System.out.println(r.line());
            System.out.println(r.bizLine());
        });

        List<CaseResult> failures = results.stream().filter(r -> !r.pass()).toList();
        long bizOk = results.stream().filter(r -> OK_CODE.equals(r.code())).count();
        long bizCalled = results.stream().filter(r -> r.business().http() != null).count();
        System.out.printf("%nUAT 接口连通：共 %d，通 %d，未通 %d（业务返回 OK-000：%d）%n",
                results.size(), results.size() - failures.size(), failures.size(), bizOk);
        System.out.printf("业务方直连：已调用 %d，跳过 %d%n", bizCalled, results.size() - bizCalled);
        printlnReportPaths();
        assertThat(failures).as("UAT 接口未通：\n%s",
                String.join("\n", failures.stream().map(CaseResult::line).toList())).isEmpty();
    }

    private static void printlnReportPaths() {
        System.out.println("Markdown 报告：" + mdReport.toAbsolutePath().normalize());
        System.out.println("CSV 报告     ：" + csvReport.toAbsolutePath().normalize());
    }

    private static JsonNode loadCases() throws Exception {
        try (InputStream in = WopUatContractIT.class.getResourceAsStream("/uat/cases.json")) {
            assertThat(in).as("缺少用例文件 classpath:/uat/cases.json").isNotNull();
            return JSON.readTree(in);
        }
    }

    private static BusinessClient loadBusinessClient() throws Exception {
        try (InputStream in = WopUatContractIT.class.getResourceAsStream("/uat/businessConfig.json")) {
            assertThat(in).as("缺少业务方配置文件 classpath:/uat/businessConfig.json").isNotNull();
            JsonNode cfg = JSON.readTree(in);
            String baseUrl = cfg.path("baseUrl").asText("").trim();
            assertThat(baseUrl).as("businessConfig.json 缺少 baseUrl").isNotBlank();
            Map<String, String> headers = new LinkedHashMap<>();
            cfg.path("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
            // 敏感信息：fixed-sys / fixed-key 优先取环境变量，配置文件只保留占位
            String fixedSys = firstNonBlank(System.getenv("WOP_BIZ_FIXED_SYS"), headers.get("fixed-sys"));
            String fixedKey = firstNonBlank(System.getenv("WOP_BIZ_FIXED_KEY"), headers.get("fixed-key"));
            assertThat(fixedSys).as("缺少 fixed-sys：请设置环境变量 WOP_BIZ_FIXED_SYS").isNotBlank();
            assertThat(fixedKey).as("缺少 fixed-key：请设置环境变量 WOP_BIZ_FIXED_KEY").isNotBlank();
            headers.put("fixed-sys", fixedSys);
            headers.put("fixed-key", fixedKey);
            return new BusinessClient(baseUrl, headers,
                    Duration.ofMillis(cfg.path("readTimeoutMs").asLong(15_000)),
                    Duration.ofMillis(cfg.path("connectTimeoutMs").asLong(5_000)));
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private CaseResult run(WopClient client, BusinessClient business, JsonNode c) {
        String id = c.path("id").asText();
        String name = c.path("name").asText();
        String path = c.path("path").asText();
        String expect = c.path("expect").asText("reachable");
        SecurityLevel level = SecurityLevel.valueOf(
                c.path("securityLevel").asText("L0").toUpperCase(Locale.ROOT));

        Integer http = null;
        String code = "";
        String message = "";
        String response = "";
        Boolean bizSuccess = null;
        byte[] body = null;
        long start = System.nanoTime();
        boolean pass;

        try {
            body = c.hasNonNull("body") ? JSON.writeValueAsBytes(c.get("body")) : null;
            VerifyResult result = client.execute("POST", path, body, level);
            http = 200;
            if (result.ok()) {
                response = result.plaintextAsUtf8();
                Biz biz = parseBiz(response);
                code = biz.code();
                message = biz.message();
                bizSuccess = biz.success();
            } else {
                response = result.message();
                code = result.reason() == null ? "" : result.reason().name();
                message = result.message();
            }
            // 只要请求到达业务方并拿到应答（HTTP 2xx + 验签通过）即算接口通；业务码非 OK-000 仅标注
            pass = result.ok();
        } catch (WopGatewayResponseException e) {
            http = e.statusCode();
            String raw = new String(e.body(), StandardCharsets.UTF_8);
            response = raw;
            Biz biz = parseBiz(raw);
            code = biz.code();
            message = biz.message();
            pass = false;
            if (matchesExpectedUnavailable(expect, http, raw)) {
                message = message + "（已知未就绪：" + expect + "）";
            }
        } catch (Exception e) {
            message = e.getClass().getSimpleName() + ": " + e.getMessage();
            response = message;
            pass = false;
        }

        long ms = (System.nanoTime() - start) / 1_000_000;
        // 直连业务方：同一报文再发一次，仅记录结果，不影响上面的 PASS/FAIL
        BusinessResult bizResult = business.call(c, body);
        return new CaseResult(id, name, path, expect, http, code, message, response, bizSuccess, ms, pass,
                bizResult);
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
        long bizCalled = results.stream().filter(r -> r.business().http() != null).count();
        StringBuilder md = new StringBuilder();
        md.append("# WOP UAT 契约测试报告\n\n");
        md.append("- 执行时间：").append(LocalDateTime.now().format(TS)).append('\n');
        md.append("- 网关：").append(config.serverRoot()).append('\n');
        md.append("- 应用：").append(config.appKey()).append('\n');
        md.append("- 用例：").append(results.size())
                .append("，接口通：").append(passed)
                .append("，接口未通：").append(results.size() - passed)
                .append("，业务 OK-000：").append(bizOk).append('\n');
        md.append("- 业务方直连：已调用 ").append(bizCalled)
                .append("，跳过 ").append(results.size() - bizCalled)
                .append("（x-woa-path 为空）\n\n");
        md.append("> 断言尺度：用例通过 = 接口连通（验签通过 + 网关 2xx，请求已到达业务方并拿到应答）。\n")
                .append("> 业务返回码非 `OK-000` 仅标注\"业务未通过\"，不计入失败；网关非 2xx 判接口未通。\n")
                .append("> 业务方直连列仅作参考，不参与 PASS/FAIL 判定。\n\n");
        md.append("| id | 接口 | 路径 | 业务码 | 业务消息 | 业务方返回(截断) |\n");
        md.append("|----|------|------|--------|----------|------------------|\n");
        for (CaseResult r : results) {
            md.append("| ").append(r.id())
                    .append(" | ").append(escape(r.name()))
                    .append(" | `").append(r.path()).append('`')
                    .append(" | ").append(escape(r.code()))
                    .append(" | ").append(escape(r.message()))
                    .append(" | ").append(escape(abbr(r.business().response(), 120)))
                    .append(" |\n");
        }
        Path report = mdReport;
        Files.createDirectories(report.getParent());
        Files.writeString(report, md.toString(), StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    /** 单行摘要：换行压成空格，超长截断（用于控制台与 Markdown）。 */
    private static String abbr(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replace("\r", " ").replace("\n", " ");
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    /**
     * 生成 CSV 报告（每次运行覆盖）：UTF-8 BOM + CRLF，Excel 直接打开不乱码、不串列。
     * 列与 Markdown 报告一致，便于筛选/透视。
     */
    private static void writeCsv(List<CaseResult> results) throws Exception {
        StringBuilder csv = new StringBuilder();
        csv.append("id,接口,路径,业务方URL,业务码,业务消息,接口返回值,业务方返回值\r\n");
        for (CaseResult r : results) {
            BusinessResult b = r.business();
            csv.append(csvCell(r.id())).append(',')
                    .append(csvCell(r.name())).append(',')
                    .append(csvCell(r.path())).append(',')
                    .append(csvCell(b.url())).append(',')
                    .append(csvCell(r.code())).append(',')
                    .append(csvCell(r.message())).append(',')
                    .append(csvCell(capForExcel(r.response()))).append(',')
                    .append(csvCell(capForExcel(b.response()))).append("\r\n");
        }
        Path report = csvReport;
        Files.createDirectories(report.getParent());
        // BOM 让 Excel 按 UTF-8 识别中文
        Files.write(report, ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8));
    }

    /** CSV 单元格转义：含逗号/引号/换行时加双引号，内部双引号翻倍。 */
    private static String csvCell(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** Excel 单元格上限 32767 字符：超长接口返回值截断并标注原长度。 */
    private static String capForExcel(String s) {
        if (s == null || s.length() <= 32000) {
            return s;
        }
        return s.substring(0, 32000) + "…(已截断，原始长度 " + s.length() + " 字符)";
    }

    private record Biz(String code, String message, Boolean success) {
    }

    /**
     * 业务方直连客户端：baseUrl + 用例的 {@code x-woa-path} 拼出实际地址，
     * 携带 businessConfig.json 中的固定请求头，POST 与网关相同的报文。
     * 不做任何判定，只把 HTTP 状态与原始响应带回去。
     */
    private static final class BusinessClient {

        private final HttpClient http;
        private final String baseUrl;
        private final Map<String, String> headers;
        private final Duration readTimeout;

        BusinessClient(String baseUrl, Map<String, String> headers,
                       Duration readTimeout, Duration connectTimeout) {
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.headers = headers;
            this.readTimeout = readTimeout;
            this.http = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        BusinessResult call(JsonNode c, byte[] body) {
            String woaPath = c.path("x-woa-path").asText("").trim();
            if (woaPath.isEmpty()) {
                return new BusinessResult("", null, "", 0, "未配置 x-woa-path，跳过");
            }
            String url = baseUrl + (woaPath.startsWith("/") ? woaPath : "/" + woaPath);
            long start = System.nanoTime();
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(readTimeout)
                        .header("Content-Type", "application/json")
                        .POST(body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
                headers.forEach(builder::header);
                HttpResponse<String> response = http.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new BusinessResult(url, response.statusCode(), response.body(),
                        elapsedMs(start), "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new BusinessResult(url, null, "", elapsedMs(start),
                        "InterruptedException: " + e.getMessage());
            } catch (Exception e) {
                return new BusinessResult(url, null, "", elapsedMs(start),
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        private static long elapsedMs(long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
    }

    private record BusinessResult(String url, Integer http, String response, long ms, String note) {
    }

    private record CaseResult(String id, String name, String path, String expect, Integer http,
                              String code, String message, String response, Boolean bizSuccess,
                              long ms, boolean pass, BusinessResult business) {
        String line() {
            return String.format("[%s] %-2s %-28s %-70s -> %s %s %s (%dms)",
                    pass ? "PASS" : "FAIL", id, name, path,
                    http == null ? "NO_HTTP" : http,
                    code, message, ms);
        }

        String bizLine() {
            if (business.url().isEmpty()) {
                return "    业务方跳过：" + business.note();
            }
            String status = business.http() == null ? "NO_HTTP" : String.valueOf(business.http());
            String suffix = business.note().isEmpty() ? "" : " [" + business.note() + "]";
            return String.format("    业务方 %s -> %s %s (%dms)%s",
                    business.url(), status, abbr(business.response(), 300), business.ms(), suffix);
        }
    }
}
