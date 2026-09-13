# 独角兽工作室 · Redis 缓存设计方案（V15 规划）

| 项 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 日期 | 2026-09-11 |
| 适用范围 | `backend/`（Spring Boot 3.3.5 + MyBatis-Plus 3.5.9 + MySQL 8 + Druid）；设计上覆盖前台主站与后台管理两端 |
| 状态 | **设计文档（未实施）** —— 本文只做审查结论与方案设计，不改动任何现有代码 |
| 关联文档 | 《后台管理实施方案.md》（§3.6/§3.7 接口与缓存约定、§5.2 账号锁定、§5.9 限流）、《前端主站优化方案.md》、《数据保留清理策略设计方案.md》（V14，同为 `site_settings` 分组配置范式） |
| 读者 | 后端实施者与运维 |

**修订记录**：

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-09-11 | 首版。基于后端代码实地通读（含 `pom.xml`、`application.yml`、全部 Controller/Service/Mapper/Filter/Security 类，共 137 个 Java 文件，以及 `sql/mysql-init.sql` 与 7 个迁移脚本的实际索引）与前端调用点全量清点（`frontend/src` + `admin/src`）后定稿；所有「现状」结论均标注了代码位置 |
| v1.1 | 2026-09-12 | 依评审意见修订。**基准变更**：以提交 `1195e44`（「将前端管理端仪表盘接入 pv 和 uv 的真实数据接入仪表盘」）**之后的代码为基准** —— 该提交把仪表盘的 trend 与 sourceDist 由 `stats_daily` 聚合表改为**直读 `visit_log`**，文档 v1.0 的行号与查询条数已随之过时（详见新增的 §2.7）。本次另修正 v1.0 的一处**自身错误**（§8.1 R8：`RetentionService` 实际**不清理** `visit_log`）、把权限缓存失效升格为**安全验收**（§4.2/§6.3/附录 C.1）、统一序列化口径（§5.5.1）、浏览量回写默认改为 **SET 活跃索引**（§4.4）、补充 **Redis 高可用**（§4.3 A7）与版本基线/时区口径/VO 演进策略，并把附录 B 落为**幂等迁移脚本** `backend/sql/mysql-migration-v15-index-tuning.sql`（已在审稿库实跑两遍验证幂等） |

---

## 1. 需求与核心结论

### 1.1 需求

为后端引入 Redis 服务，目标是**加快数据加载**。要求：

1. 审查后端代码，识别哪些功能适合引入缓存；
2. 针对这些功能分别设计**缓存三兄弟**（缓存穿透 / 缓存击穿 / 缓存雪崩）的防护方案；
3. 给出可落地的键规范、TTL 策略、失效策略、降级策略与实施节奏。

### 1.2 核心结论（先看这一段）

> **结论一：当前后端完全没有任何缓存中间件，只有一个 10 分钟的手写 JVM 内存缓存。**
> 全仓库无 `spring-boot-starter-data-redis` 依赖、无 `RedisTemplate`、无 `@EnableCaching`、无 `@Cacheable`（已全文检索确认）。
> 唯一的缓存是 `SiteSettingsService.publicCache`（`admin/service/SiteSettingsService.java:33-34`），且**只覆盖 `/api/site/settings` 一个接口**。

> **结论二：性价比最高的单点不是「内容缓存」，而是「后台认证链路」。**
> `JwtAuthFilter` 在**每一个** `/api/admin/**` 请求上都要执行
> `sysUserMapper.selectById()` + `AdminPermissionService.loadForUser()`（后者展开为 3 条 SQL：查角色关联 → 查角色 → 查权限码）。
> 即：**后台每点一次按钮 = 4 次数据库往返**，纯粹为了重建一份几乎不变的权限集合。
> 这是全代码库中唯一「每请求固定 N 次查库」的写死开销（`admin/security/JwtAuthFilter.java:49-51`、`admin/service/AdminPermissionService.java:41-58`）。
>
> 更糟的是**同一次请求内的重复加载**：`GET /api/admin/auth/me` 先经 `JwtAuthFilter` 调一次 `loadForUser()`，
> 随后 `AuthService.me()`（`admin/service/AuthService.java:92`）**又调了一次**。
> 而 `SUPER_ADMIN` 分支走的是 `permissionMapper.selectList(new LambdaQueryWrapper<>())`
> —— **无 WHERE 条件的 `sys_permission` 全表扫描**（`mapper/SysPermissionMapper.java`，`AdminPermissionService.java:53`）。
> 即：超管刷新一次「我的信息」= 2 轮完整权限重建 + 2 次权限表全表扫。

> **结论三：前台真正的热点是「页面级静态配置」，而不是列表页。**
> 前端是 SPA，`AppFooter.vue` 挂在 `<router-view>` **之外**（`frontend/src/App.vue:10`），
> 因此**每次路由切换都会重新拉一次 `/api/footer`**（`frontend/src/components/AppFooter.vue:132`）。
> 但 `AboutPageService.load()`、`FooterService.load()`、`SensitiveWordService.loadWords()`
> 走的是 `SiteSettingsMapper.findByGroupName()` / `findBySettingKey()`（`admin/service/SiteSettingsService.java:51-57`、`service/SensitiveWordService.java:43-51`），
> **完全没有走到那个内存缓存**，因此每次都是真实查库。
> 雪上加霜的是 `site_settings` 表**只有 `setting_key` 的 UNIQUE 索引，`group_name` 无索引**
> （`sql/mysql-init.sql:244-251`），所以 `listGroup("footer")` / `listGroup("about")` 每次都是**全表扫描**。

> **结论四：本项目的「缓存击穿」主要风险不是热 key 过期，而是「写热点」。**
> `WorkService.findPublishedBySlug()` 与 `NewsService.findPublishedBySlug()` 是
> **GET 请求里做「SELECT 再 UPDATE」的读改写**（`service/WorkService.java:34-42`、`service/NewsService.java:31-39`），
> 每看一次详情页就写一次库。**这种字段不能进缓存，只能把计数搬到 Redis 做原子自增 + 定时回写。**

> **结论五：本项目最大的「雪崩」隐患是「Redis 掉线 = 全站 500」。**
> 引入 Redis 后，若不做降级，`/api/about`、`/api/footer`、`/api/site/settings` 这些**每个页面都要调**的接口
> 会从「慢但可用」变成「直接报错」。**降级通路（Redis 不可用 → 直读 MySQL）是本方案的硬性要求，不是可选项。**

> **结论六：现有三处「时间/粒度对齐」已经构成雪崩前置条件，实施时必须一并处理。**
> - `SiteSettingsService.CACHE_TTL_MILLIS` 是**固定 10 分钟**（`:28`），所有 key 同时过期；
> - 定时任务密集堆在 01:00 / 02:00 / 03:00 / 03:30（`StatsService:69`、`application.yml:88`、`application.yml:94`）；
> - `RateLimitFilter` 的 `buckets` 是 `ConcurrentHashMap`（`common/RateLimitFilter.java:30`），**只增不减**，
>   且是进程内的 —— 服务重启后限流计数清零，将来多实例部署则限流失效。

### 1.3 「缓存三兄弟」在本项目的落点速查

| 问题 | 一句话定义 | 本项目的高危落点 | 首选对策 |
|---|---|---|---|
| **缓存穿透** | 查一个**数据库里根本不存在**的数据，缓存永远不命中，每次都打到 DB | `GET /api/works/{slug}`、`GET /api/news/{slug}`、`GET /api/learning/resources/{id}`、`GET /api/page-background/{pageKey}` —— slug/id 全部来自 URL，可被任意枚举 | 空值占位缓存 + 参数白名单 + 短 TTL 随机化；集群化后再上布隆过滤器 |
| **缓存击穿** | **某个热点 key 过期的那一瞬间**，大量并发同时未命中，全部涌向 DB | 首页三件套 `/api/carousel`、`/api/services`、`/api/works?featured=true`；全局的 `/api/footer`、`/api/about`、`/api/site/settings` | 逻辑过期 + 异步续期；或 Redisson 分布式互斥锁（singleflight） |
| **缓存雪崩** | **大批 key 同时过期**，或**缓存整体不可用**，DB 被瞬间打满 | ① 所有 key 同一时刻过期；② Redis 重启/宕机导致冷启动；③ Redis 掉线导致接口整体不可用 | TTL 随机抖动 + 分级 TTL + 缓存预热 + 熔断降级直读 DB |

> 补充说明：三兄弟之外，本项目还有两个**必须一起解决**的衍生问题，否则前三个做了也白做：
> **④ 写热点**（详情页浏览计数的读改写，见 §2.3）、**⑤ 一致性**（管理端改完内容，前台必须立刻看到，见 §5.3）。
>
> 另外，前端流量实测数据（§2.5）显示：**一次 6 次导航的典型会话共发出 30 个请求，
> 其中 `/api/stats/pv` 6 个 + `/api/footer` 6 个 = 40%。**
> 前者是写（不该缓存，该做写缓冲），后者是全站最值得缓存的单个接口。

---

## 2. 现状审查

### 2.1 技术栈与依赖基线

| 项 | 现状 | 位置 |
|---|---|---|
| 框架 | Spring Boot 3.3.5 / Java 21 | `backend/pom.xml:7-10,21` |
| 持久层 | MyBatis-Plus 3.5.9（`mybatis-plus-spring-boot3-starter` + `mybatis-plus-jsqlparser`） | `pom.xml:45-55` |
| 连接池 | Druid 1.2.28（开启 `stat` 过滤器，供 `/api/admin/sql-monitor` 读 JVM 内存统计） | `pom.xml:57-61`、`application.yml:24-37` |
| 数据库 | MySQL 8，索引由 `sql/mysql-init.sql` 与 `sql/mysql-migration-*.sql` 手工维护，**无 ORM 自动建表** | `application.yml:15-22` |
| 全局更新语义 | `update-strategy: ignored` = **全列更新**，`updateById` 会覆盖整行 | `application.yml:60` |
| 限流 | Bucket4j `bucket4j-core` 8.10.1，**纯内存** `ConcurrentHashMap<String, Bucket>` | `pom.xml:94-99`、`common/RateLimitFilter.java:30` |
| 缓存 | **无 Redis、无 Spring Cache、无 Caffeine**；仅 1 处手写 JVM 缓存 | `admin/service/SiteSettingsService.java:33-34` |
| 异步/定时 | `@EnableAsync` + `@EnableScheduling`；`asyncExecutor`(core 2 / max 8 / queue 200)、`backupExecutor`(1) | `config/AsyncConfig.java:17-42` |
| 现有定时任务 | 01:00 埋点聚合、02:00 备份、03:00 统计聚合、03:30 数据保留清理 | `StatsService.java:69`、`application.yml:88,94` |
| **Redis 版本基线** | **本方案假设 Redis ≥ 6.2**（v1.1 新增）。依据：§4.4 浏览量回写依赖 `GETDEL`（6.2 引入）、§4.5 Bucket4j Redis 代理依赖 `SET` 的 `KEEPTTL`/`EXPIRETIME`（6.2 引入）。**若部署环境低于 6.2，§4.4 需退回 Lua 脚本「读+清」，§4.5 需换成 `INCR`+`EXPIRE` 手写滑动窗口** | —（部署约束） |

**结论**：引入 Redis 需要新增依赖并自定义配置，属于「从 0 到 1」，但同时**没有历史包袱**——不存在需要迁移的缓存语义，可以一次性把规范和降级通路做对。

### 2.2 现有唯一缓存的问题

`SiteSettingsService.publicSettings()`（`:92-114`）用 `volatile` 字段 + `synchronized` 双检锁实现 10 分钟缓存：

```java
private static final long CACHE_TTL_MILLIS = 10 * 60 * 1000;   // ← 固定 TTL，无抖动
private volatile Map<String, Object> publicCache;              // ← 进程内，多实例不一致
private volatile long publicCacheAt;
```

| 隐患 | 影响 |
|---|---|
| **固定 10 分钟 TTL** | 所有实例、所有 key 同一时刻过期 → 雪崩前置条件 |
| **进程内存储** | 多实例部署时各存各的，`invalidatePublicCache()`（`:116-119`）只能清掉当前实例 → 改配置后前台可能读到旧值 |
| **覆盖面极窄** | 只服务 `/api/site/settings`；`listGroup()`（`:51-57`，被 About/Footer/Retention 使用）**完全不缓存** |
| **`updateAll` 事务内失效** | `updateAll()` 标了 `@Transactional`（`:60-82`），但 `invalidatePublicCache()` 在方法末尾同步执行 —— 若事务随后回滚，缓存已被清空（本场景危害小，但这是**必须在 Redis 版里修正的模式**，见 §5.3） |

### 2.3 读改写热点（本项目的特殊之处）

```java
// service/WorkService.java:34-42  ——  GET /api/works/{slug}
@Transactional
public Optional<Work> findPublishedBySlug(String slug) {
    Optional<Work> work = workMapper.findBySlugAndStatus(slug, PUBLISHED);
    work.ifPresent(w -> {
        w.setViewCount(w.getViewCount() + 1);   // ← 读改写
        workMapper.updateById(w);               // ← 全列 UPDATE，每次浏览一次写
    });
    return work;
}
```

`NewsService.findPublishedBySlug()`（`:31-39`）完全同构。

**为什么这直接决定缓存方案**：

1. **`viewCount` 必须从缓存内容里剥离。** 若把 `WorkDetailVO`（含 `viewCount` 字段，`vo/WorkDetailVO.java:22`）整体缓存，则每次浏览都要更新缓存 → 缓存命中率再高也得写 Redis，且内容与计数互相污染。
2. **全列 `updateById` 代价被放大。** 由于 `update-strategy: ignored`，这一条 UPDATE 会把 `content`（MEDIUMTEXT 富文本）整列重写一遍。
3. **它同时是「缓存一致性」的雷区**：管理端改作品 → 清详情缓存；紧接着一次浏览 → 写库；如果计数回写与内容缓存共用一把 key，必然互相踩踏。

**方案定调**：内容缓存走「静态部分 JSON」，浏览量走 **Redis 独立计数器（`INCR`）+ 定时批量回写 MySQL**（见 §4.4、§6.2）。

**附带修掉的一个真实缺陷**：当前实现是「读出 `w` → `w.setViewCount(w.getViewCount() + 1)` → `updateById(w)`」，
而**全项目没有任何乐观锁**（`model/` 与 `config/` 中均无 `@Version`，也未注册 `OptimisticLockerInnerInterceptor`，
`config/MybatisPlusConfig.java:15-22` 只注册了分页插件）。
因此**并发浏览会丢失计数**（两个请求同时读到 `viewCount=10`，各写回 11，最终 11 而非 12）。
改用 Redis `INCR` 后，自增是原子的，**顺带修掉这个丢计数问题**。

