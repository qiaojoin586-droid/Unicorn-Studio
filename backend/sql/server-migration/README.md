# 本地库 → 服务器 MySQL 全量迁移手册

> 本目录只放「迁移到服务器」相关产物。日常增量迁移脚本仍在上一层的 `backend/sql/`。
> 产物生成脚本见 `backend/sql/tools/`，可随时重新生成。

---

## 0. 一句话结论

本地库结构已通过「重建 + 逐行比对」验证：**脚本链能 1:1 复现线上库**（21 表 / 195 列 / 74 索引 / 4 外键，
排序规则 `utf8mb4_0900_ai_ci` 全部一致，连列顺序都一致），且**可重复执行**。
迁移有两条路，**推荐路径 A（整库 dump）**，只有 300 KB 数据，最省事也最不容易出错。

---

## 1. 源库体检结果（本地 `unicorn_studio`）

| 项目 | 实测值 |
|---|---|
| MySQL 版本 | **8.0.42**（`utf8mb4_0900_ai_ci` 需 MySQL 8.0+，服务器务必 8.0 以上） |
| 库默认字符集 | `utf8mb4` / `utf8mb4_0900_ai_ci` |
| 表 / 列 / 索引 / 外键 | 21 / 195 / 74 / 4 |
| 全部数据量 | 约 **300 KB**（纯文本，无大字段） |
| 最大的表 | `visit_log` 1144 行、`sys_permission` 66 行、`sys_role_permission` 58 行、`site_settings` 40 行 |
| 空表 | `comment`、`message`、`public_user`（迁移后仍为空，属正常） |
| 上传文件记录 | `upload_file` 15 行，但磁盘只有 **5 个文件**（见 §5） |
| 权限表中文 | 正常，无乱码（历史乱码已修复过） |

---

## 2. 产物清单

| 文件 | 可入库 | 说明 |
|---|:---:|---|
| `01-schema-full.sql` | ✅ | **全量建表脚本**。幂等、非破坏（无 DROP/DELETE），含种子数据（权限码/角色/site_settings 默认值）。 |
| `02-data.sql` | ❌ | **业务数据导出**。含口令散列、加密 AI Key、IP 哈希 → 已加进 `.gitignore`，切勿提交、切勿外传。 |
| `03-verify.sql` | ✅ | 迁移后校验查询，逐项输出 `OK` / `FAIL`。 |
| `../tools/gen_merged_schema.py` | ✅ | 由 `mysql-init.sql` + V8~V18 生成 `01-schema-full.sql`。 |
| `../tools/gen_data_dump.py` | ✅ | 从本地库导出 `02-data.sql`。 |
| `../tools/verify_merged_schema.py` | ✅ | 静态校验：幂等性 + 结构/列顺序/外键比对。 |
| `../tools/verify_roundtrip.py` | ✅ | 端到端回归：建空库 → 导数据 → 21 表逐行比对。 |

---

## 3. 路径 A：整库 dump 迁移（推荐）

一条命令带走结构 + 数据，不需要 `01` / `02`，服务器上的库与本地**逐字节一致**。

**① 本地导出**（Windows，已确认 `mysqldump` 在 `C:\Program Files\MySQL\MySQL Server 8.0\bin\`）

```bat
mysqldump --default-character-set=utf8mb4 --single-transaction --quick ^
  --set-gtid-purged=OFF --no-tablespaces --hex-blob --routines --events ^
  -h127.0.0.1 -P3306 -uroot -p --databases unicorn_studio > unicorn_studio_full.sql
```

> `--databases` 会带上 `CREATE DATABASE`，服务器上无需预先建库。
> `--set-gtid-purged=OFF` 必加：否则目标库未开 GTID 时报错。

**② 传输**（把 `<server>` 换成服务器地址）

```bash
scp unicorn_studio_full.sql <user>@<server>:/tmp/
scp -r data/uploads          <user>@<server>:/opt/unicorn-studio/data/   # 见 §5
```

**③ 服务器导入**

```bash
mysql --default-character-set=utf8mb4 -h127.0.0.1 -u<user> -p < /tmp/unicorn_studio_full.sql
```

**④ 校验**：`mysql ... unicorn_studio < 03-verify.sql`

---

## 4. 路径 B：脚本重建结构 + 只导数据

适用场景：服务器上**已有库想保留结构**，或不想让 dump 里的 `DROP TABLE` 冲掉东西
（路径 A 的 `--databases` dump 自带 `DROP TABLE IF EXISTS`，会先删表）。

```bash
# ① 建结构（幂等，可重复执行；已存在则不重建、不覆盖数据）
mysql --default-character-set=utf8mb4 -h<server> -u<user> -p < 01-schema-full.sql

