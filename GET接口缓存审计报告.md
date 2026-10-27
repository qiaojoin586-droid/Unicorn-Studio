# GET 接口缓存逻辑审计报告

> 审计对象：`backend/src/main/java` 下全部 51 处 `@GetMapping`（35 个文件）
> 判定标准：**Redis 优先读 → 未命中回源 MySQL → 结果回填 Redis → 返回前端**（Cache-Aside 完整闭环）

---

## 一、结论摘要

1. **预热清单里的 9 个 key，读路径全部具备"回源后回填"**（见第四节逐条核对）。因此"预热数据到期后，GET 接口查库却不写回 Redis"这一现象，**不发生在预热覆盖的接口上**。
2. 真正没有实现完整闭环的是 **6 个公开 GET 接口**：
   - 完全未接入 Redis（每次都打 MySQL）：`GET /api/works`、`GET /api/news`、`GET /api/comments`
   - 只做了"空值哨兵"负缓存、正数据永不回填：`GET /api/works/{slug}`、`GET /api/news/{slug}`、`GET /api/learning/resources/{id}`
3. 其中 `/api/works` 与 `/api/works/{slug}` 是首页与作品页的热路径（`HomePage.vue:252`、`WorksPage.vue:104`、`WorkDetailPage.vue:194`），是唯一"高频 + 零缓存"的组合，最符合"缓存到期后大量查库"的表现。
4. 代码里有直接佐证：`WorkMapper.cardProjection()`、`NewsMapper.cardProjection()` 的注释明写"列表结果是要进 Redis 缓存的""同时放大 Redis 里的列表缓存体积"，但 `WorkService` / `NewsService` 中**没有任何 `CacheHelper` 调用**，`CacheKeys` 里也没有对应的列表键 —— 说明列表缓存从未落地。
5. 旁证：`application.yml:251-263` 声明了 `app.cache.ttl.detail: 60m`、`app.cache.ttl.comment: 5m`，但全项目 **0 处** 读取 `app.cache.ttl.*`（各服务均硬编码 `Duration`）。这两个未使用的配置项，恰好对应"详情"和"评论"两个未实现缓存的场景。

---

## 二、缓存基础设施与判定口径

| 组件 | 位置 | 关键约定 |
|---|---|---|
| `CacheHelper` | `common/cache/CacheHelper.java` | `get()` 未命中/降级返回 `Optional.empty()`（113-142）；`set()` 失败只记日志（161-171）；key 统一加 `{profile}:` 前缀（63-106）；降级不抛异常 |
| `CacheCircuitBreaker` | `common/cache/CacheCircuitBreaker.java` | 连续失败 10 次打开 30s；**打开期间 `get` 与 `set` 都被短路**（`CacheHelper:114`、`CacheHelper:162`）→ 这段时间"既不读也不写" |
| `CacheKeys` | `common/cache/CacheKeys.java` | 仅定义：footer/about/join/carousel/bg/home-services/home-capabilities/learning-list/null 哨兵/views/refresh/dashboard/permission/ai。**无 works、news、comments 列表键** |
| `CacheWarmup` | `common/cache/CacheWarmup.java` | 仅 `ApplicationReadyEvent` 触发一次；预热 9 项（86-102）；`SETNX cache:lock:warmup` 5 分钟，**不主动释放** |

预热清单（`CacheWarmup.warm()`，86-102）：

| # | 预热项 | 写入 key | 调用 |
|---|---|---|---|
| 1 | footer | `cache:footer:page` | `FooterService::load` |
| 2 | about | `cache:about:page` | `AboutPageService::load` |
| 3 | home-services | `cache:home:services` | `ServiceItemService::findPublished` |
| 4 | home-capabilities | `cache:home:capabilities` | `CapabilityService::findPublished` |
| 5 | learning-list | `cache:learning:list` | `LearningService::findAll` |
| 6 | carousel-home | `cache:carousel:home` | `warmCarouselHome`（直写） |
| 7-9 | bg-about / bg-work-detail / bg-works | `cache:bg:{pageKey}` | `warmBackground`（直写） |

