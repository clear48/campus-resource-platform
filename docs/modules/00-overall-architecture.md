# 校园资源平台 — 总体架构与全局流程图

> 本文档基于 `docs/modules/` 目录下九份模块开发流程文档中的流程图整合而成，反映当前已落地的系统全貌。
> 生成日期：2026-08-08

---

## 1. 系统概述

校园资源平台是一个面向高校学生的课程资料共享平台，核心业务流程为：

**用户注册/登录 → 上传文件 → 创建资料 → 管理员审核 → 公开搜索 → 下载/收藏 → 排行榜展示**

系统共分为 9 个业务模块，模块间通过明确的依赖关系形成一条从"生产"到"消费"的完整链路。

---

## 2. 模块全景图

```mermaid
graph TB
    subgraph 基础层["基础层"]
        AUTH["01-认证模块<br/>注册/登录/JWT/黑名单"]
        CATEGORY["02-分类模块<br/>分类列表查询"]
    end

    subgraph 生产层["生产层"]
        FILE["03-文件上传模块<br/>MD5去重/秒传/落盘"]
        RESOURCE["04-资料模块<br/>创建资料/详情/缓存"]
    end

    subgraph 治理层["治理层"]
        AUDIT["05-审核模块<br/>通过/拒绝/下架/记录"]
    end

    subgraph 消费层["消费层"]
        SEARCH["06-搜索模块<br/>公开搜索/热词统计"]
        DOWNLOAD["07-下载模块<br/>限流/去重/文件流"]
        FAVORITE["08-收藏模块<br/>收藏/取消/状态缓存"]
    end

    subgraph 聚合层["聚合层"]
        RANK["09-排行榜模块<br/>热门榜/定时同步/快照"]
    end

    AUTH --> FILE
    AUTH --> RESOURCE
    AUTH --> AUDIT
    AUTH --> DOWNLOAD
    AUTH --> FAVORITE
    CATEGORY --> RESOURCE
    CATEGORY --> SEARCH
    FILE --> RESOURCE
    RESOURCE --> AUDIT
    AUDIT --> SEARCH
    AUDIT --> DOWNLOAD
    AUDIT --> FAVORITE
    SEARCH --> DOWNLOAD
    SEARCH --> FAVORITE
    RESOURCE --> DOWNLOAD
    RESOURCE --> FAVORITE
    DOWNLOAD --> RANK
    FAVORITE --> RANK
    SEARCH --> RANK
    AUDIT --> RANK
```

---

## 3. 全局业务流程图

### 3.1 总流程（用户视角 + 管理员视角）

```mermaid
flowchart TD
    subgraph 用户侧["👤 用户侧"]
        U1["注册/登录"] --> U2["浏览分类"]
        U2 --> U3["上传文件"]
        U3 --> U4["创建资料<br/>选择分类、填写信息"]
        U4 --> U5["等待审核"]
        U5 -->|"审核通过"| U6["资料公开可见"]
        U5 -->|"审核拒绝"| U7["查看拒绝原因<br/>可修改后重新提交"]
    end

    subgraph 管理员侧["🔧 管理员侧"]
        A1["查看待审核列表"] --> A2{"审核决定"}
        A2 -->|"通过"| A3["资料状态 → APPROVED<br/>初始化排行榜成员"]
        A2 -->|"拒绝"| A4["资料状态 → REJECTED<br/>记录拒绝原因"]
        A3 --> A5["可下架已通过资料<br/>状态 → OFFLINE"]
    end

    subgraph 公开消费["🌐 公开消费"]
        C1["搜索资料<br/>仅 APPROVED"] --> C2["查看资料详情"]
        C2 --> C3{"操作选择"}
        C3 -->|"下载"| C4["限流校验 → 获取凭证<br/>→ 下载文件流"]
        C3 -->|"收藏"| C5["收藏/取消收藏<br/>幂等防重复"]
        C1 --> C6["热门搜索词榜"]
        C7["热门资料排行榜<br/>日/周/月/总榜"]
    end

    U6 --> C1
    A3 --> C1
    A5 -->|"下架资料从<br/>搜索/榜单移除"| C1
    C4 --> C7
    C5 --> C7
    C1 --> C6
```

### 3.2 数据流转全貌

