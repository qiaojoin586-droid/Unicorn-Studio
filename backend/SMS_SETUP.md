# 注册短信认证接入

本项目接入的是阿里云号码认证下的短信认证服务：`SendSmsVerifyCode`，访问 `dypnsapi.aliyuncs.com`。
个人开发者使用系统赠送签名和赠送模板，无需申请企业签名；不是普通短信产品的 `SendSms`。

## 本地配置

当前开发用凭据文件是 `src/main/resources/.env`，已从 Maven 资源复制及 JAR 打包中排除。
文件内容格式参考 `.env.example`：

```dotenv
ALIBABA_CLOUD_ACCESS_KEY_ID=填写已有的AccessKeyID
ALIBABA_CLOUD_ACCESS_KEY_SECRET=填写已有的AccessKeySecret
ALIYUN_SMS_SIGN_NAME=从控制台赠送签名列表复制可用名称
ALIYUN_SMS_TEMPLATE_CODE=100001
REGISTRATION_SMS_PROVIDER=aliyun
```

本轮采用用户提供的赠送签名“恒创联众”。模板 `100001` 是登录／注册赠送模板，代码传入 `code` 和 `min` 两个变量。
如果控制台中的赠送签名发生调整，只修改 `ALIYUN_SMS_SIGN_NAME`，重启后生效。

配置使用 UTF-8，支持 `KEY=VALUE`、单双引号、注释和 `export KEY=VALUE`；系统环境变量和 JVM 参数优先。
启动时依次查找工作目录的 `.env`、`backend/src/main/resources/.env`、`src/main/resources/.env`，只读取第一个存在的文件。
也可设置 `APP_ENV_FILE` 指向指定的外部文件。Spring Boot 不会自动读取普通 `.env`，本项目已注册专门的环境加载器。

`REGISTRATION_SMS_PROVIDER` 只有两种取值：

- `aliyun`（默认）：真实短信认证通道，日志只记录脱敏手机号、请求编号和错误码。
- `console`：本地联调通道，验证码打印到后端运行控制台。

旧的 `REGISTRATION_SMS_CONSOLE_ENABLED` 已由上述单一通道配置替代，发送器只会注册一个。

## 注册流程

1. 前台点击“获取验证码”，调用现有 `POST /api/auth/register/code`。
2. 后端检查手机号、配置和限流，生成随机六位验证码。
3. Redis 按手机号原子检查60秒冷却、最近一小时10条发送额度，并预占本次额度，写入验证码、剩余尝试次数及300秒有效期。
4. 调用阿里云 `SendSmsVerifyCode`，传入 Redis 中的同一份验证码：`{"code":"...","min":"5"}`。
5. 用户提交注册，仍使用本地 Redis Lua 脚本校验；成功即消费，五次错误后失效。

这是发送指定验证码的接入方式，不使用阿里云生成验证码的占位符，因此不调用 `CheckSmsVerifyCode`。
阿里云明确拒绝时，仅撤销本次发送对应的验证码和冷却，并退回本次预占额度；网络结果未知时保留原有状态和额度，避免供应商已受理时重复发送。
SDK客户端复用，连接超时5秒、读取超时10秒，关闭客户端自动重试。

### 重发与发送额度

- 同一手机号两次发送至少间隔60秒，最近一小时最多发送10条；不同手机号不共享发送额度。
- 冷却期间重复点击、参数错误、配置缺失、供应商明确拒绝均不会消耗短信发送额度。
- 优先使用 Redis 时间及有序集合统计滚动一小时，预占额度与写入验证码在同一 Lua 脚本中完成，多实例共享限制；不支持命令复制模式的旧版 Redis 回退使用应用时间。
- 第10条正常发送，并提示“该手机号每小时最多发送10条验证码，当前额度已用完”。额度耗尽后返回429、剩余等待秒数及明确的提示。
- 前端按服务器返回的等待时间禁用重发按钮；更换手机号可使用该号码自己的额度，长等待时间以分钟显示。
- `POST /api/auth/register/code` 不再使用原来的 IP 每小时请求计数；旧的 `rate:register-code:*` 键自动过期，不会继续拦截，也不需要清空 Redis。
- 提交注册接口自己的 IP 限流保持独立。验证码有效期仍为5分钟，重新发送后以最新验证码为准。