> 注意：`cache:join:page`、`cache:site:settings`、`cache:site:group:*` **不在预热清单**，只有首次请求才会建立。

---

## 三、A 类：完整实现（Redis → DB → 回填）

### 3.1 公开接口（9 个）

| 接口 | 入口 | 缓存 key / TTL | 回源 + 回填位置 | 预热 |
|---|---|---|---|---|
| `GET /api/about` | `AboutController:26-29` | `cache:about:page` / 30m | `AboutPageService.load()` 70-76 | ✅ |
| `GET /api/footer` | `FooterController:26-29` | `cache:footer:page` / 30m | `FooterService.load()` 96-102 | ✅ |
| `GET /api/join` | `JoinController:26-29` | `cache:join:page` / 30m | `JoinPageService.load()` 66-72 | ❌ |
| `GET /api/carousel` | `CarouselController:35-48` | `cache:carousel:home` / 30m | 控制器内 `orElseGet` 39-46 | ✅ |
| `GET /api/page-background/{pageKey}` | `PageBackgroundController:46-60` | `cache:bg:{pageKey}` / 30m | 控制器内 `orElseGet` 52-58 | ✅（3 个 pageKey） |
| `GET /api/services` | `ServiceItemController:23-26` | `cache:home:services` / 30m | `ServiceItemService.findPublished()` 32-41 | ✅ |
| `GET /api/capabilities` | `CapabilityController:23-26` | `cache:home:capabilities` / 30m | `CapabilityService.findPublished()` 32-41 | ✅ |
| `GET /api/learning/resources` | `LearningController:28-32` | `cache:learning:list` / 30m | `LearningService.findAll()` 44-61 | ✅ |
| `GET /api/site/settings` | `SiteSettingsController:22-25` | `cache:site:settings` / 10m | `SiteSettingsService.publicSettings()` 146-160 | ❌ |

写法统一为：

```java
return cache.get(KEY, TYPE).orElseGet(() -> {
    Object vo = 查库();          // 回源 MySQL
    cache.set(KEY, vo, TTL);     // 回填 Redis
    return vo;
});
```

**结论：这 9 个接口的 TTL 到期后，第一个请求会重新查库并 `cache.set` 回填，不存在"只查库不回填"的问题。**

### 3.2 后台复用同一服务的接口（3 个）

| 接口 | 入口 | 说明 |
|---|---|---|
| `GET /api/admin/about` | `AboutAdminController:25-29` | 复用 `AboutPageService.load()`，同上 |
| `GET /api/admin/footer` | `FooterAdminController:30-34` | 复用 `FooterService.load()` |
| `GET /api/admin/join` | `JoinAdminController:25-29` | 复用 `JoinPageService.load()` |

### 3.3 其他带缓存的接口（2 个）

| 接口 | 入口 | key / TTL | 备注 |
|---|---|---|---|
| `GET /api/admin/dashboard/summary` | `DashboardController:24-28` | `cache:dashboard:summary:{days}` / 60s | `DashboardService.summary()` 58-66，完整闭环 |
| `GET /api/admin/auth/me` | `AuthController:47-51` | `cache:auth:perm:{userId}` / 10m | 用户信息本身查库，权限集合走 `AdminPermissionService.loadPermissionsCached()` 84-92 |

### 3.4 部分缓存（1 个）

| 接口 | 入口 | 情况 |
|---|---|---|
| `GET /api/ai/config` | `AiConfigController:47-51` | `AiConfigProvider.runtimeConfig()` 63-69 走 `cache:ai:config`（5m，完整闭环）；但同一次响应里 `configProvider.rawGroup()`（75-81）**直查 DB** 用于回显掩码 api-key。属"写侧视图故意不走缓存"，非缺陷 |

---

## 四、B 类：只做"空值哨兵"，正数据永不回填（重点问题）

这三个接口**先读 Redis**，所以看起来"接了缓存"，但读的只是"该 id/slug 不存在"的标记；**查到了真实数据反而什么都不写**。哨兵 TTL 仅 60s，过期后每个请求都是"1 次 Redis GET + 1 次 MySQL SELECT"。

