# wop-java-showcase

[Spring Boot](https://spring.io/projects/spring-boot) 3.x 商户侧接入 [wop-java-sdk](https://github.com/wop-platform/wop-java-sdk) 0.3.0 的最简示例工程。

**JSON 配置文件（resources 打包）→ `@Bean WopClient` → 业务代码一行 `execute()`**；
无需理解签名、摘要、L2 数字信封与验签解密细节。

## 工程结构

```
pom.xml                                      # spring-boot-starter-web + wop-sdk-core + wop-sdk-okhttp + starter-test
src/main/resources/config/wopSdkConfig.json  # SDK 凭证配置（随 jar 打包，加载期 fail-fast 校验）
src/main/java/.../showcase/
├── ShowcaseApplication.java                 # 启动类
├── config/WopClientConfiguration.java       # ★ @Bean WopSdkConfig + @Bean WopClient（全部配置代码）
├── service/WopAppService.java               # ★ 调用方式：一站式 / 分步 / 每笔覆盖 / 请求预览
└── web/
    ├── WopDemoController.java               # REST 接口（全 POST，参数在 body）
    └── dto/WopCallRequest.java              # 统一请求体（普通类，getter/setter）
src/test/java/.../
├── web/WopDemoControllerTest.java           # Controller 全场景测试（MockMvc，19 例）
└── service/WopAppServiceTest.java           # Service 逻辑测试（Mockito + 进程内 Transport，22 例）
```

## 快速开始

```bash
# 依赖 SDK 0.3.0 已安装到本地仓库（SDK 仓执行: mvn -DskipTests install）
mvn spring-boot:run    # 启动
mvn test               # 单元测试（41 例，零网络）
```

配置文件 `src/main/resources/config/wopSdkConfig.json`：

| 字段 | 当前值 | 说明 |
|------|--------|------|
| `appKey` | `app_xxxxxxxxxxxxxxxx` | 商户 appKey（`x-wop-appkey` 头） |
| `suite` | `WOP-RSA2048-SHA256` | 算法套件（默认 2048） |
| `merchantPrivateKey` / `platformPublicKey` | 演示值 | 商户私钥 / 平台公钥（Base64 单行）；私钥为一次性生成的**示例钥**，仅供启动演示 |
| `serverRoot` | `https://<网关域名>:443` | 网关地址（HTTPS，含 context-path） |
| `backupServerRoots` | `https://<备用网关域名>:443` | 备用网关（有序，主网关连接类失败后依序尝试） |
| `expiredSeconds` | `1800` | 签名有效窗口（秒） |
| `httpClient` | 10000 / 30000 / 3 | 连接超时 / 读超时（ms）/ 跨域名重试上限 |

> **仓库内只放演示凭证，真实 appKey / 私钥不要提交**（`*.local.json` 已被 `.gitignore` 忽略）。
> 用下面的方式指向本地真实配置，优先级高于 classpath：
>
> ```bash
> mvn spring-boot:run -Dwop.sdk.config=src/main/resources/config/wopSdkConfig.local.json
> # 或
> export WOP_SDK_CONFIG=/path/to/wopSdkConfig.json
> ```

生产接入：把门户分发的正式凭证写入外置文件
`WopSdkConfigLoader.load("/etc/wop/wopSdkConfig.json")` / `WopClient.defaultClient()`（自动发现顺序见 SDK README）。

## 接口（除 config 外均为 POST，参数在 JSON body）

统一请求体（`WopCallRequest`，所有接口**恒以 POST 发送**）：

```json
{
    "path": "/logistics/waybill/query-waybill-info",
    "securityLevel": "L2",
    "body": {
        "waybillId": "TDxxxxxxxxxxxxxxxx"
    }
}
```

- `path` 必填（`/` 开头纯路径）；
- `securityLevel` 取 `L0`/`L2`，缺省 `L0`；`body` 为业务 JSON **对象**；
- 报文里若带 `"method"` 字段会被忽略（一律按 POST 处理）。
- 覆盖类接口额外消费 `connectTimeout` / `readTimeout`（毫秒）、`appKey`、`serverRoot`。
- `subMchId` 可选（ISV 子商户平台企业id）：设置后所有调用接口都会携带 `x-wop-sub-mch-id` 头，以子商户身份调用；缺省不带头 = 应用自身身份。

| 接口 | 额外必填 | 说明 |
|------|--------|------|
| `POST /api/wop/one-stop` | — | **一站式**：`execute()` 一行完成 签名→HTTPS→非2xx拦截→验签解密 |
| `POST /api/wop/step-by-step` | — | **分步 API**：`buildRequest` → 传输适配器 `send` → `verifyResponse`（F6） |
| `POST /api/wop/override/timeout` | `connectTimeout`/`readTimeout` 至少一项 | **每笔覆盖①**：替换超时时间 |
| `POST /api/wop/override/app-key` | `appKey` | **每笔覆盖②**：替换 appKey（本笔加签身份） |
| `POST /api/wop/override/server-root` | `serverRoot` | **每笔覆盖③**：替换主域名（本笔 Failover 关闭） |
| `POST /api/wop/preview` | — | **请求预览**：返回将发往网关的完整 header + body（零网络 IO）；securityLevel 缺省返回 L0 与 L2 两份对比 |
| `GET /api/wop/config` | — | 当前生效配置（密钥打码） |

### 调用示例

```bash
# ISV 子商户身份调用：所有调用接口都可带 subMchId，请求头多出 x-wop-sub-mch-id
curl -X POST localhost:8080/api/wop/one-stop -H "Content-Type: application/json" \
  -d '{"path":"/logistics/waybill/query-waybill-info","securityLevel":"L0","body":{"waybillId":"TDxxxxxxxxxxxxxxxx"},"subMchId":"PLF-SUB-001"}'

# 请求预览（L2 报文；securityLevel 不传则返回 L0 与 L2 两份对比，不发网络请求）
curl -X POST localhost:8080/api/wop/preview -H "Content-Type: application/json" \
  -d '{"path":"/logistics/waybill/query-waybill-info","securityLevel":"L2","body":{"waybillId":"TDxxxxxxxxxxxxxxxx"}}'

# 一站式调用（L0 明文：仅签名 + 摘要）
curl -X POST localhost:8080/api/wop/one-stop -H "Content-Type: application/json" \
  -d '{"path":"/logistics/waybill/query-waybill-info","securityLevel":"L0","body":{"waybillId":"TDxxxxxxxxxxxxxxxx"}}'

# 一站式调用（L2 全文数字信封：SDK 自动加密为 {"encrypted":"..."}，DEK 平台公钥包装）
curl -X POST localhost:8080/api/wop/one-stop -H "Content-Type: application/json" \
  -d '{"path":"/logistics/waybill/query-waybill-info","securityLevel":"L2","body":{"waybillId":"TDxxxxxxxxxxxxxxxx"}}'

# 分步 API 调用
curl -X POST localhost:8080/api/wop/step-by-step -H "Content-Type: application/json" \
  -d '{"path":"/logistics/waybill/query-waybill-info","securityLevel":"L0","body":{"waybillId":"TDxxxxxxxxxxxxxxxx"}}'

# 每笔替换超时
curl -X POST localhost:8080/api/wop/override/timeout -H "Content-Type: application/json" \
  -d '{"path":"/gateway/demo","body":{},"connectTimeout":5000,"readTimeout":8000}'

# 每笔替换 appKey / 主域名
curl -X POST localhost:8080/api/wop/override/app-key -H "Content-Type: application/json" \
  -d '{"path":"/gateway/demo","body":{},"appKey":"app_xxx"}'
curl -X POST localhost:8080/api/wop/override/server-root -H "Content-Type: application/json" \
  -d '{"path":"/gateway/demo","body":{},"serverRoot":"https://<网关域名>:443"}'
```

应答体：`{"ok":true,"data":"<平台响应明文>"}`；
非 2xx → `{"ok":false,"gatewayStatus":401,"data":"<网关错误体>","error":"网关 HTTP 401"}`（不进验签，K5）；
验签/解密失败 → `{"ok":false,"error":"...reason..."}`（对外模糊，I7）；
参数/协议错误 → HTTP 400。

## 商户侧最小集成（从本工程抄什么）

1. **pom 依赖**：`wop-sdk-core` + 传输适配器之一。
   默认用 **okhttp**（实测本网关 4xx 错误响应在 JDK HttpURLConnection 上 `getErrorStream()` 读不到错误体，
   okhttp 适配器无此问题；okhttp 在 SDK 中为 provided，须自带 `com.squareup.okhttp3:okhttp`）。
2. **配置文件**：`resources/config/wopSdkConfig.json`（字段见上表）。
3. **一个 Bean**（`WopClientConfiguration` 全部内容）：

```java
@Bean
public WopSdkConfig wopSdkConfig() {
    return WopSdkConfigLoader.load("classpath:config/wopSdkConfig.json");
}

@Bean
public WopClient wopClient(WopSdkConfig config) {
    return WopClient.fromConfig(config);   // 传输适配器经 SPI 自动发现
}
```

4. **业务调用**（`WopAppService`）：

```java
// 一站式
VerifyResult result = client.execute("POST", "/logistics/waybill/query-waybill-info", body, SecurityLevel.L2);
if (result.ok()) {
    String plaintext = result.plaintextAsUtf8();
}

// 每笔请求覆盖（key / 超时 / 域名），传 options 重载即可
VerifyResult r2 = client.execute("POST", "/gateway/demo", body, SecurityLevel.L0,
        WopRequestOptions.builder().appKey("app_xxx").connectTimeout(5000).build());

// 请求预览（零网络 IO）
RequestDraft draft = client.buildRequest("POST", "/logistics/waybill/query-waybill-info", body, SecurityLevel.L2);
Map<String, String> headers = draft.headers();          // x-wop-sign / digest / encrypt ...
byte[] wireBody = draft.wireBody();                     // L2: {"encrypted":"..."}
```

## 测试

`mvn test` 共 41 例（零网络）：

- **WopDemoControllerTest**（19 例，MockMvc + MockBean）：请求绑定（path/securityLevel/body 对象与缺省值、样例报文 method 字段容忍）、一站式成功/网关非 2xx/验签失败/缺 path 400/非法 securityLevel 400、分步成功与非 2xx、三类覆盖的 400 校验与参数透传、预览应答、config；
- **WopAppServiceTest**（22 例，Mockito + 注入进程内 Transport）：恒以 POST 发送（含无 body）、body 序列化、L0/L2 级别传递、验签失败异常、分步三环节衔接、三类覆盖进入 `WopRequestOptions`、预览 L0+L2 单份/双份与 GET 无 body、配置打码。

## UAT 契约测试（手动，真网关）

`WopUatGatewayIT`（IT = Integration Test，集成测试）按 `src/test/resources/uat/cases.json`
逐个真实调用 UAT 网关（含写接口），用于联调阶段批量验证开放平台接口：

```bash
mvn test -Dtest=WopUatGatewayIT
```

- 类名以 `IT` 结尾（Maven 集成测试惯例），默认 `mvn test` **不会执行**；
- **用例数据已脱敏**：仓库内的手机号、身份证号、银行卡号、车牌、姓名、OSS 签名链接均为示例值，
  真实入参放本地副本并用 `-Dwop.uat.cases=<path>`（或 `WOP_UAT_CASES`）指向它：
  ```bash
  mvn test -Dtest=WopUatGatewayIT \
      -Dwop.sdk.config=src/main/resources/config/wopSdkConfig.local.json \
      -Dwop.uat.cases=src/test/resources/uat/cases.local.json
  ```
- 断言：**接口连通即通过**（验签通过 + 网关 2xx，说明请求已到达业务方并拿到应答）；
  业务码非 `OK-000` 只在报告里标注“业务未通过”，不计入失败；网关非 2xx（如 `OP_GW_5009`/`OP_GW_4001`）判未通；
- 安全闸：`serverRoot` 不包含 `uat` 时直接失败，防止误连生产；
- 结果：控制台逐条输出，并生成 `target/uat-contract-report.md`；
- **注意**：写接口使用示例真实执行，会在 UAT 产生或变更数据。

## 当前状态说明

- 传输链路、签名、信封、预览均已实测通过；
- UAT 联调对 appKey 返回 `401 OP_GW_1001 "appKey 为空或应用不存在"` 时，说明
  **签名请求已正确到达网关**，需在平台侧为该 appKey 开通测试环境权限后再重试。
- SDK 协议正确性由其自带的黄金向量 conformance 套件保障。
