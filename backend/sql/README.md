# backend/sql · 脚本索引与执行约定

## 结论速览

- **本地开发重置** → 跑 `mysql-init.sql`（⚠️ 含 `DROP TABLE`，会清库）。
- **服务器首次建库** → 跑 `server-migration/01-schema-full.sql`（幂等、非破坏）。
- **往服务器搬数据** → 见 [`server-migration/README.md`](server-migration/README.md)。
- **加字段/加表** → 三步齐做：① 改 `mysql-init.sql` ② 新增 `mysql-migration-vN-*.sql`
  ③ **对运行中的库执行该增量脚本**。只做 ①② 就重启，会报「Unknown column」。
  （本项目**无 ORM 自动建表**，Flyway 已不再使用，`mysql-init.sql` 是 V1–V7 合并后的唯一基线。）

---

## 1. 脚本清单

### 基线

| 文件 | 内容 | 幂等 | 破坏性 |
|---|---|:---:|:---:|
| `mysql-init.sql` | 全部 21 张表的建表语句 + 种子数据（权限码 / 4 个角色 / `site_settings` 默认值）。由 Flyway V1–V7 合并而来。 | ✗ | ⚠️ **是**（`DROP TABLE`） |

> `mysql-init.sql` 顶部有 `CREATE DATABASE` + `USE unicorn_studio`，库名是硬编码的。

### 增量迁移（均针对「已初始化的库」，可重复执行）

| 文件 | 版本 | 做了什么 | 权限码 |
|---|---|---|---|
| `mysql-migration-v8-learning-admin.sql` | V8 | 建 `learning_category` 字典表；`learning_resources` 加 `tags` / `attachment_url` | `learning:*`（6 个） |
| `mysql-migration-v9-service-item-admin.sql` | V9 | 仅补权限码与 EDITOR 角色映射，`service_item` 表结构不变 | `service:*`（4 个） |
| `mysql-migration-v10-capability-admin.sql` | V10 | 建 `capability_item` 表（结构对齐 `service_item`）；首页「培养的能力」区块 | `capability:*`（4 个） |
| `mysql-migration-v11-page-background.sql` | V11 | `carousel` 加 `page_key` / `media_type` / `video_url`，页面背景接入轮播图管理 | — |
| `mysql-migration-v12-about-page-admin.sql` | V12 | 「关于我们」文案入 `site_settings` 的 `about` 组 | `about:view` / `about:edit` |
| `mysql-migration-v13-footer-admin.sql` | V13 | 页脚文案/快速链接/社交图标入 `footer` 组 | `footer:view` / `footer:edit` |
| `mysql-migration-v14-data-retention.sql` | V14 | 数据保留策略默认值入 `retention` 组 | `retention:view` / `edit` / `run` |
| `mysql-migration-v15-index-tuning.sql` | V15 | 补 8 张高频读路径缺失的索引（缓存未命中时不再全表扫描） | — |
| `mysql-migration-v16-ai-assistant.sql` | V16 | AI 助手配置默认值入 `ai` 组（`ai.api_key` **故意留空**，禁止把真实密钥写进 SQL） | `ai:view` / `ai:edit` / `ai:test` |
| `mysql-migration-v17-join-page-admin.sql` | V17 | 「加入我们」文案 + 社群二维码入 `join` 组 | `join:view` / `join:edit` |
| `mysql-migration-v18-comment-pagination-index.sql` | V18 | 评论真分页所需复合索引 `(target_type,target_id,status,parent_id,created_at)` | — |

### 修正脚本

| 文件 | 用途 | 幂等 |
|---|---|:---:|
| `mysql-fix-permission-mojibake.sql` | 修 `sys_permission` id 41–50 的中文乱码。**根因**：早期执行 V8/V9 时 mysql 客户端漏了 `--default-character-set=utf8mb4`，UTF-8 字节被按 GBK 解码后写入。 | ✓ |

> **教训**：今后执行任何含中文的脚本，**一律加** `--default-character-set=utf8mb4`，
> 否则会静默写入乱码（不报错，只在界面上显示为「瀛︿範璧勬枡」）。

### 迁移套件

| 路径 | 用途 |
|---|---|
| `server-migration/README.md` | 本地库 → 服务器 MySQL 全量迁移手册 |
| `server-migration/01-schema-full.sql` | 全量建库（幂等 + 非破坏），由 init + V8–V18 + 乱码修复合并生成 |
| `server-migration/02-data.sql` | 业务数据导出（**含敏感数据，已 gitignore**） |
| `server-migration/03-verify.sql` | 迁移后校验 |
| `tools/gen_merged_schema.py` | 生成 `01` |
| `tools/gen_data_dump.py` | 生成 `02` |
| `tools/verify_merged_schema.py` | 校验 `01`：幂等性 + 结构/列顺序/外键/排序规则 |
| `tools/verify_roundtrip.py` | 端到端回归：建空库 → 导数据 → 21 表逐行比对 |

---

## 2. 排序规则约定

| | 值 |
|---|---|
| 库默认 | `utf8mb4` / **`utf8mb4_0900_ai_ci`** |
| 全部 21 张表 | `utf8mb4_0900_ai_ci` |
| 全部 101 个字符列 | `utf8mb4_0900_ai_ci` |

⚠️ `mysql-init.sql` 原先写的是 `utf8mb4_unicode_ci`，**与实录库不一致**（实测库是 MySQL 8 的默认
`utf8mb4_0900_ai_ci`）。用 `server-migration/01-schema-full.sql` 建库时已纠正为 `0900_ai_ci`；
若直接用 `mysql-init.sql` 建库，得到的是 `unicode_ci` 库 —— 两种排序规则在 JOIN 比较时可能报
`Illegal mix of collations`。**同一环境内必须统一**。

---

## 3. 改动后的必跑回归

```bat
python backend\sql\tools\gen_merged_schema.py
python backend\sql\tools\gen_data_dump.py
python backend\sql\tools\verify_merged_schema.py
python backend\sql\tools\verify_roundtrip.py
```

四个脚本都会自建/自删临时库（`unicorn_verify_scratch`、`unicorn_rt_scratch`），不碰 `unicorn_studio`。
默认连 `127.0.0.1:3306` / `root` / `123456`；
`gen_data_dump.py` 支持 `--host/--port/--user/--password/--db` 覆盖，其余三个的常量在文件顶部。