```mermaid
flowchart LR
    subgraph 写入路径["📥 数据写入路径"]
        direction TB
        W1["用户上传文件"] --> W2["file_info 表<br/>+ 物理文件落盘"]
        W2 --> W3["创建 resource 记录<br/>status = PENDING_REVIEW"]
        W3 --> W4["管理员审核"]
        W4 -->|"通过"| W5["resource.status = APPROVED"]
        W4 -->|"拒绝"| W6["resource.status = REJECTED"]
        W5 --> W7["下载/收藏产生行为数据"]
    end

    subgraph Redis层["⚡ Redis 缓存与统计层"]
        direction TB
        R1["JWT 黑名单<br/>crp:auth:token:blacklist:*"]
        R2["MD5 秒传缓存<br/>crp:cache:file:md5:*"]
        R3["资料详情缓存<br/>crp:cache:resource:detail:*"]
        R4["搜索热词 ZSet<br/>crp:rank:search:keyword:*"]
        R5["下载限流 ZSet<br/>crp:rate:download:*"]
        R6["下载增量 Hash<br/>crp:stats:resource:download:delta"]
        R7["收藏 Set<br/>crp:user:favorites:*"]
        R8["热门资料 ZSet<br/>crp:rank:resource:hot:*"]
    end

    subgraph 读取路径["📤 数据读取路径"]
        direction TB
        RD1["公开搜索"] --> RD2["资料详情"]
        RD2 --> RD3["下载文件流"]
        RD2 --> RD4["收藏操作"]
        RD5["热门资料榜"] --> RD6["热门搜索词榜"]
    end

    W7 --> R5
    W7 --> R6
    W7 --> R7
    R6 -->|"定时同步"| W7
    R8 --> RD5
    R4 --> RD6
    R3 --> RD2
    R2 --> W2
```

---

## 4. 模块间调用关系矩阵

| | 01认证 | 02分类 | 03文件 | 04资料 | 05审核 | 06搜索 | 07下载 | 08收藏 | 09排行 |
|---|---|---|---|---|---|---|---|---|---|
| **01认证** | — | | | | | | | | |
| **02分类** | | — | | | | | | | |
| **03文件** | ✅ 需登录 | | — | | | | | | |
| **04资料** | ✅ uploaderId | ✅ categoryId | ✅ fileId | — | | | | | |
| **05审核** | ✅ 管理员 | | | ✅ 消费待审 | — | | | | |
| **06搜索** | | ✅ categoryId | | ✅ 读APPROVED | ✅ 隔离未审 | — | | | |
| **07下载** | ✅ 限流/IP | | ✅ 读存储路径 | ✅ 读APPROVED | ✅ 隔离未审 | | — | | |
| **08收藏** | ✅ userId | | | ✅ 读APPROVED | ✅ 隔离未审 | | | — | |
| **09排行** | | | | ✅ 读APPROVED | ✅ 初始/移除 | ✅ 热词ZSet | ✅ 热度联动 | ✅ 热度联动 | — |

> ✅ = 模块对该模块有依赖关系，箭头方向为"依赖于"

---

## 5. 分层架构图

