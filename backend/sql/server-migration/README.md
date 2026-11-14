# 数据库部署：只选择一份 SQL

数据库名为 `unicorn_studio`，要求 MySQL 8.0.19 或以上的 8.0 版本，字符集 `utf8mb4`、排序规则 `utf8mb4_0900_ai_ci`。不适用于 MySQL 5.7 或 MariaDB。

| 场景 | 执行文件 | 行为 |
| --- | --- | --- |
| 全新数据库或已有空库 | `01-init.sql` | 一次建立当前完整表结构，并写入仓库定义的系统默认配置、权限及内置角色。目标库已有表时拒绝执行。 |
| 已有业务库升级，包括服务器发送验证码缺少 `phone` 字段 | `02-upgrade.sql` | 补齐缺表、可安全新增的列、缺失索引和外键；包含原 V20、V22、V23 所需结构及系统权限。 |

**两份是二选一，不是先执行 01 再执行 02。** 已有业务库先备份再执行 `02-upgrade.sql`。不要使用 `archive/` 或 `private/` 中的文件部署。

两份文件都自带结构核对，无需额外运行第三份校验 SQL。只有所有核对通过且最后输出 `DEPLOYMENT_SQL_OK`，执行才算完成。

## 当前结构基线

2026-11-13 按用户授权，通过只读事务查询本地 MySQL 8.0.42 的 `information_schema` 和 `SHOW CREATE TABLE`，确认：

- 25 张 InnoDB 表、246 个字段、69 个索引对象（100 个索引字段项）、4 个外键。
- 所有表排序规则均为 `utf8mb4_0900_ai_ci`。
- `public_user.phone` 为可空 `varchar(11)`，具有手机号唯一索引；`auth_version` 为非空 `bigint`，默认 `0`。
- 当时未发现触发器、存储过程、事件或 CHECK 约束。2026-11-14 的缓存监听改动另外定义了版本表和触发器，见下文。

上述基础结构核对没有查询业务表记录或导出业务数据。2026-11-14 已在独立测试 MySQL 8.0.42 上验证新版本初始化及连续两次升级，没有操作本地业务库或生产库。

## 手动 SQL 修改后的缓存自动生效

当前两份部署 SQL 都会额外创建 `cache_revision` 表，以及 19 张业务表上的 57 个 `AFTER INSERT/UPDATE/DELETE` 触发器，覆盖 11 个 Redis 读缓存域。定义来源为 `backend/src/main/resources/cache-dependencies.json`，运行时监听与 SQL 生成器共用这一份依赖表。完整结构现在共 26 张表、249 个字段、70 个索引对象及 4 个外键。

链路为：手动 SQL 修改业务表 → 同一事务内推进缓存域版本 → 提交后后端检测版本 → Redis 切换缓存代号 → 下一次读取回源数据库。事务回滚也会回滚版本，不要求手动更新 `updated_at`。监听读取固定数量的版本行，不定时扫描业务内容，不向 Redis 复制整表数据。

- 默认每 1000 ms 检测一次，是秒级最终一致；已有请求可能仍返回它开始时读取的版本，不承诺 SQL 提交瞬间的强一致。
- 缓存数据键会带 `:generation:<随机代号>`。失效时旧代号立即不可达，旧键按原 TTL 回收；慢查询始终回填它开始时捕获的代号，避免重新污染新缓存。组级设置和页面 VO 共用同一缓存域。
- 应用自己的写入仍在提交后立即切换相关代号。活动事务不读写共享读缓存，防止发布未提交值。
- 版本发布使用 Redis Lua 原子完成；旧的并发轮询结果不能把同一 epoch 内的版本倒退。各实例共享当前环境的代号。
- 迁移缺失、触发器不完整或 Redis/数据库监听失败时，数据库派生的 Redis 读缓存停用，自动回源 MySQL 并重试。超过 5000 ms 没有完成监听，也会回源。触发器完整性在首次连接及之后每 60 秒核对。
- 首次启动及故障恢复强制换代，避免重新启用停机或触发器缺失期间的旧缓存。Redis 重启丢失元数据时，先回源，监听重新建立随机代号后才恢复缓存。
- 限流、验证码、刷新令牌、撤销令牌和未落库的浏览量增量属于 Redis 状态数据，不参与版本失效。不要使用 `FLUSHDB` 或删除整个 `cache:*` 来代替本链路。