### 2.4 索引现状对缓存决策的影响

| 表 | 索引 | 缓存必要性 |
|---|---|---|
| `work` | `idx_work_status_sort(status, sort_order)` | 列表查询有索引 |
| `news` | `idx_news_status_sort(status, sort_order)` | 同上 |
| `carousel` | `idx_carousel_page_status_sort(page_key, status, sort_order)` | 同上 |
| `learning_resources` | **仅有主键** —— `status`、`sort_order` **无索引** | `LearningService.findAll()`（`service/LearningService.java:21-25`）的 `WHERE status=1 ORDER BY sort_order, id` 走**全表扫描 + filesort**，缓存收益高于同类接口 |
| `service_item` | **仅有主键** —— `status` **无索引** | `ServiceItemService.findPublished()` 同上 |
| `capability_item` | **仅有主键** —— `status` **无索引** | `CapabilityService.findPublished()` 同上 |
| `visit_log` | `idx_visit_log_time(visited_at)`、`idx_visit_log_ip(ip_hash, visited_at)` | 高频写入的追加表；`COUNT(DISTINCT ip_hash)` / `GROUP BY DATE_FORMAT(...)` / `GROUP BY referer` 无法有效走索引 |
| `sys_user` | `username` UNIQUE；**`refresh_token` 无索引** | `findByRefreshToken()`（`mapper/SysUserMapper.java:15-17`）是**全表扫描**，且刷新令牌是 UUID —— 这是「穿透」的典型温床 |
| `site_settings` | **只有 `setting_key` UNIQUE；`group_name` 无索引** | `findByGroupName()`（`mapper/SiteSettingsMapper.java:22-26`）是**全表扫描**，而它正是 `/api/footer`、`/api/about`、保留配置的读路径 |
| `comment` | `idx_comment_target(target_type, target_id, status)`、`idx_comment_user(user_id)`；**`parent_id` 无索引** | `findByParentIdIn()`（`mapper/CommentMapper.java:19-21`）走全表扫描 |
| `sys_permission` | `code` UNIQUE | `SUPER_ADMIN` 分支的 `selectList(new LambdaQueryWrapper<>())`（`AdminPermissionService.java:53`）是**无 WHERE 全表扫描**，且发生在每个超管请求上 |

> **重要推论**：`learning_resources` / `service_item` / `capability_item` 三张表**索引缺失**（`learning_resources` 与 `capability_item` 甚至**一个二级索引都没有**）。
> 缓存能挡住读压力，但**不能替代索引**（冷启动、缓存未命中的第一次请求仍是全表扫描）。
> 建议在实施缓存的同时补索引（附录 B），两者并行不冲突。
>
> 另外补两个容易漏掉的索引缺失点：
> - **`backup_record.created_at` 无索引** → `BackupService.cleanupOldBackups()`（`admin/service/BackupService.java:165`）
>   用 `findAllByOrderByCreatedAtDesc()` **全表扫描 + filesort 把所有备份记录载入内存**，只为保留最近 N 条。
>   该查询在**每天 02:00 定时任务**与**每次手动备份后**各执行一次。
> - **`sys_operation_log.module` / `status` 无索引**（已建索引的只有 `created_at`、`username`）
>   → 后台日志筛选走扫描（`admin/controller/OperationLogController.java:39-43`）。

### 2.5 同一次请求内的重复读（缓存的「免费」收益）

这是本项目一个**被低估的浪费模式**：同一个请求处理过程中，同一份数据被读了两遍以上。
这类浪费**不需要改业务逻辑，只需要在 Service 内部加缓存即可消除**，是「零风险、立刻见效」的部分。

| 接口 | 重复读情况 | 位置 | 说明 |
|---|---|---|---|
| **`GET /api/admin/auth/me`** | `loadForUser()` **执行 2 次** | `JwtAuthFilter:51` + `AuthService:92` | 超管分支含 2 次 `sys_permission` 无 WHERE 全表扫描；全程 **≈8 条查询** |
| `GET /api/admin/profile` | `loadForUser()` 执行 2 次 | `JwtAuthFilter:51` + `AdminAccountService:220` | 另加 `rolesOf()` 的 2 条（`:240`、`:245`） |
| **`PUT /api/admin/admins/{id}`** | 角色集合被读 **3 次** | `AdminAccountService:230 isSuperAdmin`（→再调 `loadForUser`）、`:235 roleIdsOf`、`:260 validateRoles` | 一次改管理员 = 同一份角色数据查三遍 |
| **`GET /api/admin/dashboard/summary`** | **对 `visit_log` 扫 6 遍** | `DashboardService:52,53,54,55`（今日/窗口 PV·UV）+ `:72`（trend）+ `:90`（sourceDist） | 4 条 `COUNT`/`DISTINCT` + 2 条 `GROUP BY`，全部落在同一个 ≤90 天窗口上（详见 §2.7） |
| **`GET /api/admin/retention`** | `listGroup("retention")` 读 **2 次** | `RetentionService:123 status()` 与 `:136`→`:104 loadConfig()` | 每次都是 `group_name` 无索引全表扫描 |
| **`PUT /api/admin/works/sort`** / `news/sort` | **整表载入内存**（含 `MEDIUMTEXT content`） | `WorkAdminService:158`、`NewsAdminService:148` | 只为重算排序号；拖拽排序一次 = 全表读 |
| **`PUT /api/admin/roles/{id}`** | `sys_permission` 按 id **逐个查（N+1）** | `RoleService:133`（在循环内 `selectById`） | 一次保存角色最多约 46 次单行查询 |
| **`POST /api/comments` / `POST /api/messages`** | 敏感词库**每次调用都重新查库 + 重新 split** | `SensitiveWordService:42 loadWords()` ← `CommentService:58`、`MessageController:41` | 无任何字段级 memoization；一个约 100 字节、**几乎永不变化**的配置，在最热的两条写路径上每次重读重解析 |

> **结论**：上表里除 `dashboard` 和 `sort` 之外，**其余全部只需在 Service 层加一个短 TTL 的本地/Redis 缓存即可消除**，
> 且因为它们**在同一个请求内**，甚至不需要跨请求一致性保证 —— 请求级（request-scoped）缓存就够。
> 建议把 `SensitiveWordService` 的敏感词表缓存**与 Redis 方案解耦、提前做**（一个 `volatile` 字段 + 写后失效即可），
> 这是全项目**投入产出比最高的一处改动**。

### 2.6 前端调用流量实测（决定缓存优先级的依据）

对 `frontend/src` 全量通读后的调用事实：

| 事实 | 位置 | 对缓存决策的影响 |
|---|---|---|
| **前台没有任何轮询**（无 `setInterval` 发请求） | 全量检索确认 | 流量 = 导航次数 × 每页接口数，**完全由组件挂载驱动**，可预测、易缓存 |
| **`AppFooter` 挂在 `<router-view>` 之外** | `App.vue:10`、`AppFooter.vue:132` | **每次路由切换都拉一次 `/api/footer`**，包括 404 页 → **全站最值得缓存的单个接口** |
| **PV 埋点每次导航触发一次** | `useAnalytics.js:27`（`router.afterEach`） | 典型 6 次导航 = 6 次写库；是**最高频调用**，但它是写 → 走写缓冲（§3.4 C2），不是缓存 |
| **`/api/site/settings` 前台前端根本没调** | 无任何调用点 | A1 的缓存仍建议做（后台 `listGrouped` 与将来前台接入共用），但**优先级可低于 A3/A2** |
| **`/api/news`、`/api/news/{slug}` 前台前端也没调** | 无任何调用点 | B4/B7 属于「已实现但未接入」的接口，**可延后**，不占首期收益 |
| **两个详情页各存在一次重复请求** | `WorkDetailPage.vue:198-199`（`onMounted` + `watch` 同时触发）、`LearningDetailPage.vue:189-190` 同构 | 每次进详情页**同接口发两次** → 详情缓存能把这 2 次请求合并成 1 次查库（**免费的额外收益**） |
| **没有任何客户端缓存/去重** | `composables/useApi.js` 是裸 `fetch`，无缓存、无 inflight 合并、无 `AbortController` 复用 | 服务端缓存是**唯一**的缓存层，没有下层兜底 |
| **HTTP 层被显式禁用缓存** | `frontend/server/index.js:125-127` 对所有 `/api/*` 强制 `Cache-Control: no-store` | 浏览器缓存 / CDN 都用不上，**只能靠 Redis** |
| **公共前端不发送 `Authorization` 头** | `useApi.js:10-13` 只设 `Content-Type` | 缓存 key 无需按用户维度区分 → 公共内容可以**全局共享一份缓存**，命中率最大化 |

**典型会话请求分布**（首页 → 作品 → 作品详情 → 学习 → 学习详情 → 回首页，共 6 次导航、30 个请求）：

| 接口 | 次数 | 占比 |
|---|---|---|
| `POST /api/stats/pv` | 6 | 20%（写，见 C2） |
| `GET /api/footer` | 6 | 20%（**A3，首期必做**） |
| `GET /api/carousel` | 2 | A7 |
| `GET /api/services` | 2 | B1 |
| `GET /api/capabilities` | 2 | B2 |
| `GET /api/works?featured=true` | 2 | B3 |
| `GET /api/works/{slug}` | 2 | B6（含重复请求） |
| `GET /api/page-background/work-detail` | 2 | A6 |
| `GET /api/works` | 1 | B3 |
| `GET /api/learning/resources` | 1 | B5 |
| `GET /api/learning/resources/{id}` | 2 | B8（含重复请求） |
| `GET /api/about` / `page-background/about` | 0 | 本次未访问（A2/A6） |

> **注意**：即使是「进首页什么都不点」的最短会话，也会发出 **7 个请求**（4 个首页接口 + footer + PV + 可能的重复）。
> **每一个页面 —— 哪怕它自己不渲染任何数据（如 404 页）—— 都至少产生 2 个请求**（footer + PV）。
> 这正是「A3 页脚缓存」优先级最高的原因。

**后台调用流量**（补充）：`admin/` 应用**访问路径全部是 `/api/admin/**`**，共 21 个 api 模块、84 个请求点。
关键事实：

- **仪表盘不轮询**：`DashboardView.vue:149` 只在 setup 时 `load()` 一次（`days` 切换时再拉） → B11 的价值在**单次查询代价高**（14 条查询，见 §2.7），不在频率；
- **全后台唯一轮询**：`SqlMonitorView.vue:175` 的 30 秒 `setInterval`，且**默认关闭**（`autoRefresh = ref(false)`）→ B12 价值有限；
- **每次硬刷新必发一次 `/api/admin/auth/me`**：路由守卫 `router/index.ts:208-215` 在 `!auth.user` 时调用。而 `admin_user`/`admin_permissions` 写进了 localStorage 却**从不读回**（`stores/auth.ts:51-52`），导致每次 F5 都要重新拉一次 —— 这正是结论二那个「4 次查库 + 2 次全表扫描」的触发点；
- **后台搜索无防抖**：`useTable.ts` 的 `search()` 每次按键 = 1 个 GET，无去重、无取消 → 后台列表接口虽不适合缓存，但**频繁误触**会放大 B10 的每请求开销。

### 2.7 Dashboard 数据源基准说明（v1.1 新增）

仪表盘是本方案中**唯一一处「基准随代码演进发生过变化」**的对象，故单列说明，避免后人按旧行号复核时产生歧义。

**基准提交**：`1195e44`「将前端管理端仪表盘接入 pv 和 uv 的真实数据接入仪表盘」。
该提交把 trend（近 N 日趋势）与 sourceDist（来源分布）**从 `stats_daily` 聚合表改为直读 `visit_log`**：

| 维度 | `1195e44` **之前** | `1195e44` **之后（= 当前代码，本文基准）** |
|---|---|---|
| 今日/窗口 PV·UV | 4 次读 `visit_log` | 4 次读 `visit_log`（**未变**） |
| 近 N 日 trend | 读 `stats_daily`（`findByStatDateBetweenOrderByStatDateAsc`） | ✅ **改为 `visit_logMapper.findDailyPvUv()` → `GROUP BY DATE_FORMAT(visited_at,'%Y-%m-%d')`** |
| 来源分布 sourceDist | **硬编码** `"直接访问" = 0`（无查询） | ✅ **改为 `visitLogMapper.findRefererCounts()` → `GROUP BY referer`** |

**因此本文 v1.0 的行号与数据源描述对应的是「`1195e44` 之前」的状态，已过时**；v1.1 全部改为以当前代码为准。

**当前 `summary()` 的完整查询清单（14 条，行号以 `admin/service/DashboardService.java` 为准）**：

| # | 行号 | 目标表 | 查询形态 |
|---|---|---|---|
| 1 | `:52` | `visit_log` | `COUNT(*)` 今日（走 `idx_visit_log_time`） |
| 2 | `:53` | `visit_log` | `SELECT DISTINCT ip_hash` 今日 → **物化进 Java List 再 `.size()`** |
| 3 | `:54` | `visit_log` | `COUNT(*)` 窗口（≤90 天） |
| 4 | `:55` | `visit_log` | `SELECT DISTINCT ip_hash` 窗口 → 物化进 Java List |
| 5 | `:58` | `work` | `COUNT(*)` 无 WHERE |
| 6 | `:59` | `work` | `COUNT(*)` `status='published'` |
| 7 | `:61` | `news` | `COUNT(*)` 无 WHERE |
| 8 | `:62` | `comment` | `COUNT(*)` `status='pending'`（**`status` 非 `idx_comment_target` 前导列** → 扫描） |
| 9 | `:63` | `message` | `COUNT(*)` `status='new'` |
| 10 | `:64` | `public_user` | `COUNT(*)` 无 WHERE |
| 11 | `:65-68` | `public_user` | `COUNT(*)` `created_at` 今日（**`created_at` 无索引** → 扫描） |
| 12 | **`:72`** | **`visit_log`** | **`GROUP BY DATE_FORMAT(visited_at,'%Y-%m-%d')` + `COUNT(DISTINCT ip_hash)`** |
| 13 | **`:90`** | **`visit_log`** | **`GROUP BY referer`（`referer` 无索引）** |
| 14 | `:113-115` | `message` | 分页 `ORDER BY id DESC LIMIT 5` |

> **对 v1.0 的更正**：v1.0 写「≈11 条查询」**偏少且与 §2.5 自身不自洽**（4+2 条 `visit_log` 加 6 条内容表 COUNT 已是 12）。实测口径为 **14 条**，其中 **6 条打在 `visit_log` 上**（上表 1–4、12、13）。
>
> **这使 B11 的缓存收益比 v1.0 描述更高**：不仅 `visit_log` 的扫表次数是 6（而非 4），且第 12、13 条的 `GROUP BY` 都落在**非索引表达式/列**上（临时表 + filesort）。叠加 `visit_log` **无任何清理机制**（§8.1 R8），这张表会持续增长 → 该接口的耗时会**随时间单调恶化**，缓存是性价比最高的止血手段。

