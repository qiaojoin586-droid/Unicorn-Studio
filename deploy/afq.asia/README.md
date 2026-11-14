# www.afq.asia 的 Docker + HTTPS 部署

本方案使用两个新容器：`unicorn-nginx` 提供 HTTPS、主站和管理端静态页面；`unicorn-backend` 运行 Java 21 后端。
它们加入已有的 `app-network`，继续使用 `mysql-server:3306`、`redis-server:6379`。
Compose 不会新建或删除你的 MySQL、Redis 容器。

后端基础镜像默认使用 `eclipse-temurin:21-jdk-noble`（Java 21 / Ubuntu 24.04），并安装发行版提供的 `mysql-client`，在构建时执行 `mysqldump --version` 验证备份工具；已有兼容镜像可按 §4.2 通过 `JAVA_BASE_IMAGE` 构建参数复用。后台备份需要**后端容器内部**的客户端；只在宿主机或 `mysql-server` 容器里安装不能解决后端的 `Cannot run program "mysqldump"`。镜像标签与客户端包分别参见 [Adoptium](https://adoptium.net/installation/containers) 和 [Ubuntu](https://packages.ubuntu.com/noble/mysql-client)。

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
同时会将 `backend/sql/server-migration` 中整理好的两份部署 SQL 复制到 `sql/`：`01-init.sql` 用于全新空库，`02-upgrade.sql` 用于已有业务库。二选一执行；它们不在 `html` 中，也不会随应用启动自动执行。
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
    run-sql.sh
    sql/
      01-init.sql
      02-upgrade.sql
    html/                       # 由脚本准备，包含 admin/
    data/uploads/               # 历史上传文件；后续始终保留
    data/backups/               # 后台数据库备份；后续始终保留
```

上传时需要包含隐藏文件 `.dockerignore`。后端构建上下文只允许 JAR 和 Docker 构建文件，不包含源码及 `.env`。
若你此前在源码 `application.yml` 中写过真实生产密码，请移到服务器外部配置，再重新打包。

## 2. 在服务器填写运行配置

```bash
cd /opt/Unicorn-Studio/deploy/afq.asia
mkdir -p secrets certs data/uploads data/backups logs/backend logs/nginx
chmod 700 data/backups
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
后端在容器内监听 `0.0.0.0:8080`，公网入口统一经过 Nginx。上传目录、备份目录和日志目录均已持久化到宿主机。Compose 显式设置 `APP_BACKUP_DIR=/app/data/backups` 和 `APP_BACKUP_MYSQLDUMP_PATH=/usr/bin/mysqldump`，已有的外部配置无需为这次修复替换。

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

### 4.1 选择并执行一份数据库 SQL

更新 JAR 不会自动更新表结构。当前结构基线为 25 表、246 字段、69 个索引、4 个外键，已经包含 `public_user.phone`、手机号唯一索引和 `auth_version`。

| 服务器数据库状态 | 执行方式 |
| --- | --- |
| 全新数据库或已有空库 | `bash run-sql.sh init` |
| 已有表和数据，包括发送验证码缺少 `phone` 字段 | 先备份，再 `bash run-sql.sh upgrade` |

不要同时执行两份；已有业务库不能执行初始化。脚本使用 MySQL 批处理并在错误时停止，不要自行改为交互式 `SOURCE` 或添加 `--force`。

以下命令在当前 `deploy/afq.asia` 目录执行，默认数据库 `unicorn_studio`、MySQL 容器 `mysql-server`、MySQL 用户 `root`。容器或用户不同可通过 `DEPLOY_MYSQL_CONTAINER`、`DEPLOY_MYSQL_USER` 覆盖；其他数据库名需先统一修改选定 SQL 内的 `USE` 等库名。

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
bash run-sql.sh upgrade
```

运行时按提示输入 MySQL 密码；密码不会写入 SQL 或命令参数。升级脚本只补缺失对象，不自动修改已有列类型，不覆盖已有同键配置，也不恢复管理员已撤销的旧授权。已有类型等有差异时会输出明细并中止，避免误报成功。重复手机号导致唯一索引创建失败时需先人工核实。

确认最终输出 `DEPLOYMENT_SQL_OK` 且退出码为 0 后，再重试获取验证码或启动后端。单纯补齐注册字段无需重新打 JAR 或重启 MySQL、Redis。

两份 SQL 的系统默认值来自仓库，不是本地数据库的业务数据。它们不导入本地作品、用户、实际页面配置或 AI Key；新库的真实内容需在后台维护。原始脚本已归档，旧私密导出保留在 `backend/sql/server-migration/private/`，不属于部署文件，也不应重新导入已有业务库。详细说明见 `backend/sql/server-migration/README.md`。

### 4.2 构建与启动容器

后端构建前可先核对本地 Java 镜像；此命令只读取本地信息，不下载镜像：

```bash
docker image inspect eclipse-temurin:21-jdk-noble --format '{{.Id}} {{.Os}}/{{.Architecture}}'
```

首次使用此配置时，需要下载 `21-jdk-noble` 基础镜像，并在构建时访问 Ubuntu 软件源安装 MySQL 客户端；只缓存旧的 `21-jdk` 标签并不代表已缓存新标签。后续构建可复用缓存。本次修复只涉及镜像和 Compose 配置，无需重新打 JAR，也无需重启 Docker 或数据库容器。

如果下载 `21-jdk-noble` 时镜像层长时间停在 `0B`，而服务器已缓存 `eclipse-temurin:21-jdk`，先检查缓存镜像内的系统版本（此命令禁止拉取镜像）：

```bash
docker run --rm --pull=never --entrypoint sh eclipse-temurin:21-jdk -c 'cat /etc/os-release'
```

确认输出为 `ID=ubuntu` 且 `VERSION_ID="22.04"`、`"24.04"` 或 `"26.04"` 后，使用下面这条命令替换普通的后端构建命令。Dockerfile 统一安装 `mysql-client`，由软件源选择对应的版本：Ubuntu 22.04 / 24.04 提供 8.0，Ubuntu 26.04 提供 8.4（参见 [Ubuntu 24.04](https://packages.ubuntu.com/noble/mysql-client)、[Ubuntu 26.04](https://packages.ubuntu.com/resolute/mysql-client)）。只安装后端所需的客户端，不会升级已有 `mysql-server` 容器。其他发行版或版本需先确认客户端包兼容性。

```bash
docker compose --progress=plain build --build-arg JAVA_BASE_IMAGE=eclipse-temurin:21-jdk backend
```

请先上传支持 `ARG JAVA_BASE_IMAGE` 且安装 `mysql-client` 的最新 `backend/Dockerfile`；旧文件可能忽略未使用的构建参数，或仍固定安装不适用于 Ubuntu 26.04 的 `mysql-client-8.0` 包。不要加 `--pull`，以便复用本地缓存。此选项只省去新基础镜像的下载，安装客户端仍需访问 Ubuntu 软件源。以后重建时继续传相同参数。[Docker 构建参数说明](https://docs.docker.com/build/building/variables/)。

Nginx 使用 `nginx:stable-alpine`，服务器最新的镜像列表已包含该标签，可直接复用缓存。

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

### 4.3 修复与验证后台数据库备份

若日志出现 `Cannot run program "mysqldump": ... error: 2 (No such file or directory)`，备份任务尚未开始导出数据库，后端运行环境无法启动客户端。上传更新后的 `backend/Dockerfile` 和本目录 `compose.yaml`，然后在服务器的部署目录执行以下步骤。

旧配置的 `/app/data/backups` 没有挂载到宿主机，可能仍有历史备份留在容器内。**先复制，再重建容器**；若旧容器已被删除，需从已有的宿主机或异地备份找回。

```bash
cd /opt/Unicorn-Studio/deploy/afq.asia
mkdir -p data/backups
chmod 700 data/backups
docker cp unicorn-backend:/app/data/backups/. ./data/backups/
```

如果复制命令报目录不存在，先核对旧的 `app.backup.dir` / `APP_BACKUP_DIR`。确认没有需要迁移的历史文件后再继续；其他复制错误应先处理。然后按顺序运行，任何一步失败都先停止排查：

```bash
docker compose config --quiet
docker compose --progress=plain build backend
docker compose up -d --no-deps --force-recreate backend
docker compose exec backend /usr/bin/mysqldump --version
docker compose exec backend sh -c 'test -w /app/data/backups'
docker inspect unicorn-backend --format '{{range .Mounts}}{{println .Source "->" .Destination}}{{end}}'
```

最后一条输出应包含宿主机 `data/backups` 到 `/app/data/backups` 的挂载。在后台「数据备份」手动创建一次备份，确认状态变为「成功」、能够下载，并检查宿主机文件：

```bash
ls -lh data/backups/
gzip -t data/backups/*.sql.gz
docker compose logs --tail=100 backend
```

`gzip -t` 仅检查压缩包完整性；是否可恢复还需在独立测试数据库中导入验证。定时任务仍默认每日 02:00 执行。数据库备份只包含数据库，不包含上传图片；`data/uploads` 需另行备份。日志中的上传图片 404 是文件缺失问题，安装 `mysqldump` 不会恢复这些文件。

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
