# www.afq.asia 的 Docker + HTTPS 部署

本方案使用两个新容器：`unicorn-nginx` 提供 HTTPS、主站和管理端静态页面；`unicorn-backend` 运行 Java 21 后端。
它们加入已有的 `app-network`，继续使用 `mysql-server:3306`、`redis-server:6379`。
Compose 不会新建或删除你的 MySQL、Redis 容器。

后端基础镜像使用 `eclipse-temurin:21-jdk`，与服务器已经缓存的镜像标签一致。JDK 包含运行 JAR 所需的 Java 环境，可以直接用于本项目，无需另外下载 `21-jre`。

访问地址：主站 `https://www.afq.asia/`，管理端 `https://www.afq.asia/admin/`。
`http://afq.asia`、`http://www.afq.asia`、`https://afq.asia` 统一跳转到 `https://www.afq.asia`。

## 1. 本地构建并准备文件

在项目根目录运行以下一条命令。脚本会依次构建后端、主站、管理端，检查每一步退出码，全部成功后才准备部署文件：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/afq.asia/build.ps1
```

支持 Windows PowerShell 5.1 和 PowerShell 7；脚本使用纯 ASCII 源码，避免旧版 PowerShell 把 UTF-8 中文读成乱码后解析失败。
管理端构建会自动使用本项目中独立的临时目录，并保留原有的类型检查、Vite 构建及全部产物检查。

也可逐条手动执行；PowerShell 下 Maven 的 `-D` 参数应整体加引号。遇到任何一步失败，先修复，不要继续准备部署文件：

```powershell
mvn -f backend/pom.xml "-Dmaven.test.redirectTestOutputToFile=true" package
npm --prefix frontend run build
npm --prefix admin run build
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/afq.asia/prepare-static.ps1
```

构建前主站 API 地址保持默认的同源 `/api/`，不要把 `VITE_API_BASE` 设置为 `localhost` 或 HTTP 服务器 IP。
同域的管理端无需设置独立主站地址，生产环境会自动使用 `/` 作为预览入口。

准备脚本只将主站 `index.html`、`assets`、`robots.txt`、`sitemap.xml`，以及管理端 `dist` 的入口和资源复制进 `html`。
它会复制项目根目录下现有 `data/uploads`，不会删除原文件。图片、二维码、动态壁纸等历史上传资源必须一起迁移。
同时会将手机号注册所需的 V22、V23 增量 SQL 从 `backend/sql/server-migration` 复制到部署目录的 `sql/`，供已有数据库升级使用。这些 SQL 不会放进 `html`，也不会在应用启动时自动执行。
脚本不会将 Node 服务源码、构建脚本、密码或本地 `.env` 放入站点目录。

将以下目录结构上传到服务器，例如 `/opt/Unicorn-Studio/`，保持相对位置：

```text
/opt/Unicorn-Studio/
  backend/
    Dockerfile
    .dockerignore
    target/unicorn-studio-1.0.1.jar
  deploy/afq.asia/
    compose.yaml
    nginx.conf
    application-prod.yml.example
    backend.env.example
    sql/
      V22-public-user-phone.sql
      V23-public-user-edit.sql
    html/                       # 由脚本准备，包含 admin/
    data/uploads/               # 历史上传文件；后续始终保留
