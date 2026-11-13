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
- 未发现触发器、存储过程、事件或 CHECK 约束，无需另行创建这些对象。

本轮没有查询业务表记录、没有导出业务数据，也没有执行初始化或升级 SQL。结构检查不等于执行迁移后的运行验证。

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