已有环境的上线顺序：备份 → 上传重新生成的 SQL → 执行 `bash run-sql.sh upgrade`，确认 `DEPLOYMENT_SQL_OK` → 发布新后端 → 确认日志出现“数据库缓存变更监听已就绪（11 个缓存域）”。迁移执行账号需要创建表和触发器的权限；后端账号需要查询版本表并能在 `information_schema.TRIGGERS` 中看到相关触发器（MySQL 要求相应表的 `TRIGGER` 权限）。触发器的 DEFINER 账号也需要持续保有读写版本表的权限。上线时停止使用旧版后端实例，旧版代码不会读取带代号的新缓存。

配置项均位于 `app.cache.database-changes`：`enabled` 默认 `true`，`poll-interval-ms` 默认 `1000`，`max-staleness-ms` 默认 `5000`；对应环境变量为 `CACHE_DATABASE_CHANGES_ENABLED`、`CACHE_DATABASE_CHANGES_POLL_INTERVAL_MS`、`CACHE_DATABASE_CHANGES_MAX_STALENESS_MS`。显式关闭监听会恢复原来依赖应用写入和 TTL 的缓存行为。

此机制覆盖上述表的行级 DML。`TRUNCATE`、替换表、删除触发器、恢复备份等 DDL/运维操作不能依赖行触发器通知；完成这类操作后重新执行升级脚本并重启后端。升级脚本只重建本功能的 `us_cache_*` 触发器，并刷新缓存版本表的 epoch，不删除业务记录或其他触发器。大批量写入会增加版本行更新和行锁成本，应分批提交。MySQL 行触发器的回滚与级联行为见 [触发器说明](https://dev.mysql.com/doc/refman/8.0/en/trigger-syntax.html) 和 [外键说明](https://dev.mysql.com/doc/refman/8.0/en/create-table-foreign-keys.html)。

开发验证：常规 `mvn -o test` 运行缓存回填、事务、空值缓存和降级回归。真实链路测试需独立 MySQL、Redis，先把生成 SQL 中的库名替换为 `cache_coherence_test_<字母数字>` 并在空测试库初始化，再运行：

```powershell
mvn -o '-Dtest=DatabaseCacheChangeIntegrationTest' '-Dcache.database.integration=true' '-Dtest.cache.jdbc-url=jdbc:mysql://127.0.0.1:测试端口/cache_coherence_test_测试后缀' '-Dtest.cache.redis-port=独立Redis端口' test
```

集成测试拒绝非测试库名及 Redis 默认 6379 端口，不加载项目业务凭据。测试数据库使用隔离实例的空密码 root，仅用于本机测试。

## SQL 会修改哪些内容

`01-init.sql` 使用结构核对时的完整 `CREATE TABLE`，不再按 V1→V23 历史顺序重复建表和补列。自增序列的当前计数不随本地库导出。

`02-upgrade.sql`：

- 判断字段是否存在后再添加；`phone`、`auth_version` 已包含在内。旧用户的 ID、邮箱和密码保持原值。
- 无默认值的非空基础字段缺失时，不自动填入虚构值；通过内置结构核对拒绝继续上线。
- 按字段顺序、唯一性和类型识别等价索引，避免旧索引只是名字不同就重复创建。
- 不自动修改、缩窄或删除已有字段，不删除表、索引或业务记录。已有类型、默认值、字符集等有差异时会输出差异并停止，需要人工评审。
- 保留额外的线上表、字段和索引，不强制重排已有字段顺序。
- 只补仓库已有的系统默认值，不覆盖已有同键配置；默认角色授权只应用于本次新增的角色或权限，不恢复管理员已撤销的旧授权。

系统种子经去重后包含 71 条权限、4 个内置角色、40 项默认配置、4 个默认学习分类及相应默认授权。它们来自旧版仓库 SQL，不是从本地数据库查询得到的值。脚本不导入示例作品、新闻、用户、密码、AI Key 或实际站点配置。

因此，全新环境执行后还需在管理端维护真实页面内容、AI 配置和二维码等。现有服务器的数据由升级脚本保留；数据库以外的上传文件仍需单独迁移。

## 推荐：现有 Docker 部署

准备脚本会将这两份 SQL 复制到 `deploy/afq.asia/sql/`，不会放进 Nginx 的 `html/`。上传时同时携带 `deploy/afq.asia/run-sql.sh`。

进入服务器的 `deploy/afq.asia` 目录，已有业务库只运行：

```bash
bash run-sql.sh upgrade
```

全新空库才运行：

```bash
bash run-sql.sh init
```

脚本默认访问 MySQL 容器 `mysql-server`、用户 `root`，运行时提示输入密码，不将密码写进 SQL、Shell 文件或进程参数。可通过 `DEPLOY_MYSQL_CONTAINER`、`DEPLOY_MYSQL_USER` 指定其他容器或用户。

执行采用 mysql 批处理标准输入，出现错误即停止；不要改为交互式 `SOURCE` 或开启 `--force`，否则可能在错误后继续执行，绕过空库保护或结构核对。

## 宿主机 mysql 客户端

已经配置好客户端认证的环境可执行：

```bash
# 全新空库：二选一
mysql --no-defaults --default-character-set=utf8mb4 -h127.0.0.1 -u root -p < 01-init.sql

# 已有业务库：先备份，二选一
mysql --no-defaults --default-character-set=utf8mb4 -h127.0.0.1 -u root -p < 02-upgrade.sql
```

SQL 内默认 `USE unicorn_studio`。使用其他库名时，先统一修改所选文件里的库名，再执行；不要把本地调试密码硬编码进去。

MySQL DDL 会隐式提交；如果升级中断，已经完成的新增字段可能仍然存在，事务不能撤销这些步骤。排查错误后可再次执行升级脚本，它会跳过已补齐对象。唯一索引因重复值创建失败时，需要先人工核实重复记录。

如果旧版生成文件在第 12 行出现 `ERROR 1232: Incorrect argument type to variable 'group_concat_max_len'`，替换为本目录当前版本后重试。MySQL 对这个系统变量要求整型表达式，因此设置和恢复均显式 `CAST(... AS UNSIGNED)`。已在 MySQL 8.0.42 的独立只读连接中复现旧表达式失败，并验证修正后可设置为 8192、恢复为原值；未执行建表、改表或数据写入。该报错位于表结构升级之前，此次失败不会造成部分表升级。

## 文件整理与维护

- `01-init.sql`、`02-upgrade.sql`：当前仅有的两个部署入口。
- `archive/`：保留原全量脚本、V20/V22/V23、旧校验脚本及旧 README，供追溯，不用于新部署。
- `private/02-data.sql`：保留原有私密业务导出，没有读取或改写内容，已被 Git 忽略，不会复制到部署目录。
- `../schema/deployment-schema.json`：只含审核后的结构及仓库默认种子，不含业务数据或连接密码。
- `../tools/build_deployment_sql.py`：只生成文件，没有数据库连接或 SQL 执行能力。

后续更新结构清单后，可在项目根目录用 Python 3.10+ 重新生成：

```bash
python backend/sql/tools/build_deployment_sql.py
```

参考：[SHOW CREATE TABLE](https://dev.mysql.com/doc/refman/8.0/en/show-create-table.html)、[MySQL 批处理](https://dev.mysql.com/doc/refman/8.0/en/mysql-batch-commands.html)、[DDL 隐式提交](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html)。