```mermaid
flowchart TD
    subgraph L1["🎯 接口层 — Controller（12 个）"]
        direction LR
        C_AUTH["AuthController<br/>POST /api/v1/auth/register<br/>POST /api/v1/auth/login<br/>POST /api/v1/auth/logout"]
        C_USER["UserController<br/>GET /api/v1/users/me"]
        C_CAT["CategoryController<br/>GET /api/v1/categories<br/>?parentId=0"]
        C_HEALTH["HealthController<br/>GET /api/v1/health"]
        C_FILE["FileController<br/>POST /api/v1/files<br/>GET /api/v1/files/check"]
        C_RES["ResourceController<br/>POST /api/v1/resources<br/>GET /api/v1/resources/{id}<br/>GET /api/v1/users/me/resources"]
        C_AUDIT["AuditController<br/>GET /api/v1/admin/resources/pending-reviews<br/>POST .../audit-approvals<br/>POST .../audit-rejections<br/>POST .../offline-records<br/>GET .../audit-records<br/>GET .../review-file"]
        C_SCH["SearchController<br/>GET /api/v1/search/resources<br/>?keyword=&categoryId=&sortBy="]
        C_DL["DownloadController<br/>POST /api/v1/resources/{id}/download-records<br/>GET /api/v1/download-records/{id}/file<br/>GET /api/v1/users/me/download-records"]
        C_FAV["FavoriteController<br/>POST /api/v1/resources/{id}/favorites<br/>DELETE /api/v1/resources/{id}/favorites<br/>GET .../favorite-status<br/>GET /api/v1/users/me/favorites"]
        C_RANK["RankingController<br/>GET /api/v1/rankings/resources/hot<br/>GET /api/v1/rankings/search-keywords/hot<br/>AdminRankingController<br/>POST /api/v1/admin/rankings/resources/hot/rebuild"]
    end

    subgraph L2["🔐 拦截器层"]
        I_JWT["JwtAuthenticationInterceptor<br/>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━<br/>① 读取 Authorization: Bearer {token}<br/>② JwtUtils.parseToken 解析 JWT → userId / role / jti<br/>③ Redis 黑名单校验 crp:auth:token:blacklist:{jti}<br/>④ 构造 LoginUser → UserContextHolder.set()<br/>⑤ afterCompletion → UserContextHolder.clear() 清理 ThreadLocal"]
    end

    subgraph L3["⚙️ 业务层 — Service（9 个核心 + 8 个基础设施）"]
        direction LR
        subgraph L3A["核心业务 Service"]
            direction TB
            S_AUTH["AuthServiceImpl<br/>register() · login() · logout()<br/>→ UserMapper + PasswordService<br/>→ JwtUtils + Redis 黑名单"]
            S_CAT["CategoryServiceImpl<br/>listEnabledCategoriesByParentId()<br/>→ CategoryMapper"]
            S_FILE["FileServiceImpl<br/>upload() · checkMd5()<br/>→ FileStorageService · FileInfoMapper<br/>→ FileMd5CacheService · 秒传去重"]
            S_RES["ResourceServiceImpl<br/>create() · getPublicDetail()<br/>· listMyResources()<br/>→ ResourceMapper · FileInfoMapper<br/>→ CategoryMapper · ResourceDetailCacheService"]
            S_AUDIT["AuditServiceImpl<br/>approve() · reject() · offline()<br/>· listPendingReviews() · reviewFile()<br/>→ ResourceMapper · AuditRecordMapper<br/>→ RankingService 联动"]
            S_SCH["SearchServiceImpl<br/>searchResources()<br/>→ ResourceMapper (status=1 固定过滤)<br/>→ Redis ZSet 热词统计 ZINCRBY"]
            S_DL["DownloadServiceImpl<br/>createDownloadRecord()<br/>· loadFile() · listMyDownloads()<br/>→ DownloadRateLimiter · ResourceMapper<br/>→ FileInfoMapper · FileStorageService<br/>→ Redis 去重 · 下载增量 HINCRBY"]
            S_FAV["FavoriteServiceImpl<br/>favorite() · unfavorite()<br/>· getFavoriteStatus() · listMyFavorites()<br/>→ FavoriteMapper · ResourceMapper<br/>→ Redis Set SADD/SREM/SISMEMBER"]
            S_RANK["RankingServiceImpl<br/>listHotResources() · listHotKeywords()<br/>· increaseForDownload/Favorite()<br/>→ ResourceMapper · Redis ZSet<br/>→ MySQL hot_score 降级兜底"]
        end
        subgraph L3B["基础设施 Service"]
            direction TB
            IN_PWD["PasswordService<br/>BCryptPasswordEncoder<br/>encode() · matches()"]
            IN_JWT["JwtUtils<br/>generateToken() · parseToken()<br/>getUserId() · getRole() · getJti()<br/>getRemainingSeconds()"]
            IN_FS["FileStorageService<br/>calculateMd5() · store()<br/>loadAsResource() · delete()<br/>UUID 命名 · 路径穿越防护"]
            IN_MD5["FileMd5CacheService<br/>FOUND / NOT_FOUND / ABSENT<br/>正缓存 6h SET · 负缓存 5min SET NX<br/>坏值清理 · fail-open 降级"]
            IN_DETAIL["ResourceDetailCacheService<br/>Cache Aside · JSON 快照<br/>Redisson 每资料锁 回源<br/>审核后 500ms/2s/5s 删除重试"]
            IN_LIMIT["DownloadRateLimiter<br/>ZSet 滑动窗口 · Lua 原子<br/>用户 10次/分 · IP 30次/分<br/>fail-close 拒绝下载"]
            IN_SYNC["DownloadDeltaSyncServiceImpl<br/>UUID 批次隔离 · Redisson RLock<br/>→ DownloadDeltaPersistenceService<br/>MySQL 原子累加 · 幂等明细 HDEL"]
            IN_HOT["HotRankingMaintenanceServiceImpl<br/>all 榜缺失重建 · RENAME 原子替换<br/>热度快照 → hot_score 回写<br/>Redisson RReadWriteLock"]
        end
    end

    subgraph L4["💾 持久层 — Mapper（8 个）"]
        direction LR
        M_USER["UserMapper<br/>user 表<br/>selectByUsername · selectById<br/>insert · updateLastLoginAt"]
        M_CAT["CategoryMapper<br/>category 表<br/>selectEnabledByParentId<br/>selectEnabledById"]
        M_FILE["FileInfoMapper<br/>file_info 表<br/>selectByMd5AndSize · insert<br/>increaseRefCount · selectNormalById"]
        M_RES["ResourceMapper<br/>resource 表<br/>insert · selectPublicDetailById<br/>searchApprovedResources<br/>countApprovedResources<br/>updateFavoriteCount · incrementDownloadCount<br/>updateApprovedHotScore<br/>selectApprovedRankingCandidatesByIds"]
        M_AUDIT["AuditRecordMapper<br/>audit_record 表<br/>insert · selectByResourceId"]
        M_DL["DownloadRecordMapper<br/>download_record 表<br/>insert · selectById<br/>selectByUser · countByUser"]
        M_FAV["FavoriteMapper<br/>favorite 表<br/>insert · selectByUserAndResource<br/>updateStatus · selectByUser · countByUser<br/>selectActiveResourceIdsByUser"]
        M_DELTA["DownloadDeltaSyncItemMapper<br/>download_delta_sync_item 表<br/>insert · selectByBatchAndResource<br/>updateConfirmedAt"]
    end

    subgraph L5["🗄️ 数据源"]
        direction LR
        DS1[("🐬 MySQL<br/>━━━━━━━━━━<br/>user · category · file_info<br/>resource · audit_record<br/>download_record · favorite<br/>download_delta_sync_item")]
        DS2[("⚡ Redis<br/>━━━━━━━━━━<br/>String: JWT黑名单 · MD5缓存 · 详情缓存 · 去重<br/>ZSet:  搜索热词 · 下载限流 · 热门资料榜 · all重建<br/>Hash:  下载增量 · syncing批次<br/>Set:   用户收藏集合")]
        DS3[("📁 本地文件系统<br/>━━━━━━━━━━<br/>data/uploads/<br/>UUID.ext 命名<br/>storage_type = 1")]
    end

    %% ==================== 层间主调用链 ====================
    L1 ==> L2 ==> L3 ==> L4 ==> L5

    %% ==================== 基础设施直连数据源 ====================
    IN_JWT -.->|"Token 黑名单"| DS2
    IN_MD5 -.->|"MD5 → fileId 三态缓存"| DS2
    IN_DETAIL -.->|"公开详情 JSON 快照"| DS2
    IN_LIMIT -.->|"滑动窗口 Lua 原子"| DS2
    IN_SYNC -.->|"下载增量批次隔离"| DS2
    S_SCH -.->|"热门搜索词 ZINCRBY"| DS2
    S_RANK -.->|"热门资料 ZSet 查询/更新"| DS2
    S_FAV -.->|"收藏 Set SADD/SREM"| DS2
    IN_FS -.->|"物理文件读写"| DS3
```