### 4.1 `GET /api/works/{slug}`

- 入口：`WorkController:37-46`
- 服务：`WorkService.findPublishedBySlug()` `50-63`

```java
50: public Optional<Work> findPublishedBySlug(String slug) {
51:     if (cache.getString(CacheKeys.nullWork(slug)).isPresent()) {   // 只读"不存在"标记
52:         return Optional.empty();
53:     }
54:     Optional<Work> work = workMapper.findBySlugAndStatus(slug, PUBLISHED);  // 全列，含 MEDIUMTEXT content
55:     if (work.isPresent()) {
57:         long delta = viewCountService.increment("work", w.getId());        // 只 INCR 浏览量
58:         w.setViewCount(w.getViewCount() + (int) delta);
59:     } else {
60:         cache.setString(CacheKeys.nullWork(slug), "__NULL__", NULL_TTL);   // 只在"未找到"时写
61:     }
62:     return work;
63: }
```

**问题**
- 命中真实数据时**没有任何 `cache.set`**，作品详情正文（`MEDIUMTEXT`，上限 16MB）每次请求都从 MySQL 读整列。
- 每个详情页请求固定产生：`Redis GET` + `SELECT *` + `Redis GET/INCR`（`ViewCountService.increment`）。
- 缓存里只有 `cache:null:work:{slug}`，且 60s 后消失，之后连"Redis 优先"这一步都变成空转。
- 该接口无法直接缓存实体：`WorkDetailVO` 含 `viewCount`（`WorkDetailVO:23`），缓存会冻结浏览量。需要"缓存内容 + 实时叠加增量"或缓存不含 viewCount 的 VO。

### 4.2 `GET /api/news/{slug}`

- 入口：`NewsController:35-43`
- 服务：`NewsService.findPublishedBySlug()` `44-55`

```java
45: if (cache.getString(CacheKeys.nullNews(slug)).isPresent()) return Optional.empty();
48: Optional<News> news = newsMapper.findBySlugAndStatus(slug, PUBLISHED);
49: if (news.isPresent()) {
50:     viewCountService.increment("news", news.get().getId());
51: } else {
52:     cache.setString(CacheKeys.nullNews(slug), "__NULL__", NULL_TTL);
53: }
```

**问题**：与 4.1 完全同构。`NewsDetailVO` **不含** viewCount（`NewsDetailVO:8-16`），是三者中最容易补正向缓存的（可直接缓存 VO）。当前公开前台没有新闻页（`frontend/src` 中 0 处引用 `/api/news`），实际影响小，但接口缺陷成立。

### 4.3 `GET /api/learning/resources/{id}`

- 入口：`LearningController:34-43`
- 服务：`LearningService.findPublishedById()` `64-76`

**问题**：同样只有 `cache:null:learning:{id}` 负缓存。方法注释（63 行）写明"含正文 content，不入缓存"，属**有意不缓存**；但这意味着详情页每次请求都读一次 `MEDIUMTEXT`，与列表接口已做的列投影优化（44-61）不在同一水位。

---

## 五、C 类：完全未接入 Redis（每次直查 MySQL）

### 5.1 `GET /api/works`（影响最大）

- 入口：`WorkController:26-35`
- 服务：`WorkService.findPublished()` `33-41`：三个分支全部直查 Mapper
  - `featured=1` → `findByStatusAndFeaturedOrderBySortOrderAscIdDesc`
  - `category` 非空 → `findByStatusAndCategoryOrderBySortOrderAscIdDesc`
  - 否则 → `findByStatusOrderBySortOrderAscIdDesc`
- **问题**
  - 没有任何 `CacheHelper` 读或写；`CacheKeys` 中也没有列表键。
  - 首页每打开一次就调 `/api/works?featured=true`（`HomePage.vue:252`），作品页调 `/api/works`（`WorksPage.vue:104`）→ 全部落到 MySQL。
  - `WorkAdminService` 的失效逻辑只有 `nullWork(slug)`（116、173 行）与 SEO/评论清理，**没有任何列表缓存失效**，进一步证明列表缓存从未实现。
  - 自相矛盾的注释：`WorkMapper.cardProjection()` 22-38 行写"列表结果是要进 Redis 缓存的……缓存体积也跟着一起被放大"，但缓存层不存在。