---

## 3. 分级缓存清单（哪些功能该上 Redis）

按「读频 × 写频 × 数据量 × 一致性容忍度」四维打分，把全部读路径分为 **A/B/C 三级**：

- **A 级（强烈推荐，收益最高）**：高频读 + 极低频写 + 数据量小 + 一致性容忍度高（秒级延迟可接受）
- **B 级（推荐，收益明确）**：中高频读 + 低频写，或读代价高（聚合查询）
- **C 级（不建议缓存 / 需换思路）**：写频高、或实时性要求高、或本身就是计数器

### 3.1 A 级：站点级配置类（页面级静态数据）

| # | 接口 / 方法 | 读路径 | 为什么是 A 级 | 建议 TTL |
|---|---|---|---|---|
| A1 | `GET /api/site/settings`<br>`SiteSettingsService.publicSettings()` | `site_settings` 全表（`findAllByOrderByGroupNameAscSettingKeyAsc`） | 内容极小（几十行 KV）；改一次可能几周不变。**注意：前台前端当前并未调用此接口**（§2.5），实际收益主要在后台 `listGrouped` 与将来前台接入 | 30 min + 抖动 |
| A2 | `GET /api/about`<br>`AboutPageService.load()` → `listGroup("about")` | `site_settings WHERE group_name='about'` —— **`group_name` 无索引 → 全表扫描** | 每个进入 `/about` 的访客都查一次；**当前完全未缓存** | 30 min + 抖动 |
| A3 | `GET /api/footer`<br>`FooterService.load()` → `listGroup("footer")` | `site_settings WHERE group_name='footer'` —— **全表扫描** | ⭐ **全局组件挂在 `<router-view>` 之外，每次路由切换都调**（`App.vue:10`、`AppFooter.vue:132`），含 404 页；典型会话 6 次，占全部请求的 20%（§2.6）。**首期必做** | 30 min + 抖动 |
| A4 | 敏感词库<br>`SensitiveWordService.loadWords()` | `site_settings WHERE setting_key='notify.sensitive_words'` | **每发一条评论/留言就查一次库**（`service/CommentService.java:61`），纯浪费 | 10 min，写后立即失效 |
| A5 | 数据保留配置<br>`RetentionService.loadConfig()` / `status()` | `site_settings WHERE group_name='retention'` | 低频，但 `status()` 每次进后台页面都读；缓存可省 | 5 min，写后失效 |
| A6 | `GET /api/page-background/{pageKey}` | `carousel WHERE page_key=? AND status=1` | 每个详情页/关于页进来都调；白名单已限定 2 个 pageKey（`controller/PageBackgroundController.java:26`） | 30 min + 抖动 |
| A7 | `GET /api/carousel` | `carousel WHERE page_key='home' AND status=1` | 首页首屏，无缓存时每个访客一次查询 | 30 min + 抖动 |

> **A 级统一说明**：A1–A5 有一个共同的技术前提 —— `SiteSettingsService` 需要提供**带缓存的 `getValue(key)` / `listGroup(group)`**。
> 目前 `listGroup` 与 `getValue`（`:51-57`、`:85-89`）是裸查库，而 `publicSettings()` 是另一套缓存。
> Redis 版应把它统一为「**同一份 `site_settings` 快照，三种视图（全量 / 按组 / 按 key）**」，见 §5.2。

### 3.2 B 级：内容列表与详情类

| # | 接口 / 方法 | 读路径 | 为什么是 B 级 | 建议 TTL |
|---|---|---|---|---|
| B1 | `GET /api/services`<br>`ServiceItemService.findPublished()` | `service_item WHERE status='published' ORDER BY sort_order,id` —— **status 无索引** | 首页固定模块；表小、写极少 | 30 min + 抖动 |
| B2 | `GET /api/capabilities`<br>`CapabilityService.findPublished()` | `capability_item` —— **status 无索引** | 同上 | 30 min + 抖动 |
| B3 | `GET /api/works`（列表 / `?featured=true`）<br>`WorkService.findPublished()` | `work WHERE status='published' [AND category=? / AND featured=1] ORDER BY ...` | 首页与作品页主数据源；作品数量增长后收益上升 | 10 min + 抖动 |
| B4 | `GET /api/news`（列表 / `?category=`）<br>`NewsService.findPublished()` | `news WHERE status='published' [AND category=?]` | 同上 | 10 min + 抖动 |
| B5 | `GET /api/learning/resources`<br>`LearningService.findAll()` | `learning_resources WHERE status=1 ORDER BY sort_order,id` —— **全表扫描 + filesort** | 索引缺失，缓存收益最直接 | 30 min + 抖动 |
| B6 | `GET /api/works/{slug}`<br>`WorkService.findPublishedBySlug()` | `work WHERE slug=? AND status='published'` + **UPDATE** | 详情页；**必须剥离 viewCount**（§2.3） | 内容 60 min，计数走 Redis |
| B7 | `GET /api/news/{slug}`<br>`NewsService.findPublishedBySlug()` | `news WHERE slug=? AND status='published'` + **UPDATE** | 同上 | 内容 60 min，计数走 Redis |
| B8 | `GET /api/learning/resources/{id}`<br>`LearningService.findPublishedById()` | `learning_resources WHERE id=? AND status=1` | 按主键，成本低；但配合 B5 一起做更省事 | 30 min + 抖动 |
| B9 | `GET /api/comments?targetType=&targetId=`<br>`CommentService.listApproved()` | `comment WHERE target_type,target_id,status`（有索引）+ `parent_id IN (...)`（**无索引**） | 已审核评论读多写少。**注意其成本结构**：它查出**全部** approved 评论载入内存，再在 Java 里做一级评论分页（`CommentService:82-88`）→ 内存与 CPU 开销随评论量线性增长，而非 SQL `LIMIT`。**新评论审核通过后必须失效** | 5 min（短 TTL 兜底） |

### 3.3 B 级：后台聚合与认证链路（**收益最高的 B 级**）

| # | 接口 / 方法 | 读路径 | 为什么值得做 | 建议 TTL |
|---|---|---|---|---|
| **B10** | **`JwtAuthFilter` 的每请求认证**<br>`SysUserMapper.selectById()` + `AdminPermissionService.loadForUser()` | `sys_user` PK + `sys_user_role` + `sys_role` + `sys_permission`（**4 条 SQL / 请求**；超管分支含 `sys_permission` **无 WHERE 全表扫描**） | ⭐ **全代码库唯一「每请求固定 4 次查库」**。后台一个页面往往并发 5–10 个接口 → 每次点按 = 20–40 次查询，全为重建不变数据。`/api/admin/auth/me` 更会**在同一请求内重复加载两遍**（`JwtAuthFilter` + `AuthService.me():92`），且每次 F5 必触发一次（`router/index.ts:208-215`） | 用户 5 min，权限 10 min，**写后精确失效** |
| B11 | `GET /api/admin/dashboard/summary`<br>`DashboardService.summary()` | `visit_log` **6 次**：`:52,53,54,55` 的 2×`COUNT` + 2×`COUNT(DISTINCT ip_hash)`，`:72` 的 `GROUP BY DATE_FORMAT(...)`，`:90` 的 `GROUP BY referer`（后两者均走**非索引表达式/列**）；另有 7 次内容表 `COUNT` + 1 次 `recentMessages` 分页 —— **合计 14 条 SQL**（详见 §2.7） | 单次调用代价极高，且 `visit_log` **只增不减、无任何清理**（§8.1 R8）。**注意：它并不轮询**（`DashboardView.vue:149` 只在 setup 时调一次，`days` 变更时再拉），所以价值在「单次成本」而非「频率」 | 60 s（短 TTL 足够，数据本就容忍延迟） |
| B12 | `GET /api/admin/sql-monitor/sqls`<br>`SqlMonitorController.sqls()` | **无数据库**（Druid JVM 内存）；但 `admin/src/views/sql-monitor/SqlMonitorView.vue:175` **每 30 秒轮询一次** | 不涉及 DB，但每次都要**全量拉取 + 内存排序 + 分页**，并把大 DTO 序列化过网 | 30 s 或直接优化为「前端增量」 |
| B13 | `RoleService.permissionTree()` | `sys_permission` 全表 | 权限勾选树，几乎不变；配合 B10 一起做 | 30 min |
| B14 | `SiteSettingsService.listGrouped()`<br>`GET /api/admin/settings` | `site_settings` 全表 | 后台设置页；与 A1 同一份数据 | 复用 A1 快照 |
| B15 | `BackupService.cleanupOldBackups()`<br>（每天 02:00 + 每次手动备份后） | `backup_record` **全表扫描 + filesort 载入全部行**，仅为保留最近 N 条（`created_at` 无索引） | 单次代价高且**每天必跑**；备份记录变更只发生在备份完成时（事件明确，失效点清晰） | 1 h + 抖动，备份完成后失效 |

### 3.4 C 级：不建议用「读缓存」，改用其他 Redis 数据结构

这类数据**不能**用「读时缓存、写时失效」的常规模式，但恰恰是 Redis 的最佳用武之地 —— 只是换一种用法。

| # | 功能 | 现状 | 为什么不走读缓存 | 推荐的 Redis 用法 |
|---|---|---|---|---|
| **C1** | **浏览量计数**<br>`WorkService`/`NewsService.findPublishedBySlug()` | 每次浏览 1 次 SELECT + 1 次全列 UPDATE | 「读改写」写频 = 读频；缓存内容会被计数不断弄脏 | `INCR cache:views:work:{id}` 原子自增，定时任务每 1–5 min **批量**回写 MySQL；读接口用「缓存内容 + Redis 计数」拼装（§4.4） |
| **C2** | **PV 埋点**<br>`StatsService.recordPv()` | `@Async` 直接 `INSERT visit_log`（`service/StatsService.java:51-66`） | 每次埋点一次写库；`visit_log` 是增长最快的表，且**无任何清理**（§8.1 R8） | `LPUSH/RPUSH` 到 Redis List 做**写缓冲**，定时批量 `INSERT`（或直接 `INCR` 日计数，彻底不落原始行 —— 视 PV 明细是否还需要）。**时间戳口径见下方「缓冲时间戳约定」** |
| **C3** | **限流计数**<br>`RateLimitFilter` | 内存 `ConcurrentHashMap`，只增不减，重启清零，多实例失效 | 计数是「随时间自然衰减」的状态，缓存无意义 | Bucket4j **Redis 代理**（`bucket4j-redis` + Lettuce `ProxyManager`）替代内存桶；或 `INCR` + `EXPIRE` 滑动窗口（§4.5） |
| **C4** | **后台刷新令牌**<br>`AuthService.refresh()` → `findByRefreshToken()` | `sys_user.refresh_token` **无索引** → 全表扫描；令牌是 UUID，天然适合 key-value | 这是「会话状态」，不是「缓存的数据」 | `SET refresh:{token} {userId} EX 604800`，刷新时 `GET` + 轮换 `DEL`/`SET`。同时**顺手消灭全表扫描**（§4.6） |
| **C5** | **前台用户身份**<br>`PublicAuthService.resolveFromHeader()` | 每个带 token 的评论/留言请求 `selectById` | 会话解析，非缓存 | `public:user:{id}` 缓存（TTL 5–10 min），或把必要的用户信息直接放进 JWT claims |
| **C6** | **AI 对话**<br>`AiChatController` / `AiChatService` | 无状态、无历史、**无限流**（`RateLimitFilter.isLimited()` 未覆盖 `/api/ai/chat`） | 每次调用都是真实外部 API 成本 | ① 加 Redis 限流（按 IP/用户）；② 会话历史存 Redis List（TTL 30 min）支持多轮；③ 完全相同 prompt 的短 TTL 结果缓存 |

#### 缓冲时间戳约定（针对 C2，v1.1 新增）

PV 走 Redis 写缓冲后，`visit_log.visited_at` 必须写入**「进入 Redis 的时刻」**，而不是**「批量回写 MySQL 的时刻」**：

```
写缓冲时：  RPUSH pv:buffer  {"path":..,"ipHash":..,"ua":..,"referer":..,"visitedAt":"<此刻>"}
回写时：    用 payload 里自带的 visitedAt 作为 INSERT 的值；visited_at 的填充由
            AuditMetaObjectHandler.insertFill 兜底（config/AuditMetaObjectHandler.java:23），
            但因为 payload 显式带了值，strictInsertFill 会跳过，不会被改成回写时刻
```

**为什么必须如此**（三个理由）：

1. **不变量**：`visited_at` 必须**单调且接近真实发生时刻**。若用回写时刻，一段网络抖动后堆积的埋点会被整体压到回写那一秒，趋势图会出现**假尖峰**。
2. **与 V14 的边界一致**：V14 的保留清理按 `created_at < cutoff` 物理删除（`RetentionService:227`），判断依据是**行内时间戳**。若时间戳被写成回写时刻，跨零点回写的埋点会被算到「新的一天」，导致**保留期边界与统计归属同时错位**。
   > 注：如 §8.1 R8 所述，V14 **当前并不清理 `visit_log`**；此条是为「将来把 `visit_log` 纳入保留策略」预留的正确性前提。**如果此后仍决定不清理 `visit_log`，则只有理由 1 成立** —— 但仍应采用进入时刻。
3. **幂等与重放**：回写失败重试时，用 payload 自带时间戳可以安全重放；用回写时刻则每次重试都会产生不同的值。

**实现提醒**：Redis List 元素本身不带时间，因此**时间戳必须写在 payload 内部**（如上），不能依赖「消费时刻」。
批量 `INSERT` 请用一条多值 `INSERT ... VALUES (...),(...)` 而非逐行插入，否则缓冲只减少了连接开销、没减少语句数。

### 3.5 明确「不要缓存」的部分

| 对象 | 原因 |
|---|---|
| 管理端所有**列表/详情/表单**接口（作品、新闻、轮询图、留言、评论、用户、角色、日志） | 后台是**低频且写后即读**的场景，缓存收益 ≈ 0，一致性风险 ≠ 0 |
| `sys_operation_log` / `AuditLogAspect` 相关读 | 审计数据要求强一致、实时 |
| 登录失败计数 / 账号锁定（`LoginLockService`） | **当前实现已是正确做法**（持久化在 `sys_user.failed_login_attempts` + `locked_until`，注释明确写了「重启/多实例不丢」，`admin/security/LoginLockService.java:13-14`）。**不要为了用 Redis 而改它** |
| `GET /api/health` | 无 IO，缓存无意义 |
| 富文本 `content` 字段单独缓存 | 体积大（MEDIUMTEXT，可能几十 KB）+ 与列表无关；如果要缓存，缓存**整个详情 VO** 而非字段 |