## 部署与验证

1. 在号码认证控制台开通短信认证，并确认用于调用的 RAM 身份具备 `dypns:SendSmsVerifyCode` 权限。
2. 服务器通过环境变量提供上述配置，或把外部 `.env` 放在进程工作目录／用 `APP_ENV_FILE` 指定路径。
3. 更新 JAR 并重启后端，前台获取验证码接口无需改动，不需要数据库迁移。
4. 使用自己的手机号点击“获取验证码”，确认手机收到验证码后完成注册。

本轮自动化验证使用模拟阿里云客户端，没有发送真实短信，也没有用本地 AccessKey 调用阿里云接口。

```sh
mvn -o -Dmaven.test.redirectTestOutputToFile=true package
```

完整验证短信额度的 Lua 行为时，在 `backend` 目录执行以下命令（需要本机 Redis，使用随机隔离的测试键并在结束时清理）：

```sh
mvn -o -Dregistration.redis.integration=true -Dmaven.test.redirectTestOutputToFile=true package
```

在 `frontend` 目录执行 `node scripts/verify-registration-code.mjs` 可验证真实注册弹窗的重发、额度提示及429倒计时；
浏览器测试需要可用的 Playwright／Chromium，所有接口均为模拟，不会发送短信。

### `Forbidden.NoPermission` / `Forbidden.RAM`（HTTP 403）

这类错误表示调用身份缺少号码认证的发送权限，接口已经能够连接到阿里云。项目对前台返回 HTTP 503 和固定提示
“短信服务未获得发送权限，请联系管理员”；阿里云错误码保留在后端日志中。

由阿里云主账号或具备授权权限的管理员，在 **RAM 控制台 → 身份管理 → 用户 → AccessKey 所属用户 → 权限管理 → 新增授权** 中授权。
应选择实际用于后端调用的 AccessKey 所属用户；授权登录控制台的其他用户不会修复后端调用权限。

**授权范围选择“整个云账号”**。`SendSmsVerifyCode` 的资源类型是全部资源 `*`，号码认证不支持资源组范围授权。
即使自定义策略已经写了 `"Resource": "*"`，在附加策略时选择“指定资源组”，也不会因此获得账号范围的发送权限。
如果之前选了指定资源组，请把同一份最小权限策略重新授权到“整个云账号”范围。
这里的“整个云账号”限定的是授权作用范围，允许的操作仍只有策略中列出的发送接口。

本项目仅调用发送接口，可创建并附加以下最小权限自定义策略：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["dypns:SendSmsVerifyCode"],
      "Resource": "*"
    }
  ]
}
```

也可授予系统策略 `AliyunDypnsFullAccess`，但其权限范围比上面的自定义策略更大。
发送验证码需要写权限，只读策略不能替代；普通短信产品的权限也不能替代号码认证权限。
授权生效后重新获取验证码即可，无需因授权变更而更换密钥或重启后端；若修改了 `.env`，则需要重启。

依据：[号码认证身份管理／常见权限问题](https://help.aliyun.com/zh/pnvs/security-and-compliance/identity-management)、
[SendSmsVerifyCode 授权信息](https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-sendsmsverifycode)、
[号码认证授权粒度](https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-ram)、
[资源组授权生效条件](https://help.aliyun.com/zh/ram/use-cases/use-ram-to-create-and-authorize-resource-groups)。

密钥文件、完整手机号、验证码和 SDK 的原始请求／异常正文均不会写入真实短信通道日志。

如果 VS Code 启动报 `NoClassDefFoundError: com/aliyun/tea/TeaException`，先刷新 Maven 项目，使 IDE 的运行 classpath 同步新增的 SDK 和传递依赖。
工作区配置 `java.configuration.updateBuildConfiguration` 应为 `automatic`。修改后可执行 `Java: Reload Projects` 再启动；
也可在项目根目录执行 `mvn -f backend/pom.xml spring-boot:run`，由 Maven 生成完整运行 classpath。

官方依据：[发送接口](https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-sendsmsverifycode)、
[个人开发者接入](https://help.aliyun.com/zh/pnvs/use-cases/sms-verify-for-individual-developers)、
[赠送签名变更通知](https://help.aliyun.com/zh/pnvs/product-overview/sms-authentication-signature-change-notification)。