---

## 6. 核心业务流程整合流程图

> 下图将九个模块的流程串联为一条完整的端到端链路。

```mermaid
flowchart TD
    START["👤 用户开始"] --> REG["① 注册/登录<br/>(AuthController)"]
    REG --> LOGIN{"JWT 签发成功?"}
    LOGIN -- "否" --> REG
    LOGIN -- "是" --> CTG["② 选择分类<br/>(CategoryController)<br/>公开接口，无需登录"]
    CTG --> UPLOAD["③ 上传文件<br/>(FileController)"]

    UPLOAD --> MD5_CHECK{"④ MD5预检<br/>Redis三态缓存"}
    MD5_CHECK -- "可秒传" --> SEC_UPLOAD["秒传：ref_count+1<br/>写user_file_authorization"]
    MD5_CHECK -- "需上传" --> REAL_UPLOAD["真实上传：计算MD5<br/>→ 落盘 → insert file_info<br/>→ 回填缓存"]

    SEC_UPLOAD --> CREATE_RES["⑤ 创建资料<br/>(ResourceController)"]
    REAL_UPLOAD --> CREATE_RES

    CREATE_RES --> RES_VALID{"校验 fileId<br/>+ categoryId + 重复提交"}
    RES_VALID -- "不通过" --> ERR1["返回对应错误码"]
    RES_VALID -- "通过" --> INSERT_RES["INSERT resource<br/>status = PENDING_REVIEW"]

    INSERT_RES --> AUDIT_QUEUE["⑥ 进入待审核队列<br/>(AuditController)"]

    AUDIT_QUEUE --> ADMIN{"🔧 管理员审核"}
    ADMIN -- "通过" --> APPROVE["状态 → APPROVED<br/>写 audit_record<br/>初始化排行榜成员<br/>失效详情缓存"]
    ADMIN -- "拒绝" --> REJECT["状态 → REJECTED<br/>写 reject_reason<br/>写 audit_record<br/>失效详情缓存"]

    APPROVE --> PUBLIC["⑦ 资料进入公开域"]

    PUBLIC --> SEARCH_PUB["⑧ 公开搜索<br/>(SearchController)<br/>仅查APPROVED<br/>写入搜索热词ZSet"]
    PUBLIC --> DETAIL["⑧ 公开详情<br/>(ResourceController)<br/>Cache Aside<br/>首次miss走锁回源"]

    SEARCH_PUB --> USER_ACTION{"用户操作"}
    DETAIL --> USER_ACTION

    USER_ACTION -- "下载" --> DL["⑨ 下载模块<br/>限流→去重→写记录<br/>→HINCRBY增量<br/>→四周期热度+5<br/>→返回文件流"]
    USER_ACTION -- "收藏" --> FAV["⑨ 收藏模块<br/>校验→幂等写入<br/>→favorite_count±1<br/>→Redis Set同步<br/>→四周期热度±3"]

    DL --> RANK_INPUT["⑩ 行为数据进入排行榜"]
    FAV --> RANK_INPUT
    SEARCH_PUB --> RANK_INPUT

    RANK_INPUT --> RANK_ZSET["Redis ZSet 实时排序<br/>crp:rank:resource:hot:{period}"]
    RANK_INPUT --> RANK_DELTA["下载增量 Hash<br/>crp:stats:resource:download:delta"]

    RANK_DELTA --> SYNC_TASK["定时任务(60s)<br/>Redisson锁→批次隔离<br/>→MySQL原子累加<br/>→幂等明细→HDEL确认"]
    RANK_ZSET --> SNAPSHOT["定时快照任务<br/>all榜分数→resource.hot_score"]

    RANK_ZSET --> RANK_API["⑩ 热门资料榜API<br/>ZSet候选→MySQL过滤<br/>→排序补足→返回"]
    SEARCH_PUB --> KEYWORD_API["⑩ 热门搜索词API<br/>ZSet直接读取"]

    ADMIN -- "下架" --> OFFLINE["状态 → OFFLINE<br/>ZREM四周期<br/>失效详情缓存"]
```