---

## 4. 「缓存三兄弟」详细设计

### 4.1 缓存穿透（Cache Penetration）

**本项目的高危落点**（全部是「用户可控的标识符」）：

| 接口 | 参数 | 可枚举性 | 当前后果 |
|---|---|---|---|
| `GET /api/works/{slug}` | `slug` | 任意字符串 | 查库 → 空 → 404（`WorkController:38-39`） |
| `GET /api/news/{slug}` | `slug` | 任意字符串 | 同上（`NewsController:36-37`） |
| `GET /api/learning/resources/{id}` | `id` | 任意整数 | 查库 → 空 |
| `GET /api/page-background/{pageKey}` | `pageKey` | 有白名单（**已防护**，`PageBackgroundController:36-38` 提前 return） | 已被拦截 ✅ |
| `POST /api/admin/auth/refresh` | `refreshToken` | UUID | `findByRefreshToken` **全表扫描** ← 最严重 |

**防护方案（按优先级）**：

#### 方案 P1（必做）：空值占位缓存

命中「确认不存在」的结果时，写入一个**短 TTL 的哨兵值**，让后续同样的无效请求在 TTL 内直接返回 404，不再查库。

```
KEY   cache:null:work:{slug}          VALUE "__NULL__"
TTL   60s + random(0..30s)            ← 必须短：否则新建的作品在 TTL 内仍返回 404
```

要点：

1. **哨兵用独立的 key 前缀 `cache:null:*`**，不要与被缓存的对象混在同一 key 空间 —— 便于单独监控「穿透缓存命中率」，也便于批量清理。
2. **哨兵 TTL 必须显著短于正常 TTL**（60 s vs 60 min）：正常内容的过期是「该更新了」，哨兵的过期是「也许刚被创建」。
3. **写路径必须清哨兵**。管理端新建作品（`WorkAdminService.create()`）或发布草稿（`update()` 改 `status`）时，必须 `DEL cache:null:work:{slug}`，否则「刚发的作品 404」这个 bug 会非常难查。
4. **只缓存「确定为不存在」**，不要把「数据库连不上」也写成哨兵 —— 那会把一次故障固化成 60 秒的假 404。

#### 方案 P2（推荐）：参数形状先行校验

在打缓存和打库**之前**，先按业务规则拒绝掉不可能存在的参数：

| 参数 | 校验规则 | 拦截效果 |
|---|---|---|
| `slug` | 长度 ≤ 100、匹配 `^[a-z0-9][a-z0-9-]*$` | 挡掉绝大多数扫描流量（`.php`、`..%2f`、随机串） |
| `id` | `> 0` 且 `≤ 当前最大 ID`（最大 ID 可缓存在 Redis，写时更新） | 挡掉 `-1`、`999999999` 这类枚举 |
| `category` / `pageKey` | 白名单枚举（`pageKey` 已有先例，直接推广） | 完全拦截 |

这一层**成本最低、收益最高**，且不引入任何一致性负担，建议**先于缓存实施**。

#### 方案 P3（可选，集群化后再考虑）：布隆过滤器

当 `work`/`news` 的 slug 总量达到数万级、且 P1+P2 仍被绕过时再引入：

- 用 Redis `BF.ADD` / `BF.EXISTS`（RedisBloom 模块）或用 `SETBIT` 手写；
- **必须与写路径联动**：新建/删除/改 slug 时同步更新布隆过滤器；
- **注意误判方向**：布隆过滤器只会误判「存在」（假阳性），不会漏判 —— 所以它挡穿透是安全的，但**不能替代 P1**（假阳性仍需回源）。

> **本项目的建议**：实施 P1 + P2。**P3 留到内容量真正上来再评估**，当前规模上布隆过滤器属于过度设计。

#### 方案 P4：为「刷新令牌」补索引 / 迁移到 Redis

`findByRefreshToken()` 的全表扫描是**结构性问题**，不是缓存能解决的。两个选项（二选一，推荐后者）：

- **补索引**：`ALTER TABLE sys_user ADD INDEX idx_sys_user_refresh_token (refresh_token(64));`
- **迁移 Redis**（推荐，见 §4.6）：令牌本就是 key-value，放 Redis 后既免了扫表，又天然获得 TTL（`refresh_token_expires_at` 字段可以退休）。

---

### 4.2 缓存击穿（Cache Breakdown / Hotspot Invalid）

**本项目的高危落点**：

| 热点 key | 为什么是热点 | 过期瞬间的并发来源 |
|---|---|---|
| `cache:site:footer` | 全局组件，每个路由进入都调（`AppFooter.vue:132`） | 所有在线用户 × 每次导航 |
| `cache:site:settings` | 全局配置，任何页面都可能读 | 同上 |
| `cache:site:about` | 进入 `/about` 的访客 | 营销引流时的瞬时峰值 |
| `cache:home:carousel` | 首页首屏 | 同上 |
| `cache:home:services` / `cache:home:capabilities` | 首页固定模块 | 同上 |
| `cache:home:works:featured` | 首页推荐位 | 同上 |
| `cache:auth:perm:{userId}` | 后台每次 API 调用 | 一个管理员的单个页面（前端并发 5–10 个请求）**同时**未命中 |

> **`cache:auth:perm:*` 格外注意**：它既是热点，又是**安全相关**的缓存。
> 击穿防护对它应**优先选「逻辑过期 + 异步续期」而非「分布式锁」** ——
> 因为对安全数据而言，**返回一份略旧的权限集合**与**让请求阻塞在锁上**相比，
> 前者可控（且已有 §6.3 的失效覆盖兜底），后者会把认证链路整体拖慢甚至排队。
> 若确有「绝不允许略旧」的要求，则应对该 key 采用 M2，并把锁等待上限压到最小。

**关键判断**：本项目**不是超大规模高并发场景**（单体、内网/小站），
所以「击穿」的实际风险**不在 QPS 本身，而在于放大的查询代价** —— 例如 `DashboardService.summary()` 一次调用要打 14 条查询（§2.7），
10 个并发未命中就是 140 条查询，足以让 MySQL 出现秒级卡顿。

因此方案按「代价从低到高」分两档，**建议先做 M1，不急着上分布式锁**。

#### 方案 M1（首选，性价比最高）：逻辑过期 + 异步续期

不使用 Redis 的物理 TTL 作为唯一过期依据，而是在 value 里存一个 `logicalExpireAt`；
物理 TTL 设置得远长于逻辑过期（例如逻辑 30 min、物理 24 h）。

```
读流程：
  1. GET key → 未命中               → 走 M2 的 singleflight 或直接回源（首次加载）
  2. 命中，logicalExpireAt > now    → 直接返回（绝大多数请求走这里）
  3. 命中，logicalExpireAt <= now   → 【立即返回旧值】+ 异步触发一次刷新
                                       ↑ 关键：没有请求会阻塞在回源上
```

**优点**：

- **没有任何请求会因缓存过期而变慢**（P99 稳定）；
- 天然解决了击穿（同一时刻只有一个异步刷新任务）；
- 与本项目已有的 `@EnableAsync` + `asyncExecutor`（`config/AsyncConfig.java:22-31`）完美契合，**不需要引入 Redisson**。

**缺点**：存在「数据比预期多旧一会儿」的窗口（最长 = 一次异步刷新的耗时，通常 < 100 ms）。
对本项目的内容/配置类数据（A/B 级）**完全可接受**。

**实现载体**：`CacheValue<T>` 包装类型

```java
public record CacheValue<T>(T data, Instant logicalExpireAt, long version) { }
```

#### 方案 M2（补充）：`SETNX` 分布式互斥锁（singleflight）

用于两个场景：
1. **首次加载**（缓存完全不存在，没有旧值可以返回）；
2. **绝不允许返回旧值**的数据（本项目中暂无，但保留能力）。

```
1. GET key → 命中则返回
2. SET lock:{key} <uuid> NX EX 10
   ├── 成功 → 查库 → SET key（含逻辑过期时间）→ DEL lock（Lua 比对 uuid 后删除）
   └── 失败 → 短暂 sleep（50ms）后重试 GET，最多重试 3 次 → 仍未命中则降级为直读 DB
3. finally: 必须用 Lua 脚本「比对 value 再删除」，否则会误删别人的锁
```

**必须注意的三个坑**：

1. **锁必须有超时**（`EX 10`），否则持锁进程崩溃会永久阻塞该 key；
2. **解锁必须比对持有者标识**（Lua 原子比对），不能用裸 `DEL`；
3. **等待方必须有上限**（重试 3 次后直读 DB），否则 Redis 抖动会变成请求堆积。

> **本项目的取舍**：M1 为主，M2 仅用于「缓存缺失 + 高代价查询」的组合
> （典型：`DashboardService.summary()`、`WorkService` 首次详情加载）。
> **不要给所有 key 都加分布式锁** —— 那会把每次缓存未命中的延迟从「一次查库」变成「一次查库 + 两次 Redis 往返」，得不偿失。

#### 方案 M3：热点 key 分级，避免「大 key」

`/api/works` 列表在作品数增长后，缓存 value 会变大（一次序列化/反序列化几十 KB）。
**建议**：列表接口缓存**分页后的 VO**（当前实现本身就是 `WorkCardVO`，不含 `content`，体积可控，`vo/WorkCardVO.java` 已刻意排除了正文与后台字段 —— 这个设计对缓存非常友好）。
**不要**缓存 `Work` 实体（含 `content` MEDIUMTEXT）。

---

### 4.3 缓存雪崩（Cache Avalanche）

**本项目的高危落点与成因**（逐条对应代码事实）：

| # | 成因 | 代码事实 | 后果 |
|---|---|---|---|
| 1 | **固定 TTL，同时过期** | `SiteSettingsService.CACHE_TTL_MILLIS = 10 * 60 * 1000`（`:28`）是常量 | 所有实例的所有配置 key 同一秒过期 |
| 2 | **Redis 重启 / 冷启动** | 引入 Redis 后新增 | 重启瞬间 0 命中，全部请求打向 MySQL |
| 3 | **定时任务集中** | 01:00 埋点聚合（`StatsService:69`）、02:00 备份（`application.yml:88`）、03:00（`application.yml:93`）、03:30 保留清理（`application.yml:94`） | 凌晨本就叠加；若再在此时清缓存，会与任务争抢连接池（当前 `max-active: 10`，`application.yml:28`） |
| 4 | **Redis 单点故障** | 引入 Redis 后新增 | **最严重**：所有挂在缓存上的接口直接 500 |
| 5 | **连接池过小** | Druid `max-active: 10`（`application.yml:28`） | 缓存击穿时 10 个连接瞬间打满，`max-wait: 10000` 后开始抛异常 |

**防护方案**：

#### 方案 A1（必做）：TTL 随机抖动

**禁止任何地方写固定 TTL 常量**。统一走工具方法：

```java
/** 基础 TTL 加 ±10% 抖动，避免批量 key 同时过期 */
static Duration jitter(Duration base) {
    long ms = base.toMillis();
    long delta = ThreadLocalRandom.current().nextLong(-ms / 10, ms / 10 + 1);
    return Duration.ofMillis(ms + delta);
}
```

#### 方案 A2（必做）：分级 TTL

不同性质的数据用**不同量级**的 TTL，避免「同一时刻集体过期」：

| 级别 | 数据 | TTL | 抖动 |
|---|---|---|---|
| L1 极稳 | 站点设置、页脚、关于我们、权限码表 | 30 min | ±10% |
| L2 稳定 | 轮播图、页面背景、服务方向、能力、学习资料 | 30 min | ±10% |
| L3 中频 | 作品/新闻列表、详情内容 | 10–60 min | ±10% |
| L4 易变 | 评论列表、后台仪表盘 | 60 s–5 min | ±20% |
| L5 会话 | 用户信息、权限集合 | 5–10 min | ±10% |
| L6 哨兵 | 不存在标记 | 60 s | +0–30 s（**只加不减**） |
| L7 计数 | 浏览量、PV 缓冲 | 不设 TTL（定时回写后由任务清理） | — |

#### 方案 A3（必做）：缓存预热

在应用启动完成后（`ApplicationReadyEvent`）**异步**把 A 级 + B 级热点加载进 Redis：

```java
@EventListener(ApplicationReadyEvent.class)
public void warmUp() {
    // 必须异步 + 必须 try/catch：预热失败不能影响启动
    // 必须限流：逐条 warm，不要一次性并发打 20 个查询
}
```

**注意**：预热本身是「集中打库」，必须（a）异步、（b）串行或小并发、（c）可开关（`app.cache.warmup.enabled`）。
多实例部署时建议加 `SETNX lock:warmup EX 60`，避免 N 个实例同时预热。

#### 方案 A4（必做）：熔断降级 —— Redis 不可用 → 直读 MySQL

**这是本方案最重要的工程约束。** 所有缓存访问必须包在「降级模板」里：

```
读：try { GET } catch (RedisException) { 记录熔断计数; return 直读 MySQL; }
写：try { SET } catch (RedisException) { 忽略（缓存写失败不该让业务失败）; }
失效：try { DEL } catch (RedisException) { 忽略 + 告警 }
```

并且建议加**熔断器**（连续 N 次 Redis 失败 → 直接跳过 Redis，M 秒后半开重试），
避免「Redis 挂了 → 每个请求都要等一次连接超时」把线程池拖死。

**本项目特别要求**：`/api/about`、`/api/footer`、`/api/site/settings`、`/api/carousel` 这四个
**每页必调**的接口，必须**在 Redis 完全不可用时仍能返回正确数据**。
这是验收标准的一部分，不是「尽量」。

#### 方案 A5（推荐）：连接池与超时参数

| 参数 | 建议值 | 理由 |
|---|---|---|
| `spring.data.redis.timeout` | **200–500 ms** | 默认值过长会让 Redis 抖动传染成应用卡顿 |
| `lettuce.pool.max-active` | 16（配合 `max-idle: 8`, `min-idle: 2`） | 需 `commons-pool2` 依赖 |
| `lettuce.pool.max-wait` | 200 ms | 拿不到连接就快速失败 → 走降级 |
| **不要**把 Redis 超时设得比 MySQL 还长 | — | 降级通路才有意义 |

#### 方案 A6（可选）：错开定时任务

若在凌晨清缓存/重建，把时间错到 04:00 之后，并遵守 A3 的「小并发」。

#### 方案 A7（生产建议）：Redis 自身的高可用（v1.1 新增）

A4 的降级通路**不能是唯一防线** —— 如果只有降级而没有 Redis 高可用，那么容错实际为 0：
Redis 一挂，全站就降级到「直连 MySQL 且无缓存」，**恰好是最需要缓存的时刻承受最大压力**。

