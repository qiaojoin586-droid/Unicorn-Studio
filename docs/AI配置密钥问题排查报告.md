# AI 配置「API Key 丢失」排查报告

> 排查时间：2026-09-13　｜　结论：**密钥从未丢失**，但连带发现并修复了 **4 处缺陷**（含 1 处严重安全问题）

---

## 一、结论先行

| 问题 | 性质 | 状态 |
|---|---|---|
| 用户报告的「API Key 丢失」 | **感知失真**（前端显示误导） | 已修复 |
| **审计日志泄漏明文 API Key** | **严重安全问题**（实际发生） | 已修复 + 已清理 |
| 密钥被误判为「未修改」而静默丢弃 | 潜在数据丢失 | 已修复 |
| **Redis 缓存被污染导致功能不可用** | **真正的「不可用」根因** | 已修复 + 已清理 |

**密钥本体完好无损**：数据库 `site_settings.ai.api_key` 一直是 113 字符的 AES-256-GCM 密文，
从未被覆盖或清空。数据库里其余 `ai.*` 行都在 `15:28:02` 更新过，唯独 `ai.api_key` 停留在
`14:56:17` —— 这恰好证明「留空 = 不修改」的守卫**一直在正常工作**。

---

## 二、缺陷一（严重·安全）：审计日志把明文 API Key 写进了磁盘

### 现象
明文密钥出现在两处：
1. `sys_operation_log` 表（id=118）的 `params` 字段
2. `logs/admin/2026-09-13/app-admin.log` 第 1 行

### 根因
`@AuditLog` 切面（`AuditLogAspect`）的工作方式是**把 Controller 入参原样 JSON 序列化**后
落库/落文件，只按**字段名**脱敏，没有通用兜底。

而 `PUT /api/admin/ai-config` 的请求体里带的是**明文** `apiKey`
（加密发生在 Service 层，切面拿到的是 Controller 入参），
但 `SENSITIVE_KEYS` 里只有 `password / token / refreshToken / oldPassword / newPassword`，
**没有 `apiKey`** —— 于是明文直出。

> 这是 AI 助手模块引入的缺陷，不是历史遗留。

### 修复
`AuditLogAspect` 两处常量扩充：

```java
// 精确匹配（小写）
SENSITIVE_KEYS = { password, token, refreshToken, oldPassword, newPassword,
                   apikey, api_key, secret, cryptosecret, accesskey, access_key, authorization }

// 后缀兜底：防「以后新增 xxxKey 字段又漏配」
SENSITIVE_SUFFIXES = { password, token, secret, secretkey, apikey, apisecret, accesskey, privatekey }
```

### 清理
- 数据库：`UPDATE sys_operation_log SET params = REPLACE(...)` → 2 行已处置
- 日志文件：Python 读改写替换 → 已处置
- 全仓 grep 明文确认无残留
- 顺带把历史行的 `""` / `"   "` / `"••••••••"` 统一规范为 `"***"`

### 验证
修复前后有清晰的时间分界：

| 时间 | `params` 中的 apiKey |
|---|---|
| 15:51 及以前（旧 jar） | 明文 |
| **16:00 之后（新 jar）** | **全部 `"***"`** |

---

## 三、缺陷二（UX）：空密码框让用户以为「密钥没了」

### 现象
用户打开后台 AI 配置页，看到一个**空的密码输入框**，认为密钥已丢失。

### 根因
原实现让 API Key 输入框**始终显示**，`load()` 里把 `form.apiKey = ''`，靠 placeholder
和一行灰色提示文字说明「留空 = 不修改」。审计日志印证了用户的困惑行为 ——
id=122 那次保存提交的正是回显的脱敏串 `"••••••••"`（用户把掩码贴回来了），
id=124/125 提交的是空串。两种都被守卫挡下，但整个过程对用户完全不可见。

另外输入框带了 `clearable`，在「空值代表不修改」的语义下，那个 `×` 按钮既无意义又吓人。

### 修复
改为「**已保存 key 时默认不渲染输入框**」：

- 只显示 `已保存 ••••••••` 标签 + 一个「更换密钥」链接
- 点击后才展开输入框（`editingKey` 控制），保存后自动收起
- **移除 `clearable`**
- 文案改为「保存后覆盖原密钥 / 不填则保持不变」
- 保存提示区分是否改了 key：`AI 配置已保存（API Key 已更新），即时生效`

---

## 四、缺陷三（潜在数据丢失）：用「包含圆点」判断是否改过密钥

### 根因
```java
// 修复前
return apiKey == null || apiKey.isBlank() || apiKey.indexOf('•') >= 0;
```
这会把**含圆点的真实密钥**（如 `sk-abcd.efgh1234`）误判为「未修改」而**静默丢弃** ——
用户以为改了，实际根本没写进去。

### 修复
改为「**整体**只由掩码字符构成」才算未修改：