---

## 7. 状态机总览

```mermaid
stateDiagram-v2
    [*] --> PENDING_REVIEW: 用户创建资料
    PENDING_REVIEW --> APPROVED: 管理员审核通过
    PENDING_REVIEW --> REJECTED: 管理员审核拒绝
    APPROVED --> OFFLINE: 管理员下架

    note right of PENDING_REVIEW: 不可搜索/下载/收藏
    note right of APPROVED: 可搜索/下载/收藏/上榜
    note right of REJECTED: 不可公开，上传者可见
    note right of OFFLINE: 不可搜索/下载/收藏，从榜单移除
```

**资料状态流转规则（`resource.status`）：**

| 状态 | 值 | 可搜索 | 可下载 | 可收藏 | 可上榜 |
|------|-----|--------|--------|--------|--------|
| PENDING_REVIEW | 0 | ❌ | ❌ | ❌ | ❌ |
| APPROVED | 1 | ✅ | ✅ | ✅ | ✅ |
| REJECTED | 2 | ❌ | ❌ | ❌ | ❌ |
| OFFLINE | 3 | ❌ | ❌ | ❌ | ❌ |
| DELETED | 4 | ❌ | ❌ | ❌ | ❌ |

---

## 8. Redis Key 全景图

| Key 模式 | 类型 | 模块 | 用途 | TTL |
|----------|------|------|------|-----|
| `crp:auth:token:blacklist:{jti}` | String | 认证 | JWT退出登录黑名单 | JWT剩余有效期 |
| `crp:cache:file:md5:{md5}:{size}` | String | 文件 | MD5秒传三态缓存 | 正6h / 负5min |
| `crp:cache:resource:detail:{id}` | String | 资料 | 公开详情JSON快照 | 30min+随机 |
| `crp:lock:cache:resource:detail:{id}` | RLock | 资料 | 详情缓存回源/失效锁 | 看门狗 |
| `crp:rank:search:keyword:daily` | ZSet | 搜索 | 日热门搜索词 | 2天 |
| `crp:rank:search:keyword:weekly` | ZSet | 搜索 | 周热门搜索词 | 14天 |
| `crp:rank:search:keyword:monthly` | ZSet | 搜索 | 月热门搜索词 | 60天 |
| `crp:rate:download:user:{userId}` | ZSet | 下载 | 用户下载限流 | 窗口+60s |
| `crp:rate:download:ip:{ip}` | ZSet | 下载 | IP下载限流 | 窗口+60s |
| `crp:dedup:download:{userId}:{rid}` | String | 下载 | 重复下载去重 | 10-30min |
| `crp:download:ticket:{userId}:{downloadRecordId}:{ticketDigest}` | String | 下载 | 一次性下载凭证 | 5min |
| `crp:stats:resource:download:delta` | Hash | 下载 | 下载量增量 | 不设TTL |
| `crp:stats:resource:download:syncing:{batchId}` | Hash | 排行 | 下载增量同步批次详情 | 按批次清理 |
| `crp:stats:resource:download:syncing:current` | String | 排行 | 当前同步批次ID | 不设TTL |
| `crp:user:favorites:{userId}` | Set | 收藏 | 用户收藏集合缓存 | 30min |
| `crp:rank:resource:hot:daily` | ZSet | 排行 | 日热门资料榜 | 2天 |
| `crp:rank:resource:hot:weekly` | ZSet | 排行 | 周热门资料榜 | 14天 |
| `crp:rank:resource:hot:monthly` | ZSet | 排行 | 月热门资料榜 | 60天 |
| `crp:rank:resource:hot:all` | ZSet | 排行 | 总热门资料榜 | 不设TTL |
| `crp:rank:resource:hot:all:rebuild:{batchId}` | ZSet | 排行 | all榜重建批次（RENAME原子替换） | 重建后清理 |
| `crp:lock:sync:download-delta` | RLock | 排行 | 下载增量同步锁 | 看门狗 |
| `crp:lock:sync:hot-rank-maintenance` | RReadWriteLock | 排行 | 总榜维护读写锁 | 看门狗 |