| 部署形态 | 适用 | 说明 |
|---|---|---|
| **单实例 + 降级** | 本地开发、内网小站 | 本文档所有方案的最低要求。**必须配齐 A4**，否则不可上线 |
| **主从 + 哨兵（推荐生产）** | 正式部署 | Lettuce 原生支持 Sentinel 拓扑，Spring Boot 只需改 `spring.data.redis.sentinel.*`，**应用代码零改动**。故障切换期间由 A4 的熔断器兜住（这也是熔断器必须存在的原因） |
| Cluster | 本项目**不需要** | 数据量 < 100 MB（§9.2），分片无收益，只增加运维复杂度与跨 slot 操作限制（如 `SUNIONSTORE`、部分 Lua） |

**Lettuce 拓扑切换的注意点**：

1. 从「单机」切到「哨兵」**只改配置不改代码**，因此建议 **P0 阶段就把配置写成「单机/哨兵可通过环境变量切换」**，避免日后改造。
2. **哨兵切换期间**会有一小段连接失败期 → 必须确保 A4 的降级与熔断已生效，否则切换会表现为接口 5xx。
3. **主从是异步复制**：故障切换可能丢失最后几条写入。对本项目的影响仅限「浏览量增量、限流计数、PV 缓冲」，**均为可容忍弱一致的指标**；**不要**把刷新令牌（§4.6）放在未开 AOF 的主从实例上而不接受重新登录的代价。

---

### 4.4 衍生问题：写热点（浏览量计数）

**问题回顾**：`WorkService:34-42` / `NewsService:31-39` 是 GET 里做 SELECT + 全列 UPDATE。

**设计**：

```
写（浏览）：
  INCR  cache:views:work:{id}            ← 单次原子操作，无锁、无 SELECT
  SADD  cache:views:dirty                ← 标记该 key 有未回写增量（活跃索引）
  （不写 MySQL）

读（详情页）：
  内容部分  ← cache:work:detail:{slug}（JSON，不含 viewCount）
  计数部分  ← GET cache:views:work:{id}
  返回时拼装：VO.withViewCount(dbBase + redisDelta)

定时回写（每 1–5 min）：
  1. SMEMBERS cache:views:dirty          ← 取活跃 key 集合（默认方案，见下）
  2. 逐 key 用 GETDEL（Redis 6.2+，读+清原子）取出增量；随即 SREM cache:views:dirty
  3. UPDATE work SET view_count = view_count + ? WHERE id = ?   ← 增量更新，不是全列覆盖
  4. 失败则该 key 重新 SADD 回 dirty 集合，下轮重试
```

> **v1.1 变更：活跃 key 用 SET 索引，SCAN 仅作兜底。**
> v1.0 把「`SCAN cache:views:work:*` 遍历」写成主方案、SET 索引放在括号里作为备选 —— 这个主次关系是错的。
> 内容量增长到数万篇后，**每 1–5 分钟一次全 keyspace 游标扫描**会成为 Redis 的持续负担
> （`SCAN` 虽不阻塞，但游标遍历 + 模式匹配的 CPU 开销随 keyspace 线性增长）。
> 因此改为：**浏览时 `SADD cache:views:dirty {key}`，回写时 `SMEMBERS` 取用、处理完 `SREM`**。
> `SCAN` 只保留为**对账兜底**（例如每日一次，用于捞回因异常漏 `SADD` 的孤儿 key）。
> 该 SET 本身也需治理：给 `cache:views:dirty` 设一个兜底 TTL（如 7 天，每次 `SADD` 后续期），
> 防止异常 key 永久驻留。

**关键设计点**：

| 点 | 说明 |
|---|---|
| **必须用增量 UPDATE** | `UPDATE work SET view_count = view_count + ? WHERE id = ?`。**绝不能** `updateById`（`update-strategy: ignored` 会全列覆盖，把 `content` 一起重写，且会把计数写回旧值） |
| **`GETDEL` 而非 `GET`+`DEL`** | 两步之间到达的浏览会丢失。`GETDEL` 需 **Redis ≥ 6.2**（见 §2.1 版本基线） |
| **活跃 key 用 SET 索引** | 见上方 v1.1 变更说明；`SADD` 与 `INCR` 不在同一事务，最坏情况是漏标（靠 `SCAN` 兜底对账），**不会丢计数** |
| **DB 基准值的缓存** | `dbBase`（回写前的库里值）需要短 TTL 缓存，否则读详情又要查库 —— 与内容缓存同生命周期即可 |
| **重启丢失** | Redis 重启会丢掉未回写的增量。**可接受**（浏览量是弱一致统计指标）；若要更强保证，改用 Redis AOF `appendfsync everysec`（§9.3 已含） |
| **与内容缓存的隔离** | 计数 key **独立于** 内容 key。内容失效不影响计数；计数 key 永不过期 |

> **收益**：把「每次浏览一次 DB 写」变成「每 1–5 分钟每篇一次 DB 写」。以 1000 次浏览/天/篇计，写压力降低 **99% 以上**。

---

### 4.5 衍生问题：分布式限流（`RateLimitFilter` 的 Redis 化）

**现状问题**（`common/RateLimitFilter.java`）：

| 问题 | 代码位置 | 后果 |
|---|---|---|
| 桶存内存，**只增不减** | `:30` `ConcurrentHashMap<String, Bucket>` | **内存泄漏**：`key = 限流维度 + IP`，每个访问过的 IP 永久留一个 Bucket。被扫描时 IP 数量爆炸 |
| 进程内计数 | `:113-120` | 重启清零（限流被绕过）；多实例各自计数（实际额度 × N） |
| 阈值不可动态调整 | `@Value` 构造注入 | 改阈值要重启 |

**方案**：Bucket4j 官方 Redis 集成 `bucket4j-redis`（与现有 `bucket4j-core` 8.10.1 同版本线），
用 `LettuceBasedProxyManager` 把桶放到 Redis，并设置 `expireAfter` 自动淘汰空闲桶。

```xml
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-redis</artifactId>
    <version>8.10.1</version>   <!-- 与 bucket4j-core 对齐 -->
</dependency>
<!-- 注意：需 Redis 6.2+（依赖 SET 的 KEEPTTL / EXPIRETIME 等命令） -->
```

**收益**：① 修掉内存泄漏；② 多实例共享额度；③ 重启不丢；④ 为将来把「限流阈值」也搬进 Redis（动态生效）留好通路。

**补充建议**：`/api/ai/chat` **当前完全未限流**（`isLimited()` 的 if 链里没有它），而它每次调用都产生真实外部 API 费用。应一并纳入 Redis 限流。

---

### 4.6 衍生问题：刷新令牌与用户会话

**现状**：`AuthService.refresh()` → `SysUserMapper.findByRefreshToken()`（`mapper/SysUserMapper.java:15-17`）
→ `WHERE refresh_token = ?`，而 `sys_user.refresh_token` **没有索引**（`sql/mysql-init.sql:200`）
→ **每次刷新令牌请求都是一次全表扫描**。

**方案**：令牌本就是 key-value 结构，直接迁到 Redis：

```
SET  cache:refresh:{token}  {userId}  EX 604800        ← 与 app.admin.refresh-expire-days 对齐
GET  cache:refresh:{token}                              ← refresh 时读取
DEL  cache:refresh:{token}                              ← logout / 轮换时删除
```

**必须注意的一致性点**：

1. **轮换语义**：现有实现是「旧 token 立即失效（新 UUID 覆盖）」（`AuthService:68-81`），Redis 版必须 `DEL 旧` + `SET 新`，且最好用 Lua 保证原子；
2. **账号禁用/删除时要清理**：`AdminAccountService.delete()` / `update(status=0)` 必须清掉该用户的所有令牌与权限缓存（建议额外维护 `SET cache:user:tokens:{userId}` 以便批量清理）；
3. **`refresh_token_expires_at` 字段**：迁移后可保留（作为审计痕迹）或废弃，**但不要两处都作为权威** —— 必须明确「Redis 是唯一权威」；
4. **降级**：Redis 不可用时 refresh 会失败 → 用户被迫重新登录。这比「读到过期令牌」更安全，**可接受**。

---

## 5. 落地架构设计

### 5.1 键命名规范

```
{业务域}:{对象}:{标识}[:{维度}]

cache:site:settings                    站点公开设置快照
cache:site:group:about                 site_settings 的 about 组
cache:site:group:footer                site_settings 的 footer 组
cache:site:value:notify.sensitive_words 单个 setting_key
cache:about:page                       关于我们页面 VO
cache:footer:page                      页脚页面 VO
cache:carousel:home                    首页轮播
cache:bg:about / cache:bg:work-detail  页面背景
cache:home:services                    服务方向列表
cache:home:capabilities                能力列表
cache:work:list:{category|all}         作品列表（维度编码进 key）
cache:work:list:featured               首页推荐
cache:work:detail:{slug}               作品详情内容（不含 viewCount）
cache:news:list:{category|all}         新闻列表
cache:news:detail:{slug}               新闻详情内容（不含 viewCount）
cache:learning:list                    学习资料列表
cache:learning:item:{id}               学习资料详情
cache:comment:list:{targetType}:{targetId}  已审核评论树
cache:auth:user:{userId}               后台用户（脱敏：不含 password/refreshToken）
cache:auth:perm:{userId}               后台权限集合
cache:auth:tree:permissions            权限勾选树
cache:pubuser:{id}                     前台用户（脱敏）
cache:dashboard:summary:{days}         仪表盘汇总

cache:views:work:{id}                  浏览量增量（无 TTL）
cache:views:news:{id}                  同上
cache:views:dirty                      有未回写增量的 key 集合（SET 索引，兜底 TTL 7 天）
pv:buffer:{yyyyMMdd}                   PV 写缓冲（List，日分桶 + TTL）

cache:null:work:{slug}                 空值哨兵
cache:null:news:{slug}
cache:null:learning:{id}

lock:refresh:{key}                     互斥锁
lock:warmup                            预热互斥
rate:{维度}:{ip|userId}                限流桶
```

**规范要求**：

| 规则 | 说明 |
|---|---|
| **统一前缀 `cache:`** | 与业务无关的键（`rate:`、`lock:`、`pv:`）分开前缀，便于 `INFO keyspace` 与批量运维 |
| **不用 `KEYS` 做批量删除** | 生产环境 `KEYS` 会阻塞 Redis。用 `SCAN` + 游标，或**命名空间版本号**（见 §5.4） |
| **不在 key 里放用户输入原文** | `slug` / `category` 必须先校验形状（§4.1 P2）再入 key，防止超长 key / 无界 key 空间 |
| **key 长度控制在 100 字节内** | 短 key 省内存；`notify.sensitive_words` 这类长 key 建议只用于「单值」场景 |
| **多维度用已排序的固定顺序** | `{targetType}:{targetId}` 而非自由拼接，避免同一逻辑产生两个 key |

### 5.2 `site_settings` 的统一缓存视图

现状是「`publicSettings()` 有缓存，`listGroup()` / `getValue()` 没有」，导致 About/Footer/敏感词/保留配置全在裸查库。
Redis 版建议统一为**分级 + 分组 + 单值三种视图，共享同一份写失效**：

```
                ┌──────────────────────────────────────────┐
   写（updateAll）│  1. 更新 MySQL（事务内）                    │
                │  2. afterCommit → 失效下列全部            │
                └──────────────────────────────────────────┘
                                    ↓
   ┌────────────────────┬─────────────────────┬──────────────────────┐
   │ cache:site:settings│ cache:site:group:{g} │ cache:site:value:{k} │
   │ 全量公开分组快照     │ 按组快照（about/footer│ 单值（敏感词等）       │
   │ （A1 用）           │  /retention…）        │ （A4/A5 用）          │
   └────────────────────┴─────────────────────┴──────────────────────┘
```

**失效策略**：`site_settings` 行数少、写入极低频（只有管理员保存配置时），
因此**直接全部失效**最简单也最安全 —— 不需要做「精确到 key」的失效。

> **为什么不共享一份数据、三种视图各自解析？**
> 因为 `listGroup("footer")` 只需要 footer 组，把全量快照反序列化再过滤反而更慢。
> 组级快照的额外内存开销可以忽略（`site_settings` 全表也就几十 KB）。

### 5.3 一致性策略：Cache-Aside + 事务提交后失效（**关键**）

**统一采用 Cache-Aside（旁路缓存）**：

```
读：GET → 命中返回 / 未命中 → 查 DB → SET
写：先写 DB → 再失效缓存（DEL，不是 SET）
```

**本项目必须处理的四个事务/一致性陷阱**：

#### 陷阱 1：事务内失效 → 脏缓存

现有代码 `SiteSettingsService.updateAll()` 标了 `@Transactional`，末尾直接 `invalidatePublicCache()`：

```java
@Transactional
public void updateAll(List<KeyValueRequest> items) {
    ...
    invalidatePublicCache();   // ← 事务还没提交！
}
```

**风险**：`DEL` 之后事务回滚 → 缓存空了但数据没变（影响小）；
更危险的是**中间窗口**：`DEL` 之后、`COMMIT` 之前，另一个线程读缓存未命中 → 查库 → **读到旧数据并写回缓存** → 之后事务提交，缓存里永远留着旧值。

**方案**：失效动作必须挂到**事务提交之后**：

```java
TransactionSynchronizationManager.registerSynchronization(
    new TransactionSynchronization() {
        @Override public void afterCommit() { cacheEvict(keys); }
    });
```

或封装成注解/工具方法 `afterCommit(() -> evict(...))`。

> **说明**：延迟到提交后失效，会把脏缓存窗口从「可能永久」缩短到「一个提交耗时」。
> 若要做到强一致，需要「延迟双删」或订阅 MySQL binlog（Canal），**本项目不需要** —— 内容/配置类数据容忍秒级延迟。

#### 陷阱 2：`update-strategy: ignored`（全列更新）

`application.yml:60` 配置了 `update-strategy: ignored`，`updateById` 是**全列 UPDATE**。
管理端的 `update()` 都是「先 `selectById` 加载完整实体 → 改几个字段 → `updateById`」。
这对缓存**没有直接冲突**，但意味着：

- **任何** `updateById` 都可能改动任意字段 → **失效粒度只能按实体，不能按字段**；
- 回写浏览量计数（§4.4）**禁止**用 `updateById`，必须用增量 UPDATE，否则会把 `content` 等大字段一起重写。

#### 陷阱 3：`getValue()` 的「写后立刻读」

`RetentionService` 里存在「写入 `retention.sql_monitor_last_reset` 后立刻 `getValue()` 读回」的模式（`:240`）。
若给 `getValue()` 加缓存，**同一次请求内的写后读会读到旧值**。