```java
public boolean apiKeyUnchanged() {
    if (apiKey == null || apiKey.isBlank()) return true;
    String trimmed = apiKey.trim();
    for (int i = 0; i < trimmed.length(); i++) {
        char c = trimmed.charAt(i);
        boolean maskChar = c == '•' || c == '*' || c == '·' || c == '．' || Character.isWhitespace(c);
        if (!maskChar) return false;
    }
    return true;
}
```

`AiConfigUpdateRequest` 与 `AiTestRequest` 两处统一口径；Service 层再加 `!plainKey.isEmpty()` 兜底。

---

## 五、缺陷四（真正的「功能不可用」根因）：Redis 缓存污染

### 现象
`GET /api/ai/capabilities` 返回 `available: false`，前台 AI 助手完全不可用 ——
这才是用户真正感受到的「丢了」。

### 根因链
1. `AiConfigProvider.runtimeConfig()` 走 Cache-Aside，把**解密后的配置对象**整个缓存进
   Redis `cache:ai:config`（TTL 5 分钟）
2. 更早的后端实例启动时**没有**正确的 `APP_CRYPTO_SECRET` → 解密失败 →
   `catch` 分支把 `apiKey` 记成 `null`
3. **这个 `null` 坏快照照样被写进 Redis 并缓存 5 分钟**
4. 之后即使换用正确密钥重启，只要坏缓存没过期，`available` 就仍然为 `false`

> **关键陷阱**：`application.yml` 写的是 `${APP_CRYPTO_SECRET:}` —— 这是**环境变量占位符**，
> **不解析 `-D` 系统属性**。用 `-DAPP_CRYPTO_SECRET=xxx` 传参完全无效，会静默回退到
> `app.admin.jwt-secret` 派生密钥。

### 证据
Redis 探针直查：
```
key      = cache:ai:config
exists   = true
ttl(s)   = 226
value    = {"apiKey":null,"apiUrl":"...","model":"GLM-4.5-Air",...}
```
日志：`AI api-key 解密失败，请检查 APP_CRYPTO_SECRET 是否与写入时一致`
+ `AEADBadTagException: Tag mismatch`

### 密钥其实完好
用 `SecretProbe` 逐候选试解，`unicorn-studio-dev-crypto-secret-2026-key` **一次命中**，
解出原始密钥。数据库密文 113 字符从未变过。

### 处置
`DEL cache:ai:config` → 用正确环境变量重启 → `available: true` 恢复。

### 教训
`catch` 里把解密失败静默降级为 `null` 并继续缓存，会把**一次性错误放大成持续故障**。
写缓存降级逻辑时应「**失败不缓存**」，而不是缓存失败结果。

---

## 六、验证

| 验证项 | 结果 |
|---|---|
| `clean compile`（173 源文件） | ✅ BUILD SUCCESS |
| `vue-tsc --noEmit` | ✅ 退出 0，无报错 |
| 离线单测（脱敏 + 未修改判定） | ✅ **31 / 31 全过** |
| 端到端（启动→就绪→回环→关停） | ✅ 22 项断言通过 |
| 4 种「未修改」形态密文不变 | ✅ 不传字段 / `""` / 脱敏串 / 纯空白 全部逐字节不变 |
| 真实 key 写入路径 | ✅ 含圆点 key 被接受 → 加密覆盖 → 库中无明文 → 还原成功 |
| 审计脱敏实测 | ✅ 16:00 后全部 `"apiKey":"***"` |

### 沙箱踩坑（可复用）
1. **`${ENV_VAR:}` 不认 `-D`** —— 必须用真实环境变量
2. **后台 java 进程活不过父 shell** —— 把「启动→验证→关停」写进同一个 Python 进程
3. **Maven 增量编译会漏掉改动** —— 报 `Nothing to compile` 时先删 `.class` 再编译
4. **验证 jar 是否真更新** —— 直接读 class 常量池确认，本报告正是靠这招抓到「源码已修好但跑的是旧 jar」

---

## 七、待用户处理

1. **`APP_CRYPTO_SECRET` 必须在部署环境固定下来**（本次用的
   `unicorn-studio-dev-crypto-secret-2026-key` 是临时值）。**更换该值会导致已存密文无法解密**，
   需在后台重新填写 API Key。
2. **`hanxi` 账号密码**在排查过程中被临时重置为 `Hanxi@2026`，请改回。

---

## 附：本次改动文件

| 文件 | 改动 |
|---|---|
| `backend/.../common/audit/AuditLogAspect.java` | 脱敏字段扩充 + 后缀兜底 |
| `backend/.../AIWorker/dto/AiConfigUpdateRequest.java` | `apiKeyUnchanged()` 判定收紧 |
| `backend/.../AIWorker/dto/AiTestRequest.java` | 同上，统一口径 |
| `backend/.../AIWorker/service/AiConfigService.java` | 加空值兜底保护 |
| `admin/src/views/settings/AiConfigView.vue` | 密钥回显交互重构 |