---

## 9. 模块热度权重

| 行为 | 分数变化 | 作用周期 | 触发模块 |
|------|----------|----------|----------|
| 审核通过 | `ZADD 0` | daily/weekly/monthly/all | 审核模块 |
| 有效下载 | `ZINCRBY +5` | daily/weekly/monthly/all | 下载模块 |
| 收藏成功 | `ZINCRBY +3` | daily/weekly/monthly/all | 收藏模块 |
| 取消收藏 | `ZINCRBY -3` | daily/weekly/monthly/all | 收藏模块 |
| 下架/删除 | `ZREM` | daily/weekly/monthly/all | 审核模块 |

---

## 10. 定时任务一览

| 任务 | 频率 | 锁 | 职责 |
|------|------|-----|------|
| 下载增量同步 | 每60秒 | `crp:lock:sync:download-delta` (RLock) | UUID批次隔离 → MySQL原子累加 → HDEL确认 |
| All榜缺失检查 | 每5分钟 | `crp:lock:sync:hot-rank-maintenance` (写锁) | 检查all Key → 从MySQL重建 → RENAME原子替换 |
| 热度快照 | 每5分钟 | `crp:lock:sync:hot-rank-maintenance` (读锁) | 分批读all ZSet → 回写resource.hot_score |

---