**方案**：缓存失效必须**同步**（不能只依赖 `afterCommit` 异步回调），
且在同一事务内提供「写后读直达 DB」的内部方法；或最简单 —— **这类运维配置读路径不做缓存**（C 级处理）。

#### 陷阱 4：跨表联动失效

管理端的写操作常常**牵连多张表**，失效时必须一起清：

| 写操作 | 需要失效的缓存 |
|---|---|
| `WorkAdminService.create/update/delete` | `cache:work:*`、`cache:null:work:{slug}`、**首页 `cache:home:works:featured`** |
| `NewsAdminService.*` | `cache:news:*`、`cache:null:news:{slug}` |
| `CarouselAdminService.*` | `cache:carousel:home`、**`cache:bg:*`（同一张表！）** |
| `LearningAdminService.*` / `LearningCategoryService.update`（重命名会改资源行） | `cache:learning:*` |
| `ServiceItemAdminService.*` / `CapabilityAdminService.*` | 对应 `cache:home:*` |
| `SiteSettingsService.updateAll` | `cache:site:*`、`cache:about:page`、`cache:footer:page` |
| `CommentAdminService`（审核通过 / 删除） | `cache:comment:list:{targetType}:{targetId}` |
| `RoleService.update/delete` / `AdminAccountService.update` | **`cache:auth:perm:*`（该角色下所有用户）** ← 最容易漏 |
| `PublicUserAdminService`（禁言/删除） | `cache:pubuser:{id}` |

> **`carousel` 表被 `/api/carousel` 和 `/api/page-background` 两个接口共用**（`controller/CarouselController.java:28`、`controller/PageBackgroundController.java:39`），
> 这是**必须同时失效两处缓存**的典型陷阱。

### 5.4 批量失效：命名空间版本号

对「一个写操作要清掉一族 key」的场景（如 `cache:work:*`），
用 `SCAN` 逐个删既慢又可能漏（并发写入的新 key）。
更优雅的做法是**版本号**：

```
cache:v:{namespace}          → 当前版本号（整数，INCR 递增）
cache:work:detail:v{n}:{slug} → 实际 key 里带版本号

失效时只需：INCR cache:v:work
            → 旧版本的所有 key 瞬间不可达（自然过期回收）
```

**优点**：一次 `INCR` 代替 N 次 `DEL`，O(1)、原子、无遗漏。
**缺点**：旧 key 会残留到 TTL 到期（可用较短的物理 TTL 控制内存）。

> **本项目建议**：**A 级/B 级数据量都很小**，「直接 `DEL` 明确的几个 key」已经足够，
> **不必上版本号**。版本号留给 `cache:auth:perm:*` 这类「一个角色变更影响 N 个管理员」的场景作为备选。

### 5.5 序列化

#### 5.5.1 定调：统一用 `StringRedisTemplate` + 自定义 `ObjectMapper`（v1.1 收敛口径）

| 项 | **本方案推荐（唯一口径）** |
|---|---|
| 客户端 | `StringRedisTemplate`（即 key 与 value 的 Redis 侧都是 `String`） |
| Key | `StringRedisSerializer` —— 保持 `redis-cli` 可读、可 `SCAN`/人工排查 |
| Value | **由应用层用注入的 `ObjectMapper` 显式写出 JSON 字符串**，再用 `opsForValue().set(key, json, ttl)` |
| 反序列化 | 由调用方显式给出目标类型：`mapper.readValue(json, WorkDetailVO.class)` |
| 压缩 | 单 value > 10 KB（如富文本详情）考虑 `GZIP`；本项目多数 value < 5 KB，**首期不必** |

> **v1.1 变更：不再把 `GenericJackson2JsonRedisSerializer` 列为并列选项。**
> v1.0 在表格里把「`GenericJackson2JsonRedisSerializer`」与「自定义 `Jackson2JsonRedisSerializer`」
> 列成两个等价选项，同时在下方第 3 点又指出前者与 record 的默认类型信息有兼容坑 ——
> **口径自相矛盾**，且正文 §6.1 骨架本身就写的是显式 `ObjectMapper` 方案。
> 现统一为：**一律走 §6.1 的显式写法**，理由如下：
>
> 1. **回避类型信息机制**：`GenericJackson2JsonRedisSerializer` 依赖 `activateDefaultTyping`
>    在 JSON 里写入 `@class`。而 Java `record` 是 **final** 类，与默认类型信息组合有已知兼容问题；
>    且它会把 Java 全限定类名**持久化进缓存 value** —— 一旦 VO 改名/换包，历史缓存全部反序列化失败。
> 2. **类型由调用方已知**：本项目每个缓存点的目标类型在编译期就是确定的（`WorkDetailVO.class`
>    这种字面量），**根本不需要**运行时类型自描述。让 Redis 承担类型推断是多余复杂度。
> 3. **可读可演进**：纯 JSON 可读，且不锁死 Java 类名，便于按 5.5.3 做演进。
>
> 若团队已有强偏好使用 `RedisSerializer` 体系，**也请显式注册具体类型**而非依赖默认类型信息，
> 并同样遵守 5.5.2 / 5.5.3 的约束。

#### 5.5.2 必须注意（本项目实测会踩的坑）

1. **缓存 VO 而不是实体。** 现有 VO 已经刻意排除了后台字段与正文
   （`vo/WorkCardVO.java:5`「不含正文与后台字段」、`vo/NewsCardVO.java:7`「避免列表接口过度返回」）——
   这个设计对缓存是**直接利好**，请沿用。**不要**缓存 `Work`/`News` 实体（含 `content` MEDIUMTEXT）。
2. **`LocalDateTime` 必须能序列化。** `vo/WorkDetailVO.java:23`、`vo/NewsCardVO.java:14`、`vo/NewsDetailVO` 等含 `LocalDateTime`。
   注入的 `ObjectMapper` 必须 `registerModule(new JavaTimeModule())` 并关闭时间戳写法，否则反序列化直接抛异常：
   ```java
   ObjectMapper cacheMapper = JsonMapper.builder()
           .addModule(new JavaTimeModule())
           .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)  // 写成人可读的 ISO-8601
           .build();
   ```
   > 这也是 `AboutPageService:53` 那个 `private static final ObjectMapper JSON = new ObjectMapper();`
   > 只敢用来处理 `List<Stat>`（纯 String）的原因 —— 不要照抄这个写法去做缓存序列化。
3. **时区口径（v1.1 新增，与上一条配套）。** 本项目的所有时间字段都是**无时区的 `LocalDateTime`**
   （`sql/mysql-init.sql` 中 `visited_at` / `created_at` 等均为 `DATETIME`，且 JDBC URL 固定了
   `serverTimezone=Asia/Shanghai`，见 `application.yml:17`）。
   因此序列化必须**保持无时区语义**：
   - ✅ 用 `JavaTimeModule` 默认行为输出/读入 `2026-09-12T11:23:59`（无 offset）；
   - ❌ **不要**开启 `WRITE_DATES_WITH_ZONE_ID`、不要序列化成 `Instant`/epoch 毫秒、不要用 `OffsetDateTime` 承载。
   **原因**：一旦 value 里带上 offset，跨时区部署（或容器 TZ ≠ `Asia/Shanghai`）时，
   同一个缓存 value 在 A 实例读出的「日期」与 B 实例不同 → 详情页时间显示漂移、
   `DashboardService` 的「今日」边界（`LocalDate.now()`，`DashboardService:47`）与缓存内容错位。
   **规则：全链路无时区 + 固定 `Asia/Shanghai`（与 JDBC URL 一致），缓存 value 不引入 offset。**
4. **不要缓存 `SysUser` 实体**（含 `password` BCrypt 哈希与 `refreshToken`）。
   `cache:auth:user:{id}` 里只放 `id/username/nickname/avatar/status/mustChangePassword`。

#### 5.5.3 VO 结构演进的兼容策略（v1.1 新增）

缓存里的 JSON 是**跨版本存活**的：一次发布升级 VO，上一版写入的 value 可能还在 TTL 内。
若不加约束，加字段/改类型会导致老缓存**反序列化失败**（→ 降级回源，尚可）或**静默丢字段**（→ 更危险）。

| 演进类型 | 风险 | 策略 |
|---|---|---|
| **加字段** | 老缓存缺该字段 → 反序列化得 `null`。若下游对 `null` 不容忍（如 `int` 拆箱）会 NPE | ① 新增字段一律用**包装类型**（`Integer` 而非 `int`）② 统一在 VO 上加 `@JsonIgnoreProperties(ignoreUnknown = true)` |
| **加字段（前端展示）** | 老缓存不含新字段 → **用户最长一个 TTL 看不到新内容**（"我明明配了却不显示"） | 对**内容类** VO，凡涉及**新增可见字段**的发布，**必须主动失效该命名空间缓存**（或在 key 里带 schema 版本，见下） |
| **删字段 / 改名** | 老缓存多出的字段 | `@JsonIgnoreProperties(ignoreUnknown = true)` 直接忽略，安全 |
| **改类型**（如 `String` → 对象） | 老缓存是字符串 → 反序列化异常 | **必须换 key**（加 schema 版本后缀），不能原地改 |
| **VO 改类名 / 换包** | 若用了默认类型信息（`@class`），历史 value 全部失效 | **这正是 5.5.1 弃用默认类型信息的理由**；显式类型不受影响 |

**落地方案（推荐同时做前两条，第二条为可选增强）**：

1. **给所有被缓存的 VO 加 `@JsonIgnoreProperties(ignoreUnknown = true)`**（零成本，挡住「删字段/改名」）；
2. **（可选增强）value 外层包一个带 schema 版本的信封**，版本不匹配时视为未命中并回源：
   ```java
   record CacheEnvelope(int v, Object data) { }   // v = 该 VO 的 schema 版本，改结构时 +1
   ```
   本项目 A/B 级数据量小、TTL 短（30–60 min），**首期用「发版时主动失效缓存」+ 第 1 条即可**，
   不必过早引入信封；但**必须把「改 VO 结构 → 清对应缓存」写进发布检查清单**（附录 C 已列）。

### 5.6 与现有基础设施的集成点

| 现有设施 | 集成方式 |
|---|---|
| `AsyncConfig.asyncExecutor`（core 2 / max 8 / queue 200） | 逻辑过期的**异步续期**直接复用；**注意**：`StatsService.recordPv` 已占用该池，若缓存续期量大需**单独建一个 `cacheRefreshExecutor`**，不要与埋点抢线程 |
| `@EnableScheduling` | 新增：① 浏览量回写（每 1–5 min）；② Redis 桶/哨兵清扫（每小时）；③ 缓存命中率统计上报 |
| Druid `stat` 过滤器 / `/api/admin/sql-monitor` | **缓存效果的可量化验证入口**：对比实施前后 `work`/`site_settings` 相关 SQL 的 `ExecuteCount`。这是本项目现成的「缓存收益度量工具」，务必用上 |
| `AccessLogFilter` | 已有每请求耗时日志（`[ACCESS] ... {}ms`）—— 可作为「缓存前后 P95 延迟」的数据源 |
| `RateLimitFilter` | 见 §4.5（Bucket4j Redis 化） |
| `RetentionService`（V14） | **注意边界（v1.1 更正）**：V14 清理的对象是 `sys_operation_log` 与审计日志文件，**不包含 `visit_log`**（`RetentionService:227`）。因此本方案新增的 Redis 键（`cache:views:*`、`pv:buffer:*` 等）**不会被 V14 覆盖**，需自行约定清理：`cache:views:*` 靠回写后 `SREM`/`GETDEL` 收敛，`pv:buffer:*` 靠日分桶 + TTL；Redis 侧统一以 `maxmemory-policy allkeys-lru` 兜底 |
| `application.yml` / `application-prod.yml` | 新增 `spring.data.redis.*` 与 `app.cache.*` 配置块（见附录 A） |

---

## 6. 关键实现骨架（示意）

> 以下为**设计示意代码**，用于说明结构与约束，不是最终实现。

### 6.1 统一的缓存访问模板（含降级）

```java
@Component
public class CacheHelper {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;          // 已注册 JavaTimeModule
    private final CacheCircuitBreaker breaker;  // §4.3 A4 熔断

    /**
     * 读缓存；Redis 任何异常都不抛出，直接返回 empty → 调用方回源 MySQL。
     * 这是"Redis 掉线不等于接口 500"的唯一落点。
     */
    public <T> Optional<T> get(String key, Class<T> type) {
        if (breaker.isOpen()) return Optional.empty();
        try {
            String json = redis.opsForValue().get(key);
            breaker.onSuccess();
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, type));
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存读取降级 key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** 写缓存；失败仅记日志，绝不影响业务返回值。 */
    public void set(String key, Object value, Duration ttl) {
        if (breaker.isOpen()) return;
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), Ttl.jitter(ttl));
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存写入降级 key={}: {}", key, e.getMessage());
        }
    }

    /** 事务提交后再失效，避免"清完缓存又回滚"与"回填旧值"竞态。 */
    public void evictAfterCommit(String... keys) {
        Runnable task = () -> { for (String k : keys) safeDelete(k); };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCommit() { task.run(); }
                });
        } else {
            task.run();
        }
    }
}
```

### 6.2 详情页：内容缓存 + 浏览量计数分离

```java
// 读：GET /api/works/{slug}
public WorkDetailVO detail(String slug) {
    // 1) 参数形状校验（穿透防护 P2）
    SlugValidator.requireValid(slug);

    // 2) 空值哨兵（穿透防护 P1）
    if (cache.get(NULL_KEY + slug, String.class).isPresent()) {
        throw new ResponseStatusException(NOT_FOUND, "作品不存在或已下线");
    }

    // 3) 内容：逻辑过期 + 异步续期（击穿防护 M1）
    Cached<WorkDetailVO> cached = cache.getWithLogicalTtl(DETAIL_KEY + slug, WorkDetailVO.class);
    WorkDetailVO content;
    if (cached == null) {
        content = loadFromDbOrNull(slug);          // 未命中（首次）：可加 M2 互斥
        if (content == null) {
            cache.set(NULL_KEY + slug, "__NULL__", Duration.ofSeconds(60));  // P1
            throw new ResponseStatusException(NOT_FOUND, "作品不存在或已下线");
        }
        cache.set(DETAIL_KEY + slug, content, Duration.ofMinutes(60));
    } else {
        content = cached.data();
        if (cached.logicallyExpired()) {
            cacheRefreshExecutor.execute(() -> refreshDetail(slug));  // 异步，不阻塞
        }
    }

    // 4) 浏览量：Redis 原子自增（写热点剥离），返回时叠加
    long delta = redis.opsForValue().increment(VIEW_KEY + content.id());
    return content.withViewCount(content.viewCount() + (int) delta);
}
```