### 5.2 `GET /api/news`

- 入口：`NewsController:27-33`
- 服务：`NewsService.findPublished()` `32-37`：按 `category` 分支直查 Mapper，无缓存。
- `NewsMapper.cardProjection()` 22-29 行同样写着"同时放大 Redis 里的列表缓存体积"。
- 现状：公开前台未使用该接口（无新闻页），影响低于 works，但缺陷同源。

### 5.3 `GET /api/comments`

- 入口：`CommentController:34-41`
- 服务：`CommentService.listApproved()` `100-130`
- **问题**
  - 每次请求 2~3 条 SQL：一级评论 `COUNT` + `LIMIT` 分页（102-109）、子评论 `IN` 查询（116-118）；分页参数任意组合，天然适合短 TTL 缓存。
  - `application.yml:255` 的 `app.cache.ttl.comment: 5m` 已声明，但无任何代码读取 —— 又是一处"配置写了、实现没做"。
  - 审核通过/删除/回复（`CommentAdminService`）完全没有缓存失效钩子（因为根本没有缓存）。

---

## 六、D 类：后台 GET 直查数据库（24 个）

后台管理页需要"最新值"，不做缓存通常合理；此处仅列出以证明"未实现"的范围，不建议一律加缓存。

| 文件 | 行 | 接口 |
|---|---|---|
| `AdminAccountController` | 55 / 90 | `/api/admin/admins`、`/api/admin/profile` |
| `RoleController` | 33 / 66 | `/api/admin/roles`、`/api/admin/permissions` |
| `CarouselAdminController` | 24 | `/api/admin/carousel` |
| `CapabilityAdminController` | 34 | `/api/admin/capabilities` |
| `CommentAdminController` | 37 | `/api/admin/comments` |
| `FileController` | 37 | `/api/admin/files` |
| `BackupController` | 31 | `/api/admin/backups` |
| `LearningAdminController` | 46 / 101 | `/api/admin/learning/resources`、`.../categories` |
| `MessageAdminController` | 31 / 42 | `/api/admin/messages`、`/{id}` |
| `NewsAdminController` | 28 / 39 | `/api/admin/news`、`/{id}` |
| `OperationLogController` | 25 | `/api/admin/logs` |
| `PublicUserAdminController` | 28 / 40 | `/api/admin/users`、`/{id}` |
| `RetentionController` | 33 / 40 | `/api/admin/retention`、`/preview` |
| `ServiceItemAdminController` | 34 | `/api/admin/services` |
| `SettingController` | 23 | `/api/admin/settings`（`listGrouped()` 85-95 直查） |
| `WorkAdminController` | 27 / 38 | `/api/admin/works`、`/{id}` |

## 七、E 类：非业务/无需缓存（4 个）

| 接口 | 行 | 原因 |
|---|---|---|
| `GET /api/health` | `HealthController:47-58` | 探针必须反映"此刻"真实状态，注释 28 行明确不缓存 |
| `GET /api/admin/backups/{id}/download` | `BackupController:47-56` | 文件流 |
| `GET /api/admin/sql-monitor/stat` `/sqls` | `SqlMonitorController:74`/`92` | 运行时监控数据 |
| `GET /api/ai/config/tools`、`GET /api/ai/capabilities` | `AiConfigController:54`、`AiChatController:100` | 内存中的注册表/可用性探测，不查库 |

---

## 八、"预热到期后不回填"的核验

### 8.1 逐条核验：9 个预热 key 都会回填