## 11. 关键技术决策说明

### 11.1 物理文件与业务资料解耦

`file_info`（物理文件）与 `resource`（业务资料）分离为两张表，实现：

- 同一文件可被多份资料复用（通过 `ref_count` 引用计数和 `user_file_authorization` 授权）
- MD5 + file_size 唯一索引实现秒传
- 文件存储细节（`storage_path`、`stored_name`）不暴露给业务层

### 11.2 先写 Redis 后同步 MySQL

下载量是高频写场景，采用：

1. 下载成功 → `HINCRBY crp:stats:resource:download:delta +1`
2. 定时任务 → UUID批次原子隔离 → MySQL原子累加 → 幂等明细确认
3. 避免每次下载都 `UPDATE resource SET download_count = download_count + 1`

### 11.3 状态机保证内容安全

所有公开消费入口（搜索、下载、收藏、排行榜）都在 Service/Mapper 层固定过滤 `status = 1 (APPROVED)`，形成多层防御：

- 搜索 Mapper SQL 固定 `WHERE status = 1`
- 下载 Service 校验 `resource.isApproved()`
- 收藏 Service 校验 `resource.isApproved()`
- 排行榜 Mapper SQL 固定 `WHERE status = 1`

### 11.4 Redis 故障降级策略

| 场景 | 降级方式 |
|------|----------|
| 热门资料 Redis 不可用 | 降级 MySQL `hot_score` 查询 |
| 热门搜索词 Redis 不可用 | 返回空列表 |
| 详情缓存 Redis 不可用 | 直接查 MySQL 返回 |
| 下载限流 Redis 不可用 | 失败关闭（拒绝下载） |
| 收藏 Set Redis 不可用 | 降级查 MySQL |
| 热词统计 Redis 不可用 | 记录日志，搜索正常返回 |
| 热度联动 Redis 不可用 | 记录日志，主业务不回滚 |

---

## 12. 项目技术栈总览

| 层次 | 技术 |
|------|------|
| 框架 | Spring Boot 3.x |
| 持久层 | MyBatis + MySQL 8.x |
| 缓存 | Redis (StringRedisTemplate + Redisson) |
| 认证 | JWT + BCrypt + Redis 黑名单 |
| 定时任务 | Spring `@Scheduled` |
| 分布式锁 | Redisson RLock / RReadWriteLock |
| 文件存储 | 本地文件系统 (`data/uploads/`) |
| 测试 | JUnit 5 + MockMvc + H2 MySQL Mode |
| API 文档 | Markdown (Postman Collection) |

---

## 13. 模块依赖链（开发顺序）

```text
01-认证 ──→ 02-分类 ──→ 03-文件上传 ──→ 04-资料 ──→ 05-审核
                                                    │
                    ┌───────────────────────────────┤
                    ▼                               ▼
              06-搜索                          07-下载
                    │                               │
                    └───────────┬───────────────────┘
                                ▼
                          08-收藏
                                │
                                ▼
                          09-排行榜
```

实际开发顺序即按上述编号 01 → 09 推进，每个模块在前置模块完成后才可开发。

---

## 14. 附录：各模块文档索引

| 编号 | 模块 | 文档 |
|------|------|------|
| 01 | 用户认证 | [01-auth-development-process.md](01-auth-development-process.md) |
| 02 | 分类查询 | [02-category-development-process.md](02-category-development-process.md) |
| 03 | 文件上传 | [03-file-upload-development-process.md](03-file-upload-development-process.md) |
| 04 | 资料管理 | [04-resource-development-process.md](04-resource-development-process.md) |
| 05 | 审核管理 | [05-audit-development-process.md](05-audit-development-process.md) |
| 06 | 搜索服务 | [06-search-development-process.md](06-search-development-process.md) |
| 07 | 下载服务 | [07-download-development-process.md](07-download-development-process.md) |
| 08 | 收藏服务 | [08-favorite-development-process.md](08-favorite-development-process.md) |
| 09 | 排行榜与定时任务 | [09-rank-development-process.md](09-rank-development-process.md) |