```java
// 定时回写（每 2 分钟）：增量 UPDATE，绝不用 updateById
@Scheduled(fixedDelay = 120_000)
public void flushViewCounts() {
    for (String key : scanKeys("cache:views:work:*")) {
        String raw = redis.opsForValue().getAndDelete(key);   // GETDEL：读+清原子
        if (raw == null) continue;
        long delta = Long.parseLong(raw);
        if (delta <= 0) continue;
        try {
            workMapper.incrementViewCount(idOf(key), delta);   // UPDATE ... SET view_count = view_count + ?
        } catch (Exception e) {
            log.warn("浏览量回写失败，增量丢弃 {}: {}", key, delta);  // 弱一致指标，丢弃可接受
        }
    }
}
```

### 6.3 后台认证链路（B10）

```java
// JwtAuthFilter 改造后：每请求 0 次查库（命中时）
Claims claims = jwtService.parse(token);
Long userId = Long.valueOf(claims.getSubject());

// 1) 用户信息（脱敏 VO，TTL 5 min）
AdminUserVO user = cache.get(USER_KEY + userId, AdminUserVO.class).orElseGet(() -> {
    AdminUserVO fresh = loadAndCacheUser(userId);      // 未命中才查库
    return fresh;
});
if (user == null || user.status() != 1) return;         // 不设置认证 → 401

// 2) 权限集合（TTL 10 min）
Permissions perms = cache.get(PERM_KEY + userId, Permissions.class).orElseGet(() -> {
    Permissions fresh = loadAndCachePermissions(userId);  // 原 3 条 SQL 只在未命中时执行
    return fresh;
});
```

**失效点（必须全部覆盖，否则会「改了权限不生效」）**：

| 写操作 | 失效动作 |
|---|---|
| `RoleService.update(id, ..., permissionIds)` | 查出该角色下所有 `user_id` → 逐个 `DEL cache:auth:perm:{userId}` |
| `RoleService.delete(id)` | 同上 |
| `AdminAccountService.update(id, ..., roleIds)` | `DEL cache:auth:user:{id}` + `DEL cache:auth:perm:{id}` |
| `AdminAccountService.delete(id)` | 同上 + 清刷新令牌 |
| `AdminAccountService.updateProfile()` | `DEL cache:auth:user:{id}` |
| `AdminAccountController` 改密码 | `DEL cache:auth:user:{id}` + 令旧 access token 失效（当前 JWT 无黑名单，可接受 2 h 内自然过期，或引入 Redis 黑名单） |

> **一致性 vs 现状的取舍**：现有注释明确写了权限「实时 join 查询，修改即时生效」（`AdminPermissionService:18`）。
> 引入缓存后，**最坏延迟 = 失效动作是否覆盖到**。只要上表覆盖完整，就是**即时生效**（写后 `DEL`，下次读立即回源）。
> 这是「读多写极少」场景，`DEL` 式失效不会造成抖动。

> ### ⚠️ B10 的失效是**安全边界**，不是性能问题（v1.1 升格）
>
> 本文 v1.0 把这条只写在「风险 R4 / 一致性」里，**定级偏低，现予纠正**：
>
> - 权限缓存 TTL 为 10 min、用户缓存 TTL 为 5 min。若 `RoleService` / `AdminAccountService` 的**任一**写路径
>   漏了一次 `DEL cache:auth:perm:{userId}`，则：**被降权或被禁用的管理员，最坏仍可继续操作 10 分钟**。
> - 这不是「看到旧数据」的展示问题，而是**越权窗口**：一个已被撤销 `system:sql` 权限的账号，
>   在这 10 分钟内仍能调用 `/api/admin/sql-monitor/*`；
>   一个已被 `status=0` 禁用的账号，在这 5 分钟内仍能持有有效 Authentication 继续调用后台接口。
> - 而**当前实现（无缓存）不存在这个窗口** —— 权限每次实时 join，账号状态每次 `selectById` 实时校验
>   （`JwtAuthFilter.java:50` 的 `user.getStatus() == 1` 判定）。
>   **引入缓存等于用「即时生效 + 0 越权窗口」换取性能**，因此必须以「不引入越权窗口」为验收前提。
>
> **据此确立三条硬性要求**：
>
> 1. **失效覆盖面必须穷举并可测**：§6.3 的失效表是**功能规格的一部分**，不是实现细节。
>    建议为该表每一行写一条集成测试（断言写操作后 `cache:auth:*` 对应 key 立即不存在）。
> 2. **禁用/删除/改密必须即时生效**：`AdminAccountService` 的 `status=0`、`delete`、改密路径
>    必须同时清 `cache:auth:user:{id}` 与 `cache:auth:perm:{id}`，且**必须是同步 `DEL`**（不得仅依赖 `afterCommit` 异步回调）。
> 3. **提供兜底手段**：保留一个「清空全部权限缓存」的运维入口（例如 `cache:auth:*` 按命名空间版本号整体失效，见 §5.4），
>    用于怀疑失效遗漏时的应急处置。**有兜底才能接受这个缓存。**

---

## 7. 实施路线图

| 阶段 | 内容 | 前置 | 验收标准 |
|---|---|---|---|
| **P-1 零成本优化**（0.5 天，**不需要 Redis**） | §11 表格里的六项：敏感词 memoize、保留配置复用、`/auth/me` 复用已加载权限、前端去重 `watch`、页脚 store、**执行 `backend/sql/mysql-migration-v15-index-tuning.sql`** | 无 | `POST /api/comments`×100 后 `site_settings` 的 `ExecuteCount` 增量 ≤ 1；`/auth/me` SQL 条数 ≤ 4 |
| **P0 准备**（0.5 天） | ① 加依赖（`spring-boot-starter-data-redis`、`commons-pool2`）② 加 `RedisConfig`（**序列化器按 §5.5.1 统一为 `StringRedisTemplate` + 显式 `ObjectMapper`**，含 `JavaTimeModule`、无时区；超时 + 连接池）③ 加 `CacheHelper`（§6.1，含降级）④ **写 VO 往返序列化测试**⑤ 配置块按附录 A 写成**单机/哨兵可切换**形式 | Redis 实例可用且 **≥ 6.2**（§2.1） | 单测：每个待缓存 VO 存取后字段全等（含 `LocalDateTime`）；**手动 kill Redis，接口仍 200** |
| **P1 最高收益**（1 天） | ① **B10 后台认证链路**缓存 + 全部失效点 ② `site_settings` 三视图统一缓存（A1–A5） | P0 | 后台任一页面：`sql-monitor` 中 `sys_permission` 相关 `ExecuteCount` 不再随点击线性增长；**附录 C.1 安全验收全绿**（含「禁用管理员下一次请求即 401」） |
| **P2 前台热点**（1 天） | A3/A6/A7（页脚、页面背景、轮播）+ B1/B2/B5（服务/能力/学习资料） | P0 | `sql-monitor` 中 `learning_resources`/`service_item` 的 `ExecuteCount` 显著下降 |
| **P3 穿透防护**（0.5 天） | 参数形状校验（**可与 P-1 合并，无依赖**）+ 空值哨兵 + `slug`/`id` 校验 | P0 | 用 `curl` 枚举随机 slug ×1000，DB `ExecuteCount` 增量 ≈ 0 |
| **P4 写热点**（1 天） | §4.4 浏览量 Redis 计数 + 定时增量回写；`viewCount` 从缓存 VO 中剥离 | P0 | 浏览详情页 1000 次，`work` 表 UPDATE 次数 ≤ 2；并发 100 次计数精确等于 100 |
| **P5 雪崩加固**（0.5 天） | TTL 抖动（全局替换固定常量）+ 分级 TTL + 启动预热 + 熔断器 + 连接池参数；**生产环境评估主从 + 哨兵（§4.3 A7）** | P1–P4 | 重启 Redis 后压测：无 5xx，P95 < 阈值；触发一次主从切换无 5xx |
| **P6 扩展**（各 0.5 天，按需） | ① `RateLimitFilter` Redis 化（§4.5，顺带修内存泄漏）② 刷新令牌迁 Redis（§4.6，顺带消灭全表扫描）③ Dashboard 短 TTL（B11）④ AI 限流 + 会话 | P0 | 各自独立验收 |

**建议的取舍**：
- **无论是否上 Redis，都先做 P-1** —— 六项里没有一项需要缓存中间件，且零一致性风险。
- 若引入 Redis 后只能做两件事，**做 P1 的 B10（后台认证）和 P2 的 A3（页脚）** ——
  前者消除唯一「每请求固定 4 次查库」，后者消除「每路由一次查库（且是全表扫描）」，是「读放大」最严重的两处，
  且数据几乎不变，缓存收益立竿见影、一致性风险最低。

---

## 8. 风险与反模式清单

### 8.1 本项目特有的风险

| # | 风险 | 说明 | 对策 |
|---|---|---|---|
| R1 | **`update-strategy: ignored` + 缓存** | 全列 UPDATE 意味着任何一次 `updateById` 都可能改动任意字段 | 失效粒度按实体；计数回写用增量 UPDATE（§4.4） |
| R2 | **`@Transactional` 内失效** | `SiteSettingsService.updateAll():81` 现有写法有竞态 | 一律 `afterCommit` 失效（§5.3 陷阱 1） |
| R3 | **`carousel` 表双接口共用** | `/api/carousel` 与 `/api/page-background` 读同一张表 | 失效时两处都清（§5.3 陷阱 4） |
| R4 | **权限缓存导致「改权限不生效」→ 越权窗口（安全级，v1.1 升格）** | 现状是权限实时 join、账号状态实时校验（`JwtAuthFilter.java:50`），**引入缓存即引入一个最坏 5–10 min 的越权窗口**（详见 §6.3 警示框） | 失效点必须穷举覆盖 `RoleService`/`AdminAccountService` 全部写路径，每行配集成测试；禁用/删除/改密走**同步 `DEL`**；保留命名空间级兜底清空入口。**此项列入附录 C 安全验收** |
| R5 | **`getValue()` 写后读** | `RetentionService:240` 模式 | 该路径不加缓存，或同步失效 |
| R6 | **`LocalDateTime` 序列化** | `WorkDetailVO`/`NewsCardVO` 含时间字段；`AboutPageService:53` 的裸 `ObjectMapper` 是错误示范 | 统一 `ObjectMapper` + `JavaTimeModule`；P0 先写往返测试 |
| R7 | **`local-date` 边界** | `DashboardService` 用 `LocalDate.now()` 算今日；缓存跨零点会返回「昨天的今日数据」 | 仪表盘类短 TTL（60 s）不足以跨零点；key 里带上日期或在 TTL 计算时对齐到当日 23:59:59 |
| R8 | **`visit_log` 是一张「只增不减」的表（v1.1 更正）** | ⚠️ **v1.0 此处写错了**：本文曾称「V14 的清理任务会删除 `visit_log` 行」。经核实，`RetentionService` 的删除目标**只有 `sys_operation_log`**（`admin/service/RetentionService.java:227` 的 `logMapper.deleteBefore(cutoff, limit)`），**全文没有任何针对 `visit_log` 的 DELETE**。因此 `visit_log` **没有保留期、会无限增长** | 这**反而加强了 B11 的缓存必要性**（见 §2.7）：仪表盘对 `visit_log` 的 6 次扫描会随表增长**单调恶化**。同时这也意味着 `visit_log` 的容量治理是一个**独立于本方案的遗留问题**，建议单独立项（把 `visit_log` 纳入 V14 保留策略，或改用 Redis 日计数 + 明细采样） |
| R9 | **`max-active: 10` 的 Druid 池** | 缓存击穿时连接池先于 DB 成为瓶颈 | 配合 A4 熔断；必要时评估池大小 |
| R10 | **Redis 持久化与浏览量** | 浏览量增量只在 Redis 内存 | 明确接受弱一致；或开 AOF `everysec` |

### 8.2 明确的反模式（不要做）

| 反模式 | 为什么在本项目会出问题 |
|---|---|
| ❌ 给所有 Service 方法无脑加 `@Cacheable` | 管理端列表/表单是「写后即读」，加了只会带来不一致；且 `@Cacheable` 与 `@Transactional` 的失效时机容易出错 |
| ❌ 缓存 `Work`/`News` 实体 | 含 `content` MEDIUMTEXT，value 巨大 → 大 key、序列化慢、网络放大 |
| ❌ 缓存 `SysUser` 实体 | 含 BCrypt 哈希与刷新令牌 → 安全风险 |
| ❌ 用 `KEYS cache:work:*` 批量删 | 阻塞 Redis |
| ❌ 用固定 TTL 常量 | 现状 `CACHE_TTL_MILLIS` 已经是反面教材 |
| ❌ 给每个缓存点都加分布式锁 | 未命中延迟从「1 次查库」变成「1 次查库 + 2 次 Redis 往返」 |
| ❌ 把 `LoginLockService` 改成 Redis | 现实现（DB 持久化）是**正确的**，改 Redis 反而降低可靠性 |
| ❌ 让 Redis 异常冒泡到接口 | 会把「慢」变成「挂」 |

---

## 9. 监控与容量

### 9.1 必须观测的指标

| 指标 | 来源 | 健康阈值（建议） |
|---|---|---|
| 缓存命中率（按命名空间） | 应用计数器，或 `INFO stats` 的 `keyspace_hits/misses` | A 级 > 95%，B 级 > 80% |
| 缓存读耗时 P95/P99 | `AccessLogFilter` 已有耗时日志 | < 5 ms |
| 降级触发次数 | `CacheHelper` 熔断计数 | **必须为 0**，非 0 立即告警 |
| 空值哨兵命中率 | `cache:null:*` 命中计数 | 突增说明有人在枚举 → 查 `AccessLogFilter` 的 IP |
| 浏览量回写失败数 | 定时任务日志 | 0（非 0 说明 DB 或 SQL 有问题） |
| Redis 内存 / key 数量 | `INFO memory` / `DBSIZE` | 见 §9.2 |
| `evicted_keys` | `INFO stats` | 非 0 说明内存不足或 TTL 策略有误 |
| DB QPS | Druid `ExecuteCount` 增量 | 实施后应显著下降（**最直观的收益证据**） |

### 9.2 容量估算

| 命名空间 | 估算条数 | 单条大小 | 小计 |
|---|---|---|---|
| `cache:site:*` | < 20 | < 5 KB | < 100 KB |
| `cache:work:*` / `cache:news:*` | 内容量级（假设 500 篇） | 卡片 < 1 KB，详情 < 50 KB（富文本） | 卡片 < 1 MB，详情 < 25 MB |
| `cache:home:*` / `cache:bg:*` / `cache:carousel:*` | < 50 | < 5 KB | < 250 KB |
| `cache:auth:*` | 管理员数 × 2 | < 5 KB | < 100 KB |
| `cache:views:*` | 内容量级 | < 50 B | < 100 KB |
| `cache:null:*` | 受 TTL 限制 | < 50 B | 可忽略 |
| `pv:buffer:*`（若采用） | 按小时/天分桶 | 中等 | 视流量 |