# ② 导数据（脚本内不写 USE，库名由命令行给出，方便换库名）
mysql --default-character-set=utf8mb4 -h<server> -u<user> -p unicorn_studio < 02-data.sql

# ③ 校验
mysql --default-character-set=utf8mb4 -h<server> -u<user> -p unicorn_studio < 03-verify.sql
```

**`02-data.sql` 的行为**（已刻意设计，勿随意改）：

1. `SET FOREIGN_KEY_CHECKS = 0` —— RBAC 关联表有 4 个 `NO ACTION` 外键，删表顺序无法两全；
2. 先 `DELETE FROM` 全部 21 张表 + `ALTER TABLE ... AUTO_INCREMENT = 1` ——
   `01` 会先写入种子行（权限/角色/site_settings 默认值），若不清空，`02` 的 INSERT 会撞唯一键；
   而改用 `INSERT IGNORE` 又会「保留脚本默认值、丢掉本地真实值」——**那正是要迁移的东西**，所以必须先清后插；
3. 再插入源库数据（`--complete-insert`，显式列名，不怕服务器端列顺序差异）；
4. 收尾恢复外键检查。

> ⚠️ 第 2 步会**覆盖目标库这 21 张表的全部现有数据**。目标库若有需保留的数据，请先备份。

---

## 5. 上传文件目录迁移（必做）

数据库只存相对路径（`/uploads/yyyy/MM/xxx.ext`），真实文件在**项目根**的 `data/uploads/`
（后端工作目录 = 项目根，不是 `backend/`）。

```
rsync -av --delete data/uploads/ <user>@<server>:/opt/unicorn-studio/data/uploads/
# 无 rsync 时：scp -r data/uploads <user>@<server>:/opt/unicorn-studio/data/
```

**实测：只有 5 个文件是真正被引用的**（合计 7.55 MiB），且本地都在：

| 引用方 | 路径 |
|---|---|
| `carousel` id=1 (home) | `/uploads/2026/09/c625e1a88b974ee893f4836f8e91a4e7.jpg` |
| `carousel` id=9 (home) | `/uploads/2026/09/dff7986c2a9a42eea75f0791b3ec74ea.jpg` |
| `carousel` id=10 (about) | `/uploads/2026/09/c8ca41b0c0484e0d93596216e852b4de.jpg` |
| `carousel` id=11 (works) | `/uploads/2026/09/41f83953a70a48e88de4f426e2adc3c3.jpg` |
| `work` id=2 封面 | `/uploads/2026/09/c158be6a7f8d441b964d928b452e5d10.jpg` |

`upload_file` 表另有 **10 行孤儿记录**，其文件本地已不存在（历史替换未清理）。
它们不被任何内容引用，不影响前台显示；如需清理：

```sql
-- 可选清理：删除磁盘上已不存在的上传记录（先确认，再执行）
DELETE FROM upload_file WHERE url IN (
  '/uploads/2026/08/79c6310882ac489ead2c91dc90417f87.png',
  '/uploads/2026/08/1cb6fc36ba8b458786d245946c8f5ec6.png',
  '/uploads/2026/09/18e082a932d3461e8e0b010755ed689a.png',
  '/uploads/2026/09/5d2f539a6c744081839c8e1834d0bb00.jpg',
  '/uploads/2026/09/42f01b9b6b9d48609a8eb547ec81d602.jpg',
  '/uploads/2026/09/deb4da68c7964ced8dc6bed16345ce26.jpg',
  '/uploads/2026/09/c2c4094bbe3848168a05176962cd7c66.jpg',
  '/uploads/2026/09/047731e9a0094356a4c148a696ff43fd.jpg',
  '/uploads/2026/09/aa4f49d314e543fa84464bdafd2dfb02.jpg',
  '/uploads/2026/09/efe79ef15dbd47019f50f18988f1d0ed.jpg'
);
```

---

## 6. 服务器侧后端配置

`application.yml` 里**没有默认凭据**，缺失即拒绝启动（`SecretsStartupValidator`）。
模板见 `backend/src/main/resources/application-local.yml.example`。

### 6.1 必须显式配置的项

| 项 | 要求 | 备注 |
|---|---|---|
| `DB_USERNAME` / `DB_PASSWORD` | 非空 | 空密码会被启动校验拦下 |
| `SPRING_DATASOURCE_URL` | 见下 | ⚠️ **`application.yml` 里 URL 硬编码为 `127.0.0.1:3306`**，服务器 MySQL 若非本机必须覆盖 |
| `APP_CRYPTO_SECRET` | ≥32 位随机串 | ⚠️ **必须与源环境一致**，否则迁移过来的 `ai.api_key` 解密失败（见 §7.2） |
| `ADMIN_JWT_SECRET` | ≥32 位 | 与下面那个**必须不同** |
| `PUBLIC_JWT_SECRET` | ≥32 位 | 轮换会使所有前台 token 立即失效 |
| `APP_CORS_ORIGINS` | 见 §6.2 | 漏配 = `/api/**` 全 403 |
| `TRUSTED_PROXY_COUNT` | **`1`** | 生产为 nginx → 后端 1 层代理；留 0 会让全站共用一个限流桶 |
| `REDIS_HOST` / `REDIS_PASSWORD` | 非空 | 缺失时缓存整体降级（不阻断启动） |

### 6.2 CORS（本地出过线上故障，重点）

`app.cors.*` 只作用 `/api/**`，且**仅在请求带 `Origin` 时校验** —— 于是会出现
「curl 200、浏览器 403 `Invalid CORS request`」。另外 **`localhost` 与 `127.0.0.1` 是两个不同 Origin**。

```bash
# 服务器上必须把真实访问源加进去（域名 + IP，带端口）
APP_CORS_ORIGINS=https://your-domain.com,http://<server-ip>:3000
# 生产建议收窄通配模式，或置空串只走精确匹配
APP_CORS_ORIGIN_PATTERNS=
```

### 6.3 Redis：**必须**与本地隔离（否则「图片/附件过一段时间就 404」）

Redis 连接池配置里 `REDIS_DATABASE` 默认 `0`。若服务器与本地**指向同一台 Redis 的同一个 db**，
两套环境的缓存键会互相覆盖。这已在 2026-09-22 实测踩中，症状极具迷惑性：

1. 本地库只有 4 条学习资料，本地实例的列表接口却返回了 **7 条** ——
   顺着查到 `cache:learning:list` 里存的正是**服务器环境**写入的那 7 条；
2. 那 7 条带着服务器的 `coverUrl` / `attachmentUrl`（`/uploads/2026/09/8bb46a63….png` 等），
   而对应文件只存在于服务器磁盘 ⇒ **任何一方渲染对方的数据，图片与附件必然 404**；
3. 更麻烦的是 TTL 会持续续期（两边都在写、都在读），所以它看起来像「**过一段时间才坏**」；
   而「缓存过期就会回源」的直觉恰恰是错的 —— 回源拿到的仍是对方的键。

**现在的双保险（代码层 + 配置层）**：

| 层 | 措施 | 说明 |
|---|---|---|
| 代码 | 缓存键自动带 profile 前缀 | `CacheHelper` 给每个键加 `<profile>:`，如 `local:cache:learning:list` / `prod:cache:carousel:home`。键空间天然分开，**不依赖运维记性** |
| 配置 | 本地固定 `spring.data.redis.database: 1` | 与线上的 db `0` 物理隔离（已落在 `application-local.yml`） |

启动日志会打印一行便于确认：`缓存键环境隔离前缀: 'prod:'`。
历史上已写入的无前缀键不会被再读取（自然过期即可），**无需手工清理**。

要再加一层可用 `CACHE_NAMESPACE`（如 `CACHE_NAMESPACE=unicorn` ⇒ `unicorn:prod:` 前缀）。
迁移后若前台最长 30 分钟不更新，按前缀清一次缓存即可（注意键名已带前缀）：

```bash
redis-cli -h <host> -p 6379 -a <pwd> -n <db> --scan --pattern 'prod:cache:*' | xargs -r redis-cli -h <host> -p 6379 -a <pwd> -n <db> DEL
```

### 6.4 其他

- `SPRING_PROFILES_ACTIVE`：`application.yml` 未指定默认 profile，务必显式设置（如 `prod`），
  否则读不到对应 profile 的覆盖项。
- `MYSQLDUMP_PATH`：后台「备份」功能依赖 `mysqldump`，服务器上需装 MySQL 客户端或指向绝对路径。
- `FILE_UPLOAD_DIR`：上传根目录，同时是 `FileService` 的**写入目录**与 `/uploads/**` 静态映射的**读取目录**。
  解析优先级（`UploadProperties`，单一来源）：
  1. 配置值本身是**绝对路径** → 原样使用（**推荐**，与启动方式彻底无关）；
  2. 设了 `APP_HOME` → 相对它解析（`backend/start.sh` 会导出）；
  3. 否则相对**启动工作目录(cwd)** 解析；**但若 cwd 下那个目录为空、而 jar 同级同名目录里有文件，
     会自动改判到 jar 同级目录**（并打 WARN 说明改判了什么）。
     这一条是为「直接用 `java -jar` 部署」兜底的 —— 那种场景 cwd 取决于当时在哪敲命令
     （`cd /opt/app && java -jar app.jar` 与 `java -jar /opt/app/app.jar` 的 cwd 可以差很远），
     而 jar 的位置是稳定的。实测：从任意目录 `java -jar <别处>/app.jar`，图片都能正常访问。
  ⚠️ **若上传文件不在上述任一位置**（如 `/srv/unicorn/data/uploads`），必须显式指定：
  `java -Dapp.upload.dir=/srv/unicorn/data/uploads -jar app.jar` 或
  `export FILE_UPLOAD_DIR=/srv/unicorn/data/uploads`。
  两者不一致时会出现**「图片文件还在磁盘上、数据库记录也没变，
  前端主站却返回 `{"code":404,"message":"资源不存在"}`」** —— 这个 JSON 是后端
  `NoResourceFoundException` 的输出（区别于 nginx 的 HTML 404），等价于「后端在它以为的目录里没找到文件」，
  和图片是不是真的没了无关。
  **排查只需看启动日志里这三行**（`UploadProperties` 输出，搜 `UploadProperties` 即可）：
  ```
  静态资源映射 /uploads/** 与上传落盘共用同一目录: file:/opt/unicorn-studio/data/uploads/（基准: ...）
  上传目录自检通过（存在/可读/可写）：/opt/unicorn-studio/data/uploads
  ```
  另外，**任何一个 `/uploads/**` 请求 404 时，后端会直接打出结论**（`UploadNotFoundInterceptor`），
  区分两种情形并把实际路径写清楚：
  - `上传文件 404（配置问题）` —— 文件在别处找到了，会打印它真正所在的路径 ⇒ 改配置或迁文件；
  - `上传文件 404（文件缺失）` —— 哪个候选目录里都没有 ⇒ 多半被清理或被部署动作删掉了。
  上传目录**不参与健康检查**，服务会照常返回健康状态，所以不能靠 `/actuator/health` 判断这一项。
- `LOG_DIR`：日志目录，默认 `./logs`，**同样相对启动工作目录**
  （详见 `application.yml` 的 `logging.file.path` 注释）。**强烈建议显式给绝对路径**：
  `export LOG_DIR=/opt/unicorn-studio/logs`；用 `backend/start.sh` 启动时脚本已自动设为
  `<应用目录>/logs`。否则「从别的目录 `java -jar`」会把日志写到那个目录下，
  目录不可写时更是**完全没有文件日志**（logback 只会把错误写进自己的内部状态，不报错给你）。
  该变量同时决定 heapdump 落点与 `RetentionService` 清理 admin 审计文件的目录。
- 日志级别：`application-prod.yml` 把 `com.unicorn.studio` 收到 `WARN`（业务包），
  但**接口访问日志（`[ACCESS]` 行）走独立的 `ACCESS_LOG` logger，仍为 INFO** ——
  它在 `logs/info/yyyy-MM-dd/app-info.log` 里，每行含方法/路径/状态码/客户端 IP/耗时，
  是「接口到底有没有被调到、返回了几百」的第一现场。要连它一起关就单独设
  `logging.level.ACCESS_LOG=WARN`（历史上它挂在 `com.unicorn.studio.common.log` 下，
  被那条 WARN 顺带压掉，表现为「服务器上 logs/ 里没有接口日志」，请不要改回去）。

---

## 7. 已知坑

### 7.1 用脚本建库需 MySQL 8.0+
`01-schema-full.sql` 显式用 `utf8mb4_0900_ai_ci`（对齐实录库）。MySQL 5.7 不认识该排序规则，会直接报错。

### 7.2 AI Key 是加密存储的
`site_settings` 里 `ai.api_key` 为 **AES-256-GCM 密文**（实测 113 字节），密钥来自环境变量
`APP_CRYPTO_SECRET`。服务器必须配**同一个** `APP_CRYPTO_SECRET`，否则后台「AI 配置」读取时解密失败。
若确实要换密钥：迁移后在后台「AI 配置」页**重新填写 API Key**。

### 7.3 `sys_user.refresh_token` 会一起搬过去
迁移后源库的 refresh token 在服务器上仍可能有效。导入完建议清一次：

```sql
UPDATE sys_user SET refresh_token = NULL, refresh_token_expires_at = NULL;
```

### 7.4 `01-schema-full.sql` 与 `mysql-init.sql` 的分工
`mysql-init.sql` **含 `DROP TABLE`**，是「本地重置」用的破坏性脚本，**不要拿到服务器上跑**。
服务器上用 `01-schema-full.sql`（非破坏、幂等）。

### 7.5 本次修正的一处历史缺陷

`mysql-init.sql` 原先把 V11 追加的三列（`carousel.page_key` / `media_type` / `video_url`）
写在了 `created_at` / `updated_at` **之前**，而实录库是 V11 `ALTER TABLE ADD COLUMN` 追加的，
这三列排在末尾。二者列名相同但 `ORDINAL_POSITION` 不同，导致新建库与实录库**列顺序**不一致
（`mysqldump --complete-insert` 出来的 INSERT 列序不同）。已把这三列在 `mysql-init.sql` 中移到末尾，
现在 21 张表列顺序与实录库完全一致。

> V19 补 `public_user.ai_enabled` 时按同一规则处理：该列在原建表语句里也是**追加在末尾**，
> 并在 `01-schema-full.sql` 的 `public_user` 表定义处写了同样的 `★ 必须排在 created_at 之后` 注释
> —— **新加列时务必沿用这条规矩**，否则脚本建的库与实录库列顺序就会不一致。

### 7.6 「上传的图片 404」排查清单（按顺序，5 分钟定位）

这个故障出现过两次、症状完全一样，且**服务本身始终是健康的**，所以务必按证据定位而不是猜：

1. **先看 404 的响应体长什么样**（决定是哪一层 404，别跳步）：
   ```bash
   curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://<host>/uploads/2026/09/<file>.jpg
   curl -s http://<host>/uploads/2026/09/<file>.jpg | head -c 200
   ```
   | 响应 | 出自 | 含义 |
   |---|---|---|
   | `{"code":404,"message":"资源不存在",...}` | **后端** | 后端在它以为的目录里没找到文件 → 走第 2 步 |
   | HTML（含 `<title>404 Not Found`） | **nginx** | `/uploads/` 没被代理/alias 到后端 → 查 nginx 配置 |
   | 纯文本 `Not Found` | **Node 生产服务** | `frontend/server/index.js` 静态分支命中 → 该路径没走 `/uploads/` 代理分支 |
2. **看后端启动日志里的上传目录两行**（`grep UploadProperties`）：记下它实际用的绝对路径。
3. **确认文件在不在那个路径下**：
   ```bash
   ls -l /opt/unicorn-studio/data/uploads/2026/09/ | head
   find / -name '<file>.jpg' -path '*uploads*' 2>/dev/null   # 文件其实在别的目录？
   ```
   - 文件在**另一个**目录 → `FILE_UPLOAD_DIR` 与写入时不一致：把文件整体迁到日志里那个路径，
     或把 `FILE_UPLOAD_DIR` 改回原路径后重启。
   - 文件**确实不在任何地方** → 见第 4 步（多半是被部署动作清掉了）。
4. **确认上传目录没有被部署动作清掉**：`data/` 在 `.gitignore` 里，
   所以 `git clean -fdx`、或「重新 clone 到同一目录」都会**删掉全部上传文件**，
   而数据库里的 `/uploads/...` 记录仍在 → 全部 404。部署脚本里不要对应用目录执行 `git clean -fdx`；
   上传目录应放在仓库之外（如 `/srv/unicorn/data/uploads`）并显式配 `FILE_UPLOAD_DIR`。
5. **确认目录可读**：`namei -l /opt/unicorn-studio/data/uploads/2026/09` ——
   后端进程（`start.sh` 里的用户）必须对该路径每一级都有 `x`，对文件有 `r`。
6. **确认数据库里的 URL 与磁盘一致**：
   ```sql
   SELECT id, page_key, image_url FROM carousel WHERE status = 1;
   SELECT url FROM upload_file ORDER BY id DESC LIMIT 10;
   ```
   `image_url` 形如 `/uploads/yyyy/MM/<uuid>.jpg`；把文件名拼到上传目录后面 `ls` 一下即可。
   （若前台拿到的是 `http://...` 绝对域名，说明该条是手工填的外链，不是上传产物，另当别论。）

---

## 8. 复现与回归

改动任何 SQL 脚本后，建议跑一遍（需要本地 MySQL 可用，脚本会自动建/删临时库 `unicorn_verify_scratch`、`unicorn_rt_scratch`）：

```bat
python backend\sql\tools\gen_merged_schema.py      :: 重新生成 01
python backend\sql\tools\gen_data_dump.py          :: 重新生成 02
python backend\sql\tools\verify_merged_schema.py   :: 幂等性 + 结构/列序/外键
python backend\sql\tools\verify_roundtrip.py       :: 21 表逐行数据比对
```

**最近一次运行结果（可通过证据）**：

- `verify_merged_schema.py`：两遍执行均 `rc=0`（幂等）；列 194/194、索引 74/74、表排序规则 21/21、
  列顺序 0/21 差异、外键 4/4 —— 全部 `OK`。
- `verify_roundtrip.py`：**21/21 张表数据逐行完全一致**（含 `visit_log` 1144 行、
  `site_settings` 40 行、`sys_permission` 66 行）；`03-verify.sql` 无报错。

> 说明：结构脚本只保证「结构 + 种子默认值」。源库 `site_settings` 比脚本多 4 个键
> （`basic.icp`、`content.comment_enabled`、`seo.work.1`、`seo.work.2`），那是通过后台界面写的运行时数据，
> 由 `02-data.sql` 承载 —— 这也是为什么迁移必须导数据、而不只是跑结构脚本。

---

## 9. 本地构建与打包（含「测试全部报 MockMaker」的处置）

```bat
cd backend
mvnw.cmd clean package          :: 含测试；要跳过测试用 -DskipTests
```
> ⚠️ **不要长期用 `-DskipTests` 打包**。本项目已有过一次教训：改了
> `CacheHelper` 的构造器签名后，只用 `-DskipTests` 打包，编译与启动都正常，
> 但 `CacheHelperTest` 已经编译不过 —— 直到正式打包时才暴露。

### 9.1 环境前提

| 项 | 要求 |
|---|---|
| `JAVA_HOME` | 必须指向 JDK 21（只把 `java` 放进 PATH 不够，`mvnw` 会直接报 "JAVA_HOME is not defined correctly"） |
| 离线构建 | 依赖已缓存时可加 `-o`：`mvnw.cmd -o clean package` |
| 本地配置 | `src/main/resources/application-local.yml`（已 gitignore）需含真实 DB 口令；缺失则用 `-Dspring.datasource.password=...` 临时覆盖 |

### 9.2 「Could not initialize plugin: interface org.mockito.plugins.MockMaker」

**现象**：一次性几十个测试全红，报
`MockitoInitializationException: It appears as if your JDK does not supply a working agent attachment mechanism`
→ `Caused by: Could not self-attach to current VM using external process`。
看着像测试代码写错了，其实**与测试代码无关**：Mockito 的内联 mock maker 需要
Byte Buddy 以 Java agent 形式挂载，它先尝试「自附加」，而这条路在 JDK 21 + Maven
命令行下常常不通（IDEA 自己的测试运行器会替你挂 agent，所以**同一套代码在 IDE 里绿、
在命令行里红**）。

**处置（已固化在 `pom.xml`，无需手工操作）**：用 `dependency-plugin` 解析出
`byte-buddy-agent` 的 jar 路径，再由 `surefire` 通过 `-javaagent` 传给测试 JVM：

```xml
<plugin>
  <artifactId>maven-dependency-plugin</artifactId>
  <executions><execution>
    <id>resolve-byte-buddy-agent</id><phase>initialize</phase><goals><goal>properties</goal></goals>
  </execution></executions>
</plugin>
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>-javaagent:@{net.bytebuddy:byte-buddy-agent:jar}</argLine>
  </configuration>
</plugin>
```

**最近一次验证**：`mvnw.cmd -o clean package` → `Tests run: 87, Failures: 0, Errors: 0` → `BUILD SUCCESS`。

### 9.3 关于 `mvnw`

仓库此前只有 `mvnw` / `mvnw.cmd` 脚本、**缺少 `.mvn/wrapper/maven-wrapper.properties`**，
导致 `mvnw` 每次都去联网重下 Maven，内网/离线环境直接失败。
现已补齐（wrapper 3.3.4 的 `only-script` 形态，不再依赖 `maven-wrapper.jar`），
并实测 `mvnw.cmd -o clean package` 可离线跑通。