| key | 读接口 | 回填代码 |
|---|---|---|
| `cache:footer:page` | `/api/footer`、`/api/admin/footer` | `FooterService:97-101` |
| `cache:about:page` | `/api/about`、`/api/admin/about` | `AboutPageService:71-75` |
| `cache:home:services` | `/api/services` | `ServiceItemService:33-39` |
| `cache:home:capabilities` | `/api/capabilities` | `CapabilityService:33-39` |
| `cache:learning:list` | `/api/learning/resources` | `LearningService:45-59` |
| `cache:carousel:home` | `/api/carousel` | `CarouselController:39-45` |
| `cache:bg:about` | `/api/page-background/about` | `PageBackgroundController:52-57` |
| `cache:bg:work-detail` | `/api/page-background/work-detail` | 同上 |
| `cache:bg:works` | `/api/page-background/works` | 同上 |

→ **这些 key 过期后，第一个请求必然重新 `cache.set`。**

### 8.2 那"到期后 Redis 里没有数据"可能是什么

按可能性排序：

1. **观察的接口不在缓存范围内**（最可能）：`/api/works`（首页/作品页）、`/api/works/{slug}`、`/api/learning/resources/{id}`、`/api/comments`。这些接口无论预热是否到期都不写缓存；其中详情接口在 60s 哨兵过期后连 Redis 读取都命中不了。
   - 验证：把"看到查库日志"的请求 URL 与本文第三、五节对照即可定位。
2. **Redis 熔断打开**：`CacheCircuitBreaker` 连续失败 10 次后打开 30s，期间 `CacheHelper.get` 直接返回 empty、`CacheHelper.set` 直接 return（114、162 行）→ 表现为"查库且不回填"，恢复后自动好转。
   - 验证：检索日志 `缓存读取降级` / `缓存写入降级`，或 `GET /api/health` 的 `redis` 字段。
3. **序列化/反序列化口径不一致**：`cache.set` 成功但 `cache.get` 每次抛异常被降级（日志同上），表现为"key 存在但每次都回源"。
   - 验证：`redis-cli --scan --pattern 'prod:cache:*'` 看 key 是否存在，配合日志确认是否反序列化失败。

### 8.3 预热机制本身的三个弱点（会放大"集中回源"）

1. **只在启动时跑一次**（`CacheWarmup:76-83`），没有周期续期；9 个 key 的 TTL 都是 30m，`CacheHelper.jitter()` 只做 ±10% 抖动 → 全部在 27~33 分钟区间集中过期，形成一次同步回源尖峰。
2. **锁不释放**：`tryLock()` 129-133 用 `SETNX` 5 分钟，成功后没有任何 `delete`；5 分钟内重启会整体跳过预热。
3. **预热覆盖不全**：`cache:join:page`、`cache:site:settings`、`cache:site:group:*` 不在清单里，冷启动首个请求必然回源。

---

## 九、修复建议（按收益排序）

| 优先级 | 项 | 做法 |
|---|---|---|
| P0 | `/api/works` 列表 | key 需含 `featured` / `category` 维度（如 `cache:works:list:{featured}:{category}`），TTL 30m，`orElseGet` 回填；`WorkAdminService` 的 create/update/delete/changeStatus/sort 统一 `evictByPatternAfterCommit("cache:works:list:*")` |
| P0 | `/api/works/{slug}` 详情 | 缓存"不含 viewCount 的详情 VO"（或实体），命中后再叠加 `ViewCountService` 增量；写侧按 slug 失效；null 哨兵保留 |
| P1 | `/api/comments` | key 含 `targetType/targetId/page/size`，TTL 5m（对齐 `app.cache.ttl.comment`）；审核/删除/回复后按 `targetType+targetId` 前缀失效 |
| P2 | `/api/news`、`/api/news/{slug}` | 与 works 同构；`NewsDetailVO` 不含 viewCount，可直接缓存 VO |
| P2 | `/api/learning/resources/{id}` | 若要缓存正文，需单独评估 MEDIUMTEXT 体积；否则至少在文档/配置中删掉未使用的 `ttl.detail` |
| P3 | 预热 | 释放 `cache:lock:warmup`；补 `join` / `site:settings`；考虑 `@Scheduled` 周期续期（或提前 `refresh-ahead`），避免 9 个 key 同步过期 |
| P3 | 配置一致性 | `app.cache.ttl.*` 全项目 0 引用，要么接入 `@Value`，要么删除，避免"看起来配了其实没生效" |