**结论：总内存需求 < 100 MB**（以 500 篇内容、详情缓存全量计）。
**Redis 实例配 256 MB `maxmemory` + `allkeys-lru` 非常充裕**，本项目的容量压力可以忽略 ——
**风险完全在一致性、穿透和降级上，不在内存上。**

### 9.3 必须显式配置的 Redis 服务端项

```ini
maxmemory 256mb
maxmemory-policy allkeys-lru     ; 兜底：内存满时淘汰最久未用的缓存键
appendonly yes                   ; 若要保住未回写的浏览量增量
appendfsync everysec
bind 127.0.0.1
requirepass <强口令>              ; 即便本机也不要裸奔
```

---

## 10. 附录

### 附录 A：新增配置项

```yaml
spring:
  data:
    redis:
      # ---- 拓扑：单机 与 哨兵 通过环境变量切换，应用代码零改动（§4.3 A7）----
      # 单机（开发/小站）：默认走 host+port
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      # 哨兵（生产推荐）：设置 REDIS_SENTINEL_NODES 后启用，需把上面的 host/port 留空
      # sentinel:
      #   master: ${REDIS_SENTINEL_MASTER:mymaster}
      #   nodes: ${REDIS_SENTINEL_NODES:}
      #   password: ${REDIS_SENTINEL_PASSWORD:}
      password: ${REDIS_PASSWORD:}
      # ---- database 隔离 ----
      # 本应用独占 db 0。若与其他应用/组件共用同一 Redis 实例，必须换用不同 database
      # （如 1/2/...）或不同实例 —— 否则 CONFIG SET / FLUSHDB / SCAN 会互相影响。
      # 注意：Redis Cluster 只支持 db 0，若将来上 Cluster 则必须改为「按 key 前缀隔离」。
      database: ${REDIS_DATABASE:0}
      timeout: 300ms                 # 必须显式设置（§4.3 A5）
      connect-timeout: 200ms         # 连接建立超时，避免 Redis 不可达时线程长时间挂起
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
          max-wait: 200ms

app:
  cache:
    enabled: ${CACHE_ENABLED:true}          # 总开关，便于应急关闭
    warmup:
      enabled: ${CACHE_WARMUP_ENABLED:true}
    ttl:
      site: 30m
      content: 30m
      detail: 60m
      comment: 5m
      dashboard: 60s
      user: 5m
      permission: 10m
      null-marker: 60s
      views-dirty: 7d                        # cache:views:dirty 集合的兜底 TTL（§4.4）
    null-marker:
      enabled: true                          # 穿透防护开关
    jitter-ratio: 0.10                       # TTL 抖动比例
    circuit-breaker:
      failure-threshold: 10                  # 连续失败 N 次后打开
      open-duration: 30s                     # 打开后多久半开重试
```

> **`app.cache.enabled=false` 必须能一键回到「全部直读 MySQL」**。
> 这是灰度上线与故障应急的生命线：配置化开关比回滚代码快得多。

### 附录 B：建议随缓存一起补的索引

缓存**不能替代索引**（冷启动、未命中、缓存被清空时仍是全表扫描）。
以下索引与缓存实施无冲突，建议同批上线。

> **v1.1 变更：不再在文档里给裸 `ALTER TABLE`，已落成幂等迁移脚本。**
> v1.0 直接给了裸 `ALTER TABLE ... ADD INDEX`，**不符合本项目的迁移约定**：
> 本项目无 ORM 自动建表，增量变更一律走 `backend/sql/mysql-migration-vN-*.sql`，
> 且 MySQL 8 的 `ADD INDEX` **没有** `IF NOT EXISTS`，必须用 `information_schema` 判断 +
> `PREPARE/EXECUTE` 才能重复执行（该范式由 V11–V14 确立，见 `mysql-migration-v11-page-background.sql:42-50`）。
>
> **已产出：`backend/sql/mysql-migration-v15-index-tuning.sql`** —— 8 个索引全部为幂等写法，
> 可安全重复执行；脚本末尾还记录了「本批未纳入的索引及其原因」，避免后人重复评估。
>
> 执行方式：
> ```
> mysql --default-character-set=utf8mb4 -u root -p unicorn_studio < backend/sql/mysql-migration-v15-index-tuning.sql
> ```

**脚本覆盖的 8 个索引**（与 §2.4 的表一一对应）：

| # | 表 | 索引 | 服务的读路径 |
|---|---|---|---|
| 1 | `learning_resources` | `idx_learning_status_sort (status, sort_order, id)` | `LearningService.findAll()`（**该表原无任何二级索引**） |
| 2 | `capability_item` | `idx_capability_status_sort (status, sort_order, id)` | `CapabilityService.findPublished()`（**同左**） |
| 3 | `service_item` | `idx_service_item_status_sort (status, sort_order, id)` | `ServiceItemService.findPublished()` |
| 4 | `site_settings` | `idx_site_settings_group (group_name, setting_key)` | `/api/footer`、`/api/about`、保留配置的 `findByGroupName()` 全表扫描 |
| 5 | `sys_user` | `idx_sys_user_refresh_token (refresh_token(64))` | `AuthService.refresh()` 全表扫描（迁 Redis 后可跳过） |
| 6 | `backup_record` | `idx_backup_record_created (created_at)` | `BackupService.cleanupOldBackups()` 全表扫描 + filesort |
| 7 | `comment` | `idx_comment_parent (parent_id)` | `CommentService.listApproved()` 的子评论查询 |
| 8 | `sys_operation_log` | `idx_operation_log_module_status (module, status)` | 后台日志筛选（`created_at`/`username` 已有索引） |

> **注意 `work.featured` / `work.category` / `news.category`**：目前只有 `idx_work_status_sort(status, sort_order)`，
> `?featured=true` 与 `?category=` 是索引前缀扫描后的后置过滤。
> 由于这两类查询**结果集小、且将被缓存覆盖**，**首期不必加索引**；等内容量增长后再按实际慢查询日志评估
> （Druid `slow-sql-millis: 2000` 已在 `application.yml:33` 开启，有现成的观测手段）。
> 同理未纳入的还有 `public_user.created_at`、`public_user.ban_until`、`message.*`（LIKE，B-Tree 无效）、
> `visit_log.referer`（基数高、且将被 B11 缓存覆盖）—— 完整原因见迁移脚本末尾注释。

### 附录 C：验收清单

#### C.1 安全验收（v1.1 升格，**不通过则不得上线**）

> 背景见 §6.3 警示框：引入权限缓存 = 引入潜在越权窗口。以下每条都必须有**对应的集成测试**。

- [ ] **禁用管理员即时生效**：`AdminAccountService.update(id, status=0)` 后，该账号**下一次请求即 401/403**（不必等待 5 min TTL）
- [ ] **降权即时生效**：把某角色移除 `system:sql` 后，该角色下所有管理员的**下一次请求**即无法访问 `/api/admin/sql-monitor/*`
- [ ] **改角色权限即时生效**：`RoleService.update(id, ..., permissionIds)` 后，该角色下**每个**管理员的 `cache:auth:perm:{userId}` 均已被清除
- [ ] **删除管理员即时生效**：`AdminAccountService.delete(id)` 后，其 `cache:auth:user:{id}` 与 `cache:auth:perm:{id}` 均不存在，且旧 refreshToken 不可用
- [ ] **改密即时生效**：改密路径清 `cache:auth:user:{id}`
- [ ] **失效覆盖面穷举**：§6.3 失效表中**每一行**都有对应断言（写操作后目标 key 立即不存在）
- [ ] **兜底入口可用**：命名空间级清空（`cache:auth:*` 版本号失效）能一键让全部权限缓存失效
- [ ] **缓存中无敏感字段**：遍历 `cache:auth:user:*` 的所有 value，**断言不含 `password` / `refreshToken`**

#### C.2 降级与雪崩验收

- [ ] Redis **完全停机**时，`/api/about`、`/api/footer`、`/api/site/settings`、`/api/carousel`、`/api/works`、`/api/works/{slug}` 全部返回 **200 + 正确数据**，响应时间仅回落到未接入缓存前的水平
- [ ] `kill -9` Redis 后重启（冷缓存），**无 5xx**，DB QPS 峰值不超过基线 × 1.5
- [ ] 熔断器生效：连续 10 次 Redis 失败后**不再尝试连接**（观察日志无持续重试），30 s 后半开恢复
- [ ] 断言：全仓库**不存在**未加抖动的缓存 TTL 常量
- [ ] Redis 内存稳定，`evicted_keys` 长期为 0
- [ ] `app.cache.enabled=false` 后行为与接入前**完全一致**
- [ ] （若生产上哨兵）**主动触发一次主从切换**：切换期间接口无 5xx（由熔断降级兜住），切换后缓存自动回填

#### C.3 穿透验收

- [ ] 枚举 `GET /api/works/{随机slug}` × 1000：DB `work` 表 `ExecuteCount` 增量 ≈ 0（哨兵生效）
- [ ] 非法形状参数（`slug` 含非法字符、超长、`id<=0`）在**触达缓存与 DB 之前**即被拒绝
- [ ] 管理端新建并发布作品 → 立即访问其 slug **能正常打开**（不返回 404，哨兵已清）

#### C.4 写热点与一致性验收

- [ ] 浏览 `GET /api/works/{slug}` × 1000：`work` 表 UPDATE 次数 ≤ 2（计数已剥离）
- [ ] 并发浏览同一作品 100 次：`work.view_count` 增量 **恰好等于 100**（Redis `INCR` 修掉丢计数，§2.3）
- [ ] 回写使用增量 UPDATE：断言不存在 `updateById` 形式的 viewCount 回写；`content` 列相对回写前**无变化**
- [ ] `cache:views:dirty` 在回写后收敛为空；连续运行 24 h 无残留增长
- [ ] 管理端改页脚 → 刷新前台，**1 秒内**看到新值（失效生效）
- [ ] 管理端改轮播图 → `/api/carousel` **与** `/api/page-background/*` **同时**更新（`carousel` 双接口，§5.3 陷阱 4）

#### C.5 收益度量（用现成工具验证）

- [ ] 后台任一页面：`/api/admin/sql-monitor` 中 `sys_permission` 相关 `ExecuteCount` **不再随点击线性增长**
- [ ] **`GET /api/admin/auth/me` 单次请求的 SQL 条数从 ≈8 降到 ≤ 2**（§2.5 重复读已消除）
- [ ] **`GET /api/admin/dashboard/summary` 单次请求对 `visit_log` 的扫表次数从 6 降到 0（缓存命中时）**
- [ ] **`POST /api/comments` 连续 100 次：`site_settings` 的 `ExecuteCount` 增量 ≤ 1**（敏感词库已 memoize，§2.5）
- [ ] `GET /api/admin/retention` 单次请求 `site_settings` 读取次数从 2 降到 ≤ 1
- [ ] `learning_resources` / `service_item` / `capability_item` 相关查询不再出现全表扫描（`sql-monitor` 中 `ExecuteCount` 与耗时同时下降）

#### C.6 发布流程检查（v1.1 新增）

- [ ] **改动任何被缓存 VO 的结构时，同批清空对应命名空间缓存**（§5.5.3）
- [ ] 被缓存的 VO 均已加 `@JsonIgnoreProperties(ignoreUnknown = true)`
- [ ] 新增时间字段保持**无时区 `LocalDateTime`**，缓存 value 中不出现 offset（§5.5.2 第 3 条）
- [ ] 部署环境 **Redis ≥ 6.2**（否则 §4.4 需降级为 Lua、§4.5 需换手写滑动窗口，见 §2.1）

---

## 11. 一页总结

### 只需改几行、不需要 Redis 就能拿到的收益（建议先做）

| 改动 | 消除的浪费 | 工作量 |
|---|---|---|
| `SensitiveWordService.loadWords()` 加字段级缓存 + 写后失效 | **每次评论/留言 POST 都重查库 + 重 split** 一份几乎不变的 ~100 字节配置（§2.5） | 极小 |
| `RetentionService.status()` 复用 `loadConfig()` 的结果 | 同一请求内 `site_settings` **读两遍**（§2.5） | 极小 |
| `AuthService.me()` / `AdminAccountService.profile()` 复用 `JwtAuthFilter` 已加载的权限 | `/auth/me` 从 ≈8 条 SQL 降到 ≈4 条（§2.5） | 小 |
| 去掉 `WorkDetailPage.vue:199`、`LearningDetailPage.vue:190` 的重复 `watch` | 每次进详情页**同接口发两次**（§2.6） | 极小 |
| 给 `AppFooter` 的请求加个最小 store（或前端缓存） | 每次路由切换重复拉同一份页脚（§2.6） | 小 |
| 补附录 B 的索引 | 全表扫描（缓存未命中时仍是扫描） | 小 |

> **这六项合计能消掉本项目相当一部分读写浪费，且零一致性风险。建议作为 Redis 工作的前置步骤。**

### 引入 Redis 后，做这三件事收益最大、风险最小

1. **后台认证链路缓存（B10）** —— 消除全代码库唯一的「每请求固定 4 次查库」（`JwtAuthFilter` + `AdminPermissionService`）。数据几乎不变，失效点清晰可控。
2. **`site_settings` 三视图统一缓存（A1–A5）** —— 把 `listGroup()` / `getValue()` 这两个**当前完全裸查库**的路径纳入缓存，一次性解决页脚、关于我们、敏感词、保留配置四条高频读（且 `group_name` 无索引，每次都是全表扫描）。
3. **浏览量计数剥离到 Redis（C1）** —— 消除详情页 GET 里的读改写，把「每次浏览一次全列 UPDATE」降为「每几分钟一次增量 UPDATE」，**并顺带修掉并发丢计数**。

### 做之前必须先立的规矩

- 所有读缓存必须有**降级通路**（Redis 挂 ≠ 接口挂）—— 硬性要求；
- 所有 TTL 必须**带抖动 + 分级**，禁止固定常量；
- 所有失效必须在**事务提交后**，且覆盖跨表联动（尤其 `carousel` 的双接口、权限的角色联动）；
- 计数类数据**绝不进内容缓存**，也绝不用 `updateById` 回写；
- 序列化选型要**先用一个 VO 往返单测锁定**，再铺开（`LocalDateTime` + record 是两个已知坑）。

### 不要做的

缓存实体（含 `MEDIUMTEXT` / BCrypt 哈希 / 刷新令牌）、无脑 `@Cacheable`、给每处都加分布式锁、
给管理端「写后即读」的列表接口加缓存、用 `KEYS` 批量删除、把已经正确的 `LoginLockService` 改成 Redis。