```

上传时需要包含隐藏文件 `.dockerignore`。后端构建上下文只允许 JAR 和 Docker 构建文件，不包含源码及 `.env`。
若你此前在源码 `application.yml` 中写过真实生产密码，请移到服务器外部配置，再重新打包。

## 2. 在服务器填写运行配置

```bash
cd /opt/Unicorn-Studio/deploy/afq.asia
mkdir -p secrets certs data/uploads logs/backend logs/nginx
cp -n application-prod.yml.example application-prod.yml
cp -n backend.env.example secrets/backend.env
chmod 600 application-prod.yml secrets/backend.env
```

编辑 `application-prod.yml`：填写已有 MySQL 用户、密码和 Redis 密码，核对数据库名。
这份文件由后端容器只读挂载，后续更新 JAR 时无需重填密码。

编辑 `secrets/backend.env`：

- `ADMIN_JWT_SECRET`、`PUBLIC_JWT_SECRET` 使用不同的随机值，各至少32字符。可分别执行 `openssl rand -hex 32` 生成。
- `APP_CRYPTO_SECRET`：迁移已有数据库时，填写原先使用的加密密钥，不能随意换新，否则已加密保存的 AI API Key 无法解密。仅全新数据库才生成新值。
- 阿里云短信的 AccessKey ID、Secret、当前可用签名及模板：从你现有的配置复制。源码里的 `.env` 已被排除在 JAR 外，不会自动进入容器。

项目其余配置仍来自 JAR 内的 `application.yml`，外部文件只覆盖部署必需项。
后端在容器内监听 `0.0.0.0:8080`，公网入口统一经过 Nginx。上传目录和日志目录均已持久化到宿主机。

## 3. DNS、证书和80/443端口

在域名解析控制台设置两条 A 记录，指向本服务器公网 IPv4：

| 主机记录 | 类型 | 记录值 |
| --- | --- | --- |
| `www` | A | 服务器公网 IP |
| `@` | A | 服务器公网 IP |

在阿里云安全组和服务器防火墙中允许入站 TCP 80、443。

从阿里云证书控制台下载 **Nginx 格式**的证书，使用其中的 `.pem` 证书链与匹配的 `.key` 私钥。
上传到服务器并按本配置命名为：

```text
deploy/afq.asia/certs/afq.asia.pem
deploy/afq.asia/certs/afq.asia.key
```

证书需覆盖 `www.afq.asia` 和 `afq.asia`；你提供的截图显示两者都在证书域名列表中。
私钥只保留在服务器，不要放到 `html`、Git 或聊天中。

```bash
chmod 600 certs/afq.asia.key
openssl x509 -in certs/afq.asia.pem -noout -dates -ext subjectAltName
```

截图中的**证书有效期截至2027-02-09**，与订阅到期时间不同；以后按实际证书的 `notAfter` 续签并替换文件。

你提到宿主机已安装 Nginx，先检查80、443是否被占用：

```bash
sudo ss -lntp
```

如果宿主机 Nginx 只为本项目服务，可在切换时停止它，让容器接管80、443。
若它还服务其他网站，先保留现有入口并合并规划；不要直接停掉，也不要让两个 Nginx 同时绑定相同端口。
本 Compose 按“容器直接接管80、443”配置，没有自动停止宿主机服务。

## 4. 启动与检查

已有网络和数据库容器应能看到：

```bash
docker network inspect app-network
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
```

检查 `mysql-server`、`redis-server` 都在 `app-network` 中，且现有数据库表和数据已准备好。
不要为部署应用重新初始化或覆盖现有数据库。

### 4.1 已有数据库的手机号注册升级

更新 JAR 不会自动更新表结构。当前注册代码需要 `public_user.phone`、手机号唯一索引和 `public_user.auth_version`。如果发送验证码时报 `Unknown column 'phone' in 'where clause'`，说明后端连接的库尚未完成 V22；同时核对是否连接到了预期的数据库。

以下示例假设数据库名为 `unicorn_studio`、MySQL 容器名为 `mysql-server`，并使用有备份、ALTER 和权限表写入权限的 MySQL `root` 用户。数据库名或用户不同则替换为实际值；密码在提示时输入，不写到命令行。

先备份现有数据库，并将备份复制到宿主机的非公开目录：

```bash
mkdir -p backups
chmod 700 backups
docker exec -it mysql-server mysqldump --default-character-set=utf8mb4 --single-transaction --no-tablespaces --set-gtid-purged=OFF -u root -p --databases unicorn_studio --result-file=/tmp/unicorn-before-registration-upgrade.sql
```

确认上一条命令成功退出后，再执行：

```bash
docker cp mysql-server:/tmp/unicorn-before-registration-upgrade.sql "./backups/unicorn-before-registration-upgrade-$(date +%Y%m%d-%H%M%S).sql"
chmod 600 backups/*.sql
docker cp sql/V22-public-user-phone.sql mysql-server:/tmp/V22-public-user-phone.sql
docker cp sql/V23-public-user-edit.sql mysql-server:/tmp/V23-public-user-edit.sql
docker exec -it mysql-server mysql --default-character-set=utf8mb4 -u root -p unicorn_studio
```

进入 `mysql>` 后先核对数据库和表结构，再按 V22、V23 顺序执行；如有任何 SQL 错误，停止并排查，不要继续重试注册：

```sql
SELECT DATABASE();
SHOW COLUMNS FROM public_user;
SOURCE /tmp/V22-public-user-phone.sql;
SOURCE /tmp/V23-public-user-edit.sql;
SHOW COLUMNS FROM public_user LIKE 'phone';
SHOW COLUMNS FROM public_user LIKE 'auth_version';
SHOW INDEX FROM public_user WHERE Key_name = 'uk_public_user_phone';
```

核对 `phone` 为可空的 `VARCHAR(11)`、`auth_version` 默认值为 `0`，以及手机号索引的 `Non_unique` 为 `0`。两份脚本会判断字段或指定索引是否已存在；V22 给旧用户保留空手机号，V23 给旧用户设置凭据版本 `0`，不删除、重建或覆盖用户记录。若已有重复的非空手机号，唯一索引会创建失败，需要先人工核实这些记录。

执行成功后可直接重试获取验证码，无需为这次字段补齐重新打 JAR 或重启 MySQL、Redis。该错误发生在查重阶段，日志不能用于判断后续短信接口是否正常。

已有线上数据只执行所需的增量脚本，不要重新导入 `02-data.sql` 或旧整库导出，它们可能覆盖当前数据。全新数据库的完整初始化与其他版本升级见 `backend/sql/server-migration/README.md`。

### 4.2 构建与启动容器

后端构建前可先核对本地 Java 镜像；此命令只读取本地信息，不下载镜像：

```bash
docker image inspect eclipse-temurin:21-jdk --format '{{.Id}} {{.Os}}/{{.Architecture}}'
```

使用本地缓存构建时，不添加 `--pull`。如果正在等待下载旧配置的 `21-jre`，按 Ctrl+C 取消该次构建，上传更新后的 `backend/Dockerfile`，再执行下面的构建命令。无需重新打 JAR，也无需重启 Docker 或数据库容器。

Nginx 仍使用 `nginx:stable-alpine`。服务器截图中的 `registry.cn-hangzhou.aliyuncs.com/acs-sample/nginx:latest` 是另一个镜像，不能视为已缓存本方案所需的 Nginx；后续需单独准备 `nginx:stable-alpine`。

```bash
docker compose config --quiet
docker compose --progress=plain build backend
docker compose run --rm --no-deps nginx nginx -t
docker compose up -d
docker compose ps
docker compose logs --tail=100 backend nginx
```

如果证书、私钥或配置路径错误，`nginx -t` 会报错，应先修正再启动。
后端启动完成后验证：

```bash
curl -I http://www.afq.asia
curl -I https://www.afq.asia
curl -I https://www.afq.asia/learning
curl -I https://www.afq.asia/admin/
curl https://www.afq.asia/api/join
```

确认 HTTP 跳转到 HTTPS，浏览器证书有效，直接刷新主站与管理端深层路由正常。
然后在浏览器检查登录、上传图片、社区二维码、AI 流式回复和后台未保存预览。
本配置保留 `/api/`、`/uploads/`，关闭 API 代理缓冲，并允许同源 iframe 预览。

## 5. 页脚备案号

主站已在全站页脚最底部展示 `豫ICP备2026050572号-1`，可点击打开 `https://beian.miit.gov.cn/`。
后台 **系统设置 → 基础信息 → 备案号** 可以维护该值；保存后刷新主站即可更新。
未配置或接口暂时不可用时，仍显示你提供的默认备案号，无需为此执行数据库迁移。
当前只显示你已提供的 ICP 备案号；后续如取得公安备案号，再添加对应信息。

## 6. 后续更新

- 前端：先上传新的 `assets`，再替换对应的 `index.html`；保留一段时间的旧哈希资源，避免已打开页面的懒加载失败。
- 后端：先检查该版本的数据库增量升级（手机号注册见 §4.1），上传新 JAR，再执行 `docker compose build backend`、`docker compose up -d backend`。不要删除 `data`、`logs`、证书及运行配置。
- 修改 `application-prod.yml` 或 `backend.env` 后执行 `docker compose up -d --force-recreate backend`，使运行配置生效。
- 修改 Nginx 配置文件后，先重新执行 `docker compose run --rm --no-deps nginx nginx -t`，再 `docker compose up -d --force-recreate nginx`。
- 续签证书只需替换 `certs` 中的文件，检查通过后执行 `docker compose exec nginx nginx -s reload`。

实际密码文件、私钥、准备后的静态文件和持久化目录已由本目录 `.gitignore` 排除。
准备后的 `sql/` 和备份目录 `backups/` 也已排除，SQL 原件仍维护在 `backend/sql/server-migration/`。

参考：[阿里云 Nginx 证书部署](https://help.aliyun.com/zh/ssl-certificate/install-ssl-certificates-on-nginx-servers-or-tengine-servers)、
[ICP备案号展示要求](https://help.aliyun.com/zh/icp-filing/basic-icp-service/support/website-to-add-the-record-number-faq)、
[Docker 外部网络](https://docs.docker.com/compose/how-tos/networking/)。