---

## 十、修复记录（已实施）

编译验证：`mvn -o -DskipTests compile` → **BUILD SUCCESS**。

### 10.1 新增缓存键（`common/cache/CacheKeys.java`）

| 键 | 用途 |
|---|---|
| `cache:works:list:{featured\|all\|cat-xxx}` | 作品列表（按查询维度分键） |
| `cache:works:detail:{slug}` | 作品详情 |
| `cache:news:list:{all\|cat-xxx}` | 新闻列表 |
| `cache:news:detail:{slug}` | 新闻详情 |
| `cache:comment:list:{type}:{id}:{page}:{size}` | 评论列表（前缀失效用 `commentListPrefix`） |

### 10.2 读端（Redis 优先 → 回源 → 回填）

| 接口 | 改动 | TTL |
|---|---|---|
| `GET /api/works` | `WorkService.findPublished` 缓存 `List<WorkCardVO>`；维度与三查询分支严格对齐；分类超 64 字符不入缓存 | 30m |
| `GET /api/works/{slug}` | `WorkService.findPublishedBySlug` 缓存 `WorkDetailVO`；命中仍执行浏览量 INCR；空值哨兵保留 | 10m（浏览量冻结上限） |
| `GET /api/news` | `NewsService.findPublished` 缓存 `List<NewsCardVO>` | 30m |
| `GET /api/news/{slug}` | `NewsService.findPublishedBySlug` 缓存 `NewsDetailVO`（无 viewCount，可长 TTL） | 60m |
| `GET /api/comments` | `CommentService.listApproved` 缓存整页结果；仅白名单 targetType + `page<100`/`size<=100` 参与缓存 | 5m |

### 10.3 写端失效

| 写侧 | 失效内容 |
|---|---|
| `WorkAdminService` create/update/delete/changeStatus | `cache:works:list:*` + 对应 slug 详情（改 slug 时新旧两个都清） |
| `WorkAdminService.sort` | `cache:works:list:*`（详情不受影响） |
| `NewsAdminService` 同上 | `cache:news:list:*` + 对应 slug 详情 |
| `CommentAdminService` changeStatus/reply/delete/batch | `cache:comment:list:{type}:{id}:*`（批量按目标去重） |

全部走 `evictAfterCommit` / `evictByPatternAfterCommit`，事务提交后才删，避免"清完缓存又回滚"。

### 10.4 预热（`CacheWarmup`）

- 新增预热：`cache:join:page`、`cache:works:list:featured`、`cache:works:list:all`。
- 预热结束在 `finally` 中释放 `cache:lock:warmup`，5 分钟内重启不再整体跳过预热。

### 10.5 未改动项与原因

- `GET /api/learning/resources/{id}`：`content` 为 MEDIUMTEXT（单条上限 16MB），按 id 缓存正文会让 Redis 内存与内容库同量级，保持原有"有意不缓存"的设计。如确需缓存，建议先限制正文字数或只缓存元数据。
- 24 个后台列表/详情 GET：管理端需要最新值，不加缓存。
- `app.cache.ttl.*`：本次未接入（各服务仍用各自的常量），属配置清理项，不影响功能。

### 10.6 验证方式

1. 冷启动后 `redis-cli --scan --pattern 'prod:cache:works:*'`，先访问首页/作品页，再确认 `cache:works:list:featured`、`cache:works:list:all` 与具体 slug 的 detail 键已生成。
2. 等 detail 键 TTL 过期（10m）后再次访问，观察 Druid SQL 监控：应只出现 1 次 `SELECT ... FROM work WHERE slug=?`，随后键被重新写入。
3. 后台作品「发布/下线/改 slug」后立即刷新前台，确认列表与详情都变化（失效生效）。
4. 评论：提交并被审核通过后刷新评论区应立即出现（`cache:comment:list:*` 已清）。

