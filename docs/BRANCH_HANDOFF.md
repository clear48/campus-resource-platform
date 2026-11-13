# 分支交接记录

## 2026-11-13 用户许可后恢复并发测试

- 当前分支codex/local-concurrency-peak。恢复同一专用数据库，所有本轮测量记录在独立resume-20261113证据目录，Token已刷新、周期榜与详情已预热。应用源码基线不变，打包未重跑旧264测试，不将历史验证冒充本轮。
- 主混合场景已确认700/750区间：700三次340秒计划PASS，750一次正式FAIL（资料榜P95 205ms）；各轮310秒预算、零请求错误、目标VU时间覆盖≥99.98%。700 RPS中位4157.884。该模型为50/35/10/5、think0，不承诺全业务/长期容量。
- 独立接口测量继续串行进行，唯一执行者perf_harness，root维护文档。实时私有状态由peak-active-run.txt指向恢复目录，不得启动第二个JMeter并行负载。

## 2026-11-11 并发边界测试暂停交接

- 分支`codex/local-concurrency-peak`，工具提交`8de17c3d`、`4b7ecb40`已推送；应用源码仍为main基线3b6b699f，main/dev未修改。
- 后端264/264及打包、工具显式Groovy 61/61通过。修复原生线程生命周期与线程组计时窗口校验，新增自适应边界搜索。
- 100/150混合诊断通过，200搜索P95 517ms而SLO不合格，未做正式边界确认；不是已测出峰值。用户要求200完成后暂停，现已停队列、175中断档、发压及监控，独立服务保留待命。
- 明确等待用户许可后才能恢复测试；私有运行状态在仓库外C:/campusshare-loadtest/peak-run-state.json，证据和有效诊断均保留，不复用旧凭据到报告或Git。
- 阶段报告与JSON：`docs/test/2026-11-11-local-concurrency-peak-report.md`、`docs/test/2026-11-11-local-concurrency-peak-summary.json`。本次仅保存暂停检查点，不再执行压测或新增业务变更。

## 2026-11-07 同步 dev、排行榜修复与 main

- 来源：`dev@f4f7fa0b`、排行榜 `afcac95e` 和原 main `db6601e9`；在临时集成分支合并验证，Redis 常量中的文件保护锁和自然周期 Key helper 均保留。仅本文件与状态文档有内容冲突，已保留两侧历史章节。
- 用户明确要求 dev 合并后保留：最终保留本地与远程 `main`、`dev`，两者同步到相同结果；排行榜分支及本轮临时集成分支在确认来源提交均已包含后删除。不再套用旧“只保留 main”的清理规则。
- 验证命令与结果：`mvnw.cmd clean test` 264/264（0 失败/错误/跳过），`mvnw.cmd -DskipTests package` 通过；使用本轮专用隔离 MySQL，不访问日常 3306 数据库。42 个独立变更源码/测试文件与来源 blob 一致，合并时无生产代码重写。
- 保护功能保持关闭，本轮未执行数据库迁移、真实 Redis/磁盘误删恢复演练或生产部署。启用前需先完成迁移和目录身份/恢复验收；活动目录与副本目录必须互不嵌套，即使开关关闭构造器仍会检查。
- 以下章节是集成前的历史记录；排行榜新桶从运行版本切换后的事件开始累计、旧无日期分数不迁移等升级要求仍有效。真实 `.env`、简历和测试运行资料不纳入 Git。

## 2026-11-02 在线单文件保护交接

- 当前分支：`dev`，已推送至 `origin/dev`。提交依次为：`e6b4e5f`（数据库与配置骨架）、`587ce2e`（SHA-256 在线副本）、`fb898af`（自动巡检和在线恢复）、`9a698f2`（业务可用性门禁）、`333a10f`（审核数据库切片测试接线）。
- 功能默认关闭。接手者先执行 `sql/migrations/20261102_file_online_protection.sql`，创建互不嵌套的活动/副本目录和 `.crp-storage-id`、`.crp-backup-id`，配置匹配 ID 后完成真实误删演练，才能启用 `FILE_PROTECTION_ENABLED=true`。
- 自动恢复严格限定为单文件：默认同轮两个文件缺失即熔断；单文件需连续两轮确认，并在 Redisson 单文件锁内二次查库。副本未 READY、摘要不一致、根目录身份错误或目标出现异常文件时均不覆盖。
- 受影响入口在恢复窗口返回 HTTP 503/`50301`，其他文件与后端服务继续正常运行。该机制不替代 `docs/LOCAL-DATA-PROTECTION.md` 的停机联合灾备。
- 当前未完成真实磁盘演练、生产启用、事件管理接口或告警通知；不能把单元/H2 测试描述为真实 MySQL、Redis 与磁盘故障验证。
- 用户原有未提交修改 `docs/DEPLOYMENT-QA-QUICK-REVIEW.md` 始终保留，未纳入本任务提交。

## 2026-11-07 自然日/周/月排行榜

- 开发分支：`codex/natural-period-ranking`，基线为 `main@db6601e9`；保留已有 `dev`，不合并其在线文件保护实现，本轮不部署服务器。
- 已实现上海自然日、周一开始的自然周、自然月分桶；资料与热词全部读写同一周期模型，显式 Clock 支持确定性测试，每次查询/行为固定一个时点。
- 固定到期为周期结束加 2/14/60 天，活跃写入不会延长旧桶寿命；all 继续原 Key/锁/快照。周期故障返回空，不使用总榜快照。取消收藏按发生当期扣分可负，下架清当前桶及 all。
- 上线要求：所有实例统一切换；新桶从切换后事件开始累计，旧无日期 Key 的历史总分不能迁移或用来回填完整周期。停止旧写后由原 TTL 回收；无 TTL 的遗留 Key 需核实后定向清理，应用不扫描删除。
- 验证：专项 7 类 `mvnw.cmd "-Dtest=RankingPeriodTest,RankingServiceImplTest,SearchServiceImplTest,RankingMapperIntegrationTest,SearchServiceDatabaseIntegrationTest,AuditServiceDatabaseIntegrationTest,HotRankingMaintenanceServiceImplTest" clean test` 49/49，全量 `mvnw.cmd test` 247/247，0 失败/错误/跳过。使用独立临时 MySQL；首次旧 target Mapper 污染已 clean 后纠正。日志保留于忽略目录 `tmp/`。
- 本次未新增依赖、数据库表、公开接口或前端代码；真实 Redis 原子性、历史周期重建和生产部署未验证，`ZINCRBY`/`EXPIREAT` 联合原子性仍属独立改进项 IMP-025。
- 文档覆盖 Redis/API/模块/架构/面试/需求/压测与 IMP-009，归档状态维持关闭。下一步可独立治理热词基数或原子计分，本轮不自动扩展。
- 实际实现提交 `c82cd225`，已推送 `origin/codex/natural-period-ranking`，未合并 main。104 个本地文档链接与差异检查通过；隔离 MySQL 已关闭，临时目录删除遭自动审批 `blocked by policy` 拒绝而保留，本机路径见忽略文件 `tmp/natural-rank-mysql.json`。

## 2026-11-02 全部分支汇入 main 与网站停止维护

- 集成目标：`main`。先快进 `dev@fdd82716`，再合并分叉的 `codex/auth-auto-refresh@ed79a203`；仅 `CURRENT_STATUS.md` 和本文件存在内容冲突，保留本地备份、压测与认证双方记录。
- README 明确“现已停止维护网站”，保留部署资产供复盘与自行部署；同步认证指南已合入主干的状态，本轮不部署或变更生产配置。
- 用户已有 `docs/DEPLOYMENT-QA-QUICK-REVIEW.md` 修改留在原工作区，不提交、不覆盖；现有认证工作树保留。
- 本轮新写入仅为合并冲突处理及网站/交接状态，业务实现采用已提交分支内容，不新增依赖、表结构或 Redis 协议。
- 验证：隔离 MySQL 下 `mvnw.cmd test` 232/232、认证专项 26/26、打包通过；`npm run test:unit -- --reporter=dot` 101/101 与 `npm run build` 通过；本地备份 8/8、恢复 14/14，`python -B -m unittest discover -s tests/performance -p 'test_*.py' -v` 共 42 项（41 通过、1 项显式 JMeter 前置未配置而跳过）。文档 74 个链接/锚点及 `git diff --check` 通过。
- 保留 16 项既有 npm 审计提示（7 moderate、9 high）和主包大小提示；无依赖升级或生产操作。提交说明：`merge: integrate branches and mark website unmaintained`，实际提交和推送结果见最终回执。

## 2026-10-28 本地压测执行完成

- 分支`dev`；本轮工具提交4c82e98e、8ab58a72、166c05e3、a11bead9、5289ddba、05f15fa7均已推送。报告提交说明：`docs(perf): record completed local load tests and findings`。
- 查询18档及选定100线程矩阵、混合短轮和专项完成；用户取消连续稳定性，不计为通过。收藏100并发9个40901需修复，登录100/50延迟未达标、20线程短轮达标。
- 下载40次应计增量逐资料落库一致，最终1003资料/1225文件，重复、计数差异、物理残留均0。本轮专用18080/13306/16379已关闭，原始证据和数据保留，未修改Java业务或日常数据。
- 验证：后端208/208及打包；工具最终启用真实Groovy并发回归后42/42；V2分类100复验。报告与脱敏JSON见`docs/test/2026-10-28-local-loadtest-report.md`、`docs/test/2026-10-28-local-loadtest-summary.json`。
- 下一步为独立修复收藏并发幂等并回归；登录先采连接持有/等待及CPU栈，不将扩大连接池视为已验证方案。用户已有`docs/DEPLOYMENT-QA-QUICK-REVIEW.md`修改保留且不提交。

## 2026-10-28 压测工具与环境准备

- 沿用当前开发分支 `dev`，仅新增 `tests/performance/` 与压测相关文档；不改 Java 业务、依赖、表结构或 Redis 协议。用户已有部署问答修改继续保留。
- 工具包含本机/专用库约束、SQL/API 随机分类绑定、证据路径隔离、JMX 原生 HTTP Sampler 和 HTTP/业务双断言；0 样本或断言失败不能因为 JMeter 退出码为 0 被算通过。
- 验证：后端 208/208 及打包通过，工具单测 12/12，缺 manifest 负向 6 个请求全失败，JDK21 下搜索冒烟 2254/2254。实际压测尚在继续，后续单独提交报告与最终状态，不能将本阶段当作全部压测完成。
- 提交说明：`test(perf): add isolated local load testing tools`。真实凭据、Token、清单、JTL、合成源文件和数据库都留在仓库外。
- 后续运行器加固提交说明：`fix(perf): bind runtime targets and share throughput windows`。新增发压入口清单身份/实时 SQL/API 绑定门禁，并统一混合标签吞吐分母；14/14 隔离/统计测试通过。当前在跑的单接口轮次目标由本轮启动器确认，单标签的吞吐不受分母修正影响。
- 专项工具提交说明：`test(perf): add concurrent and download validation scenarios`。31/31工具回归通过；`correctness --case favorite|resource`分别保留严格的失败或成功证据。已确认收藏恢复并发40901但SQL计数一致，业务代码未改；执行报告当前标为IN_PROGRESS，继续完成独立场景与最终对账。

- 专项对账加固：清单文件身份、资料文件/归属逐项核对；下载初始计数前也执行跨周期排空门禁。37/37工具单测通过，提交说明：`fix(perf): close manifest and download reconciliation gaps`。

- 按用户要求后续负载从100线程开始，专项新增`--start-threads 100`：正确性/小文件/冷缓存/重放100并发；10MiB上传按整包内存降10，签票100请求按原IP阈值严格30成功/70拒绝后30并发取流，调整原因入报告。40/40工具单测通过；提交说明：`feat(perf): support 100-thread initial load scenarios`。

- 真实100线程分类诊断发现工具共享LazyMap产生`categoryId=null`。修复为Classic完整解析、正整数分类ID不可变列表及URL组装门禁；原响应断言保持严格。41项常规测试通过，另1项JMeter/Groovy真实100线程内存回归通过；旧实现复现null/并发异常，V2无非法URL。V1/V2计划均留仓库外并记录各自hash；提交说明：`fix(perf): eagerly materialize shared JMeter data`。

## 2026-10-27 本地压测设计

- 当前分支 `dev`；本次仅新增 `docs/12-local-load-test-plan.md` 并同步索引、状态和交接，不修改业务代码、SQL、Redis Key、前端或部署资产。
- 用户明确只考虑本地架构。方案将普通接口容量、收藏/上传并发正确性、下载限流、短时文件读取与增量同步分别验证，按真实代码定义业务断言。
- 本轮验证为文档本地链接、真实路径/配置/关键语义核对与 `git diff --check`；没有运行 Maven、造数、JMeter 或性能实验，不能声称容量已验收。JMX 和千/万级造数尚待独立实现。
- 保留开始时已有的 `docs/DEPLOYMENT-QA-QUICK-REVIEW.md` 修改，不纳入本轮暂存。提交说明：`docs(perf): design local load testing workflow`；实际提交 ID 与推送结果见任务最终回执。
- 下一步：先准备专用 MySQL schema、专用本地 Redis 和上传目录及合成数据，再实现只读 P01～P03 计划；不得直接把现有 30～40 条造数脚本当作万级工具，或为压测擅自修改限流常量。

## 2026-10-24 本地开发数据保护

- 分支：`dev`，从`main@8bde7775`创建并跟踪`origin/dev`；用户明确要求重新建立该开发分支。本任务不修改Java业务代码、数据库结构、Redis Key、前端或生产部署脚本。
- 首个小功能`bfd983d feat(local-backup): add verified local data backup`已推送：新增公共安全函数、联合备份脚本和备份黑盒测试；测试为6/6。
- 第二个小功能`c03e9c4 feat(local-backup): add isolated local restore`已推送：实现严格manifest校验、专用最小权限账号隔离MySQL恢复、隔离Redis RDB校验，以及可选的上传目录stage/quarantine同盘切换；任何目标已存在、路径重叠、ReparsePoint、摘要不一致或目标库非全新时停止。
- 安全加固`d1b7742 fix(local-backup): harden mysql restore isolation`已推送：备份协议收缩为基础表，发现视图/触发器/例程/事件即停止；恢复使用导入凭据核对实际`CURRENT_USER()`与纯`USAGE`授权，只临时授权目标库并在导入对账后撤权。
- 最终模拟专项测试为备份8/8、恢复14/14；上传切换异常执行尽力补偿而非事务性保证，补偿不完整时必须保持停机并人工检查活动目录、`quarantine`和失败目录。
- 本地固定目录仅作为新环境建议。历史数据库保存绝对`storage_path`，本轮不移动现有运行文件；迁移固定目录必须单独完成文件复制、路径更新和下载验证。
- 本轮只完成伪MySQL/Redis客户端驱动的模拟黑盒测试；当前机器缺少`redis-cli`和`redis-check-rdb`，3306/6379也未提供专用本地服务，尚不能宣称真实联合恢复成功。
- 操作手册：`docs/LOCAL-DATA-PROTECTION.md`；运行入口同步到`docs/07-project-runbook.md`和`docs/README.md`。
## 登录自动刷新开发交接（2026-11-01）

- 开发分支：`codex/auth-auto-refresh`，基线 `origin/main@8bde7775`，位于独立工作树；不得把原 `dev` 分支的未提交修改混入。
- 本轮范围包括后端认证、前端会话/请求层、对应单测和文档。前端历史任务队列不重新启动。
- 后端采用含 `sid` 的短期 Access JWT 与 opaque Refresh Token；Redis 仅保存刷新摘要，轮换保留固定会话 TTL，退出删除会话，旧无 `sid` Token 在原有效期内兼容但不可刷新。
- 后端小功能已提交并推送 `abf8a40`。专项五类 26/26 通过；进一步回归命令（仓库根目录）：`./campus-resource-platform/mvnw.cmd -f campus-resource-platform/pom.xml '-Dtest=JwtUtilsTest,AuthServiceRefreshTest,AuthSessionServiceTest,*ControllerTest,JwtAuthenticationInterceptorTest' test -q`，11 类、58/58 通过，均为本地 mock/unit。
- 前端小功能已完成：双 Token 单 JSON 保存、单飞刷新、跨标签页锁、会话快照保护、一次请求重放，下载流复用且保留票据。认证失败清理与资料更新持锁重检，旧无 Refresh 会话过期后仍可退出；网络或 500 失败保留凭据供重试。
- 前端命令（仓库根目录）：`npm --prefix frontend run test:unit -- --reporter=dot`，33 文件、101/101 通过；`npm --prefix frontend run build` 通过。`git diff --check` 通过，只读复核无阻塞问题。
- 实现与测试已完成，功能保留在开发分支供审阅，未合并或部署到 `main`。旧会话需重新登录取得双 Token；无 Web Locks 时仅单页串行。没有待执行的前端队列任务。
- 用户明确只要求本地简单单元测试，不执行 Docker 或真实数据库/Redis 集成测试。不得将 mock 单测结果描述为真实 Redis Lua 原子性验证。

## 2026-10-17 四分支合入 main

- 分支关系：远程 `main@af1de973` → `dev@9e1e6d29` → `codex/deploy-02@e9730f74` → `deploy@a224aee0`；后者包含全部既有远程分支历史，因此使用 `git merge --ff-only origin/deploy` 汇入 `main`，无内容冲突或强推。
- 本地原 `main@744f02ea` 仅新增 15,142 个依赖缓存文件与两份运行上传文件，源码差异为零；原提交以仅本地标签 `codex/main-local-backup-20261017-744f02ea` 完整保留。移动 `main` 引用使用旧 SHA 校验，推送时只显式推送 `main`，不推备份标签或运行数据。
- 验证：`pwsh -NoProfile -File deploy/scripts/Test-Deploy04Build.ps1` 全部通过，覆盖隔离 MySQL 后端 208/208、后端打包、前端安装/76 项测试/生产构建、Compose 配置与双镜像构建；`Test-Deploy08Scripts.ps1` 9/9、`Test-Deploy07Https.ps1 -StaticOnly` 通过。
- 合并后用 `git merge-base --is-ancestor` 逐一验证四分支提交均包含在 `main`，并检查 `frontend/node_modules` 与 `campus-resource-platform/data` 未跟踪。新增差异仅涉及分支约定文档和四个验证脚本的默认分支参数，不新增业务代码或修改数据库/Redis/权限规则。
- `npm ci` 当前报告 9 项既有审计问题（3 moderate、6 high），前端主包大小提示仍存在；不在分支合并任务内升级依赖。DEPLOY-08 仍为 `IN_PROGRESS`，本轮不执行生产验收或恢复。
- 用户追加要求合并完成后仅保留 `main`；GitHub 默认分支切换为 `main` 后，删除已合入的远程 `dev`、`codex/deploy-02`、`deploy` 及本地 `dev`、`deploy`。本地旧提交备份标签保留，不推远程；历史记录中的旧分支名不代表当前仍有这些分支。
- DEPLOY-04 四个验证脚本默认 `RequiredBranch=main`，保留显式覆盖临时分支的能力；README、部署指南、文档索引和协作规范同步。提交说明：`chore: consolidate repository onto main branch`，实际提交 ID 和推送结果见任务最终回执。
- 单主干适配验证：四个默认分支参数的 AST 断言、8 个 PowerShell 脚本语法、106 个文档本地链接/锚点及 `git diff --check` 通过，活动入口无旧分支依赖。

## 2026-10-17 部署文件发布与 README 配置说明

- 分支：`deploy`；开始时 HEAD 为 `aa4de3d9`，工作区干净，远程 `origin/deploy` 已包含现有部署配置和脚本。
- 本轮范围：根 `README.md`、`deploy/README.md`、`docs/README.md`、`docs/DEPLOYMENT_HANDOFF.md`、`docs/CURRENT_STATUS.md` 和本文件；统一部署入口、环境变量、镜像交付、HTTPS 运维与阶段状态。
- 关键约束：服务器以准确镜像标签及 `--no-build --pull never` 启动；HTTPS 使用基础编排 + HTTPS override；生产项目名、卷和 `/data/uploads` 不随意更改，Secret / 私钥 / 备份不进入 Git。
- 无新增依赖，无业务代码、接口、数据库结构、Redis Key 或生产服务器变更。本轮任务为文档发布，不执行真实生产上线或备份操作。
- 验证命令与结果：`pwsh -NoProfile -File deploy/scripts/Test-Deploy08Scripts.ps1` 为 9/9；`pwsh -NoProfile -File deploy/scripts/Test-Deploy07Https.ps1 -StaticOnly` 通过；`docker compose ... config --quiet` 的 HTTP / ACME / HTTPS 三组合及内存内映射断言通过，106 个 Markdown 链接/锚点、两份 README 的 36 个命令块语法、6 个 Shell 的逐项 `bash -n`、8 个 PowerShell 语法解析与 `git diff --check` 通过。
- Docker Engine 本轮未启动，因此未重跑镜像构建和运行演练。只读审查提出的证书 hook 查找路径问题已在根 README 与部署手册补齐：默认 `/srv/campusshare/app`，自定义目录显式设置宿主机脚本变量 `CAMPUSSHARE_HTTPS_CONFIG`。
- 本轮提交说明：`docs(deploy): document production configuration and release workflow`；实际提交 ID 与远程推送结果由任务最终答复给出。
- 下一步仍是 DEPLOY-08 尚未完成的公网业务和生产联合恢复验收，具体按 `docs/DEPLOY-08-RUNBOOK.md` 执行；预检脚本资产不代表生产恢复成功。

## 2026-09-25 DEPLOY-07 HTTPS 与证书续期

- 分支：`deploy`；实现提交 `7a32b55e` 已推送到 `origin/deploy`。服务器加载该提交的 Compose/Nginx/证书脚本，但继续运行 DEPLOY-05 已验证的 immutable 前后端镜像。
- DEPLOY-06 DNS 已完成：根域名 A 和 `www` CNAME 均解析正常；DEPLOY-07 保留两个域名并申请同一张 SAN 证书。
- 新增 HTTP+ACME 与 HTTPS 两个 Compose override；80 除 challenge 外统一 301 到根域名 HTTPS，443 在容器内使用非特权 8443，健康检查走仅 loopback 的 8081。
- 证书部署脚本校验 lineage、SAN、有效期、公私钥、卷标签和运行镜像，通过指纹 release、`current.next` 与 `mv -fT` 原子切换；私钥 `0400`、证书 `0444`、Nginx 只读挂载，不暴露 Docker socket。
- 本地验证通过：证书脚本黑盒测试、DEPLOY-07 HTTPS Compose 演练、后端 208/208、前端 76/76、双镜像构建及 DEPLOY-04 Compose 故障恢复/持久化回归。
- 生产验证通过：Let's Encrypt 双域名证书、80/443 跳转与页面、ACME 路径、Certbot dry-run、deploy hook 原子切换和运行中 Nginx reload；dry-run 后已重新部署生产 lineage 并核对证书一致。
- 四个容器均 healthy、重启次数为 0，Certbot timer 为 enabled/active。下一任务为 `DEPLOY-08` 公网完整业务验收与生产联合备份恢复。
- 本地 `npm ci` 审计报告 5 个既有前端依赖问题（3 个 moderate、2 个 high），本任务未扩大为依赖升级；需在独立任务中核对生产可达性和兼容升级范围。

## 2026-09-24 DEPLOY-05 服务器初始化与部署

- 分支：`deploy`；服务器部署身份固定为提交 `e554ec5619010e60c29ca3b9798c02cdcfb4c048`，未在服务器重新构建镜像。
- Ubuntu 24.04/amd64、2 核 2 GB、40 GB 系统盘和 1.9 GB Swap 基线通过；SSH 仅允许公钥普通账号登录，AppArmor、UFW、Docker、Compose 均启用。
- DEPLOY-04 发布包在服务器端完成 SHA-256 复核；前后端 immutable image ID 与本地证据一致，MySQL、Redis 使用 Compose 固定 digest。
- 生产 Secret 仅在服务器本地生成并保存在 `600` 权限的未跟踪 `deploy/.env`；四服务使用 `--no-build --pull never` 启动且全部 healthy。
- 本地和公网 IP + Host 头验证首页、登录页、Nginx、liveness、readiness 均为 HTTP 200；MySQL 最小权限、Redis 认证、内部端口隔离和三个命名卷通过。
- 宿主机重启后 Docker 与四容器自动恢复，卷和镜像身份保持不变，Swap 为 0、无 OOM。MySQL 稳态接近 512 MiB 容器上限，已列为后续重点容量监控项。
- 服务器发布目录保存了不含 IP 和 Secret 的 DEPLOY-05 验收证据。下一任务为 `DEPLOY-06` DNS 配置；尚未修改 DNS 或启用 HTTPS。

## 2026-09-24 DEPLOY-04 本地发布门禁

- 分支：`deploy`；提交 `1a8a4e36`、`e2aa4fa7`、`79fb3b2f`、`1e475b18`、`42b39a18` 已推送到 `origin/deploy`。
- 新增统一 Build、Compose、Migration、Rollback 演练脚本；证据写入系统临时目录，Secret、数据库导出和卷归档不进入仓库。
- 验证：后端 208/208、前端 76/76、双镜像构建、空卷与 SPA、readiness、最小权限、内部端口隔离、依赖故障恢复及三个持久卷重启保留均通过。
- 迁移：三份 SQL 首次执行和完整重跑通过，七张 legacy 表迁移前后全列摘要一致，七类错误结构和 Redis guard 均被正确拒绝。
- 回滚：停止前端/后端后联合备份 MySQL、Redis 完整数据卷和上传卷，恢复到新空卷后切点前 marker 全部存在、切点后 marker 全部不存在；固定 `64498230` 旧镜像通过 revision、健康及上传卷读写验证。
- 本地 Windows MySQL/Redis 服务未启动；所有 Docker project、卷、网络、临时历史镜像和含敏感数据的临时目录均已清理。下一任务为 `DEPLOY-05` 服务器初始化与部署，暂不配置 DNS。

## 2026-09-22 DEPLOY-03 生产安全收敛

- 分支：`deploy`；基线 `e9730f74`，提交 `3a2c5e96`、`9272af41`、`9a3c2d86`、`5dee7699`、`740a4d97`、`248bc29d` 已推送到 `origin/deploy`。
- 同源部署取消后端 CORS；Nginx 覆盖代理头，Tomcat 只信任私网代理，业务层统一使用解析后的 `remoteAddr`。
- `prod` 配置校验应用账号、MySQL/Redis 密码、JWT Secret、Secret 复用和磁盘保留水位。新 MySQL 空卷通过初始化脚本仅授予应用账号 `SELECT/INSERT/UPDATE`；旧卷不会自动执行，必须人工 `SHOW GRANTS` 并整改。
- readiness 分别探测 MySQL、Redis、上传目录写入和磁盘水位，失败返回 503；原 `/api/v1/health` 保持 liveness。Spring 优雅停机 30 秒，Compose 宽限 40 秒。
- 上传检查扩展名、客户端 MIME、服务端签名/文本/压缩包结构；OOXML 校验主内容类型与根关系，拒绝宏和外部关系。落盘使用容量水位、进程锁、同目录临时文件与原子移动，下载使用安全附件响应。
- 当前不含病毒扫描、历史文件重扫、严格用户累计配额、多实例共享存储或对象存储；2 GB 主机不运行重型扫描服务。
- 后端全量测试在隔离 MySQL 下 208/208 通过；专项安全补丁 26/26、配置/健康专项 6/6、前端 76/76 和生产构建通过。
- 真实空卷容器中 readiness 为 200；停止 Redis 后约 2.3 秒、停止 MySQL 后约 3.3 秒返回 503，恢复后自动回到 UP；应用账号仅有 `SELECT/INSERT/UPDATE`，Redis 未认证拒绝，内部服务无端口发布，优雅停机完成。测试容器、卷和镜像已清理。
- 下一任务为 `DEPLOY-04` 的最终镜像、迁移、持久化和回滚演练。

## 2026-09-22 DEPLOY-02 容器化部署资产

- 分支：`codex/deploy-02`；从 `dev@9e1e6d29` 创建，功能提交 `6449823` 已推送到同名远程分支。
- 新增后端和前端多阶段镜像、`deploy/docker-compose.yml`、非特权 Nginx 配置、`deploy/.env.example` 与 `deploy/README.md`；真实 `deploy/.env` 保持忽略且未提交。
- Compose 仅发布 Nginx 端口，内部运行后端、MySQL 8.4 与 Redis 7.4；数据库、Redis AOF 和 `/data/uploads` 使用独立命名卷。
- 2 GB 资源预算为后端 768 MB、MySQL 512 MB、Redis 160 MB、Nginx 64 MB；JVM 最大堆 512 MB，Hikari 最大连接 8，MySQL Buffer Pool 256 MB，Redis `maxmemory` 96 MB 且 `noeviction`。
- 供应链与权限：所有基础镜像固定 digest，Maven Wrapper 校验 SHA-256；后端、Redis、Nginx 均以非 root 用户运行；Secret 只由未提交环境文件注入。
- 验证：前端全量 31 文件 76/76；后端全量 169/169；镜像构建和 Compose 配置通过；空卷四服务健康；`/healthz`、API、SPA 路由通过；内部端口无宿主机绑定；容器重建后 MySQL、Redis、上传文件均保留。
- 本地 MySQL 服务没有被修改；后端数据库集成测试使用隔离的临时 MySQL 实例完成。下一任务为 `DEPLOY-03`。

## 2026-09-21 前端门户 UI 与响应式布局升级

- 分支：`dev`；不新增依赖、不改变 API 与权限边界，将前台、管理端、首页和认证页统一为靛蓝/紫色校园知识门户风格。
- 前台使用三段式吸顶导航；管理端使用桌面侧栏和移动端横向菜单；首页新增声明式关键词搜索、上传/浏览 CTA、真实平台能力标签与更清晰的热门榜单层级。
- 首页搜索支持点击和回车，空白关键词不携带 query；登录页消费路由守卫写入的站内 `redirect`，并拒绝 `//`、反斜杠、换行和非字符串目标，防止开放重定向。
- 可访问性补充：`lang=zh-CN`、语义化导航和搜索表单、可见焦点、榜单周期 `aria-pressed`、减弱动效支持；320px 管理员态通过保留品牌标识并隐藏品牌长文案避免溢出。
- 验证：UI 专项测试 5 文件 18/18、前端全量 31 文件 76/76、`npm run build`、`git diff --check` 均通过；真实浏览器桌面首屏和 375/320 设备指标宽度检查通过。
- 已知事项：Vite 构建仍提示主包约 844.05 kB 超过 500 kB；这是既有警告，本次未扩大为懒加载或拆包改造。

## 2026-08-13 资料有效状态条件唯一约束

- 分支：`dev`；`resource` 新增生成列 `active_duplicate_guard` 和唯一索引 `uk_resource_active_duplicate (uploader_id, file_id, active_duplicate_guard)`。
- 待审核/已通过资料的生成值固定为 `1`，从数据库层阻止同一用户、同一文件出现第二条有效资料；拒绝/下架/删除生成 `NULL`，允许后续重新提交。
- `ResourceServiceImpl.create` 保留前置查重，并把并发插入产生的 `DuplicateKeyException` 转换为 `DATA_DUPLICATE`。
- 存量库迁移为 `sql/migrations/20260813_resource_active_duplicate_guard.sql`，执行前检查有效重复组，支持重复执行；回滚顺序为先删唯一索引、再删生成列。
- 本机 MySQL 8.0.45 的 `campus_resource_platform` 已完成迁移，执行前 3 条资料、有效重复组 0；迁移前表备份位于系统临时目录，不纳入 Git。
- 验证：资料数据库/Service/Controller 专项测试 27/27、后端全量 169/169 通过；本机迁移连续执行两次成功，事务探针验证第二条有效资料被拒绝、无效资料可共存且回滚后无残留。

## 2026-08-07 文件 MD5 三态与负缓存

- 分支：`dev`；新增 `FileMd5CacheService` / `FileMd5CacheServiceImpl`，继续使用 `RedisKeyConstants.fileMd5Cache(md5, size)`，缓存正值为十进制 `fileId`，负值为固定 `NOT_FOUND`。
- 预检按 `FOUND` / `NOT_FOUND` / `ABSENT` 分流：FOUND 仍查用户授权，NOT_FOUND 不查文件表和授权表，ABSENT 回源 MySQL 并按结果写正缓存或 `SET NX` 负缓存。
- 正值 TTL 6 小时，负值 TTL 5 分钟；上传主链仍直查 MySQL，在新文件事务、既有文件授权及并发唯一键回退授权成功后用普通 `SET` 覆盖旧负值。Redis 读写异常不影响数据库主流程。
- 非正、溢出或协议外缓存值最佳努力删除；显式 `evict(md5, size)` 已提供，但没有真实文件删除/恢复入口调用，生命周期失效闭环留给 `BATCH-17`。
- 验证：`.\\mvnw.cmd -DskipTests compile` 通过；缓存专项测试 21/21、后端全量测试 173/173 通过。正缓存写失败不影响数据库及最终上传正确性，但旧负值可能造成最长 5 分钟的预检假阴性；真实 Redis/数据库并发集成与生命周期接线仍待后续完成。

## 2026-08-07 公开资料详情缓存

- 分支：`dev`；已实现 `crp:cache:resource:detail:{resourceId}`，缓存 `ResourceDetailVO` 公共 JSON，TTL 30 分钟 + 随机 0~5 分钟且 `favorited` 固定为 `null`。
- 公开详情采用 Cache Aside；ID/状态/用户态字段不合法或坏 JSON 时删除并回源，Redis 故障保持 MySQL 原错误语义，不做不存在/不可见负缓存。
- miss 回填和审核失效共享每资料锁并最多等待约 2 秒；竞争/Redisson 异常线程只回源不回填。审核提交后 `afterCommit` → `invalidate()` 持锁 DELETE（与回填互斥）；锁超时（2s）降级直接删除。
- Redis/Redisson 同时故障时 `deleteQuietly` 仅告警，缓存残留由 TTL（30~35分钟）兜底。
- 最终验证：缓存专项 34/34、资料/审核相关回归 74/74、Spring 上下文 1/1、后端全量 155/155 均通过；本功能将按项目规则提交并推送到 `dev`。

## 2026-08-05 多 Agent 项目初始化 Skill

- 个人 Skill 名称：`$multi-agent-project-bootstrap`，默认安装目录为 `$CODEX_HOME/skills/multi-agent-project-bootstrap`。
- 新项目中直接要求使用该 Skill，即可先 dry-run 再幂等创建或合并 `.codex/`、Agent 角色、根协作规则和操作文档。
- 当前项目已完成旧并发字段迁移：使用 `agents.max_concurrent_threads_per_session = 4`，该数量不包含主 Agent；`agents.enabled = true` 已显式启用。
- Skill 应用时保留了当前项目四个专属 Agent、根 `AGENTS.md` 和 `docs/08-multi-agent-collaboration.md`，没有覆盖项目业务规则。
- 后续升级 Skill 时，应先运行其 11 个回归测试和 `skill-creator/scripts/quick_validate.py`，再在临时仓库做前向测试。

## 2026-07-15 IMP-001、IMP-003、IMP-004、IMP-005

- 分支：`dev`；四个独立实现提交均已推送到 `origin/dev`。
- 提交：`8b4ae92`（JWT 密钥）、`024aff7`（审核文件读取）、`4ab0da0`（一次性下载票据）、`f07e704`（用户文件授权）。
- 发布前必须先执行 `sql/migrations/20260715_user_file_authorization.sql`，再部署 `f07e704` 及其后版本。
- 下载客户端必须把创建记录响应中的 `downloadTicket` 放入 `X-Download-Ticket` 请求头；旧客户端直接 GET 文件流将失败。
- IMP-004 仅处理重放、下架复核和 Redis 故障关闭，不含 IMP-008 的计数时点修正。
- 验证：后端全量 124/124、前端全量 66/66、前端生产构建均通过。

## 2026-07-14 Codex 多 Agent 协作配置

- 项目级 Agent 配置位于外层仓库 `.codex/`，不要放到内层 Spring Boot 工程。
- 已配置 `architect`（只读分析）、`implementer`（单写者实现）、`tester`（测试）和 `reviewer`（只读审查）。
- 默认最多 4 个 Agent 线程，只允许主 Agent 创建一级子 Agent；角色模型继承父会话，不锁定具体模型名称。
- 新任务应重新加载项目配置，并按 `docs/08-multi-agent-collaboration.md` 中的只读提示词验证角色是否可用。
- Subagent 共享同一工作区；提交时必须显式暂存本任务文件，保留工作区中的其他未提交改动。

## 2026-07-14 工作目录整理

- 文档入口改为 `docs/README.md`：数据库资料在 `docs/database/`，接口参考和 Postman 集合在 `docs/api/`，前后端业务模块映射在 `docs/modules/README.md`。
- 后续引用 API 文档时使用 `docs/api/api-reference.md`；导入 Postman 时使用 `docs/api/postman/` 下的集合和本地环境示例。
- 未移动 `campus-resource-platform/data/` 下的运行时上传资料，也未删除本地工具、构建或依赖缓存目录。
- 已通过 Markdown 本地链接检查、前端全量单测与构建、后端跳过测试编译；本次仅调整文档与接口调试资料路径。

## 2026-07-14 排行榜与定时任务补强

- 已完成 P1：`RankingMapperIntegrationTest` 在 H2 MySQL 模式下验证 `ResourceMapper.xml` 的公开资料过滤、热度兜底排序、主键游标扫描和 APPROVED 热度快照更新。
- 已完成 P2：`RankingTaskExecutionMonitor` 以当前应用实例内最近快照记录下载增量同步、all 榜重建和热度快照任务的开始/结束时间、耗时及未捕获异常类型；调度入口已接入统一日志，任务触发与监控单测共 4 个用例通过。
- 已完成 P3：下载增量以 UUID 批次隔离，`download_delta_sync_item` 的 `(batch_id, resource_id)` 唯一键与 `resource.download_count` 累加同事务提交；Redis 确认失败后重试同批次不会重复计数。建表已写入 `sql/init.sql`，存量库执行 `sql/migrations/20260714_download_delta_sync_idempotency.sql`；P3 专项 13 个、排行榜关联 19 个及当前全量 113 个测试均通过。
- 发布 P3 前必须先排空或人工核对旧 `crp:stats:resource:download:syncing:active` Hash；切换期间停止旧版本调度器，避免旧、新批次协议并行。后续如需继续，应单独设计跨实例指标聚合、历史持久化、管理员查询接口、失败告警和幂等明细保留期清理；不应直接复用进程内快照作为跨实例运维数据。

## 当前分支

| 项目 | 内容 |
| --- | --- |
| 分支 | `dev` |
| 分支用途 | 项目日常迭代与前端演示模块开发 |
| 稳定分支 | `main`，不直接提交未稳定的前端功能 |
| 当前前端队列 | `docs/frontend/05-frontend-task-queue.md` |
| 当前前端任务 | 无；前端演示队列已完成 |

## 已完成前端文档

| 文档 | 内容 |
| --- | --- |
| `docs/frontend/01-frontend-requirements.md` | 前端演示范围、普通用户与管理员页面、后端亮点和非目标 |
| `docs/frontend/02-page-design.md` | 页面路由、字段、操作与接口设计 |
| `docs/frontend/03-api-mapping.md` | 27 个后端接口的前端 API 映射与文档缺失说明 |
| `docs/frontend/04-frontend-dev-plan.md` | F01-F47 增量开发计划 |
| `docs/frontend/05-frontend-task-queue.md` | T01-T47 自动推进任务队列 |

## 前端开发规则

- 前端只服务于后端项目演示，使用 Vue 3 + Vite + Element Plus + Axios。
- 每个子任务完成后必须运行专项测试和 `npm run build`，再独立提交并推送。
- 自动推进规则仅适用于 `frontend/` 下前端代码任务；涉及后端、SQL、Redis 或接口变更必须停止。
- 每轮自动推进最多执行用户指定的阶段数；本轮最多三个阶段。
- `frontend/` 已完成 T01-T47：真实验证认证、文件秒传、待审核创建、管理员审核、公开详情、权限边界、退出 Token 黑名单，以及搜索、收藏、下载、排行榜和管理员下架闭环。用户提供的 PDF 保持为 `APPROVED` 演示资料；另一个测试资料已按 T46 验证下架、公开隔离和审计流水。运行手册、锁定依赖安装、全量测试、构建和 Vite 启动验证也已完成。

## 工作区注意事项

- 开始前先运行 `git branch --show-current`、`git status --short --branch` 和 `git log -5 --oneline --decorate`。
- 工作区中如出现与当前前端任务无关的修改，必须保留，不得暂存或覆盖。
- `AGENTS.md` 可能因本地行尾格式显示为修改；如无内容差异，不得将其混入前端子任务提交。

## 下一位执行者

1. 前端自动任务队列已完成，不再自动创建新的前端任务。
2. 后续开发先读取根 `AGENTS.md`、本文件、`docs/CURRENT_STATUS.md` 与相关功能文档。
3. 继续保持前端仅作为后端演示界面，不记录真实密码、Token、数据库连接信息、绝对上传路径或其他敏感内容。

## 2026-07-13 前端修复与浏览器验收

- 修复 `VITE_API_BASE_URL` 默认值和开发示例配置，将 `/api` 对齐为后端实际版本化路径 `/api/v1`；Vite 仍通过相对路径转发到本机后端。
- 已在真实 Edge 中完成游客、普通用户、管理员流程验收。测试账号仅在本机临时环境变量中使用，未写入仓库。
- 前端全量单测基线更新为 31 个文件、66 个用例；提交前应再次执行 `npm run test:unit` 和 `npm run build`。

## 2026-07-13 项目运行手册

- 新增 `docs/07-project-runbook.md`，作为 MySQL、Redis、后端、前端、联调与排查的统一启动入口；根 `README.md` 已链接该手册。
- 手册明确 `VITE_API_BASE_URL=/api/v1`、后端健康检查和运行时上传目录规则；未写入任何真实敏感信息。

## 2026-11-01 双 Token 说明交接

- 工作分支`codex/auth-auto-refresh`；后端`abf8a403`、前端`aa5b4f7f`已推送，尚未merge/deploy；原dev工作区修改保持独立。
- 文档已按[生命周期指南](modules/01-auth-token-lifecycle.md)统一刷新、退出、兼容、错误优先顺序和Redis失败关闭；JWT_SECRET轮换不等于撤销Refresh。
- 上轮 58/101 单测与构建结果仅引用历史证据，本轮不改代码/依赖/表，不重跑业务测试，不执行外部服务集成。本轮 16 份文档的本地链接、锚点、指南 4 个 JSON 示例和代码围栏检查通过，`git diff --check` 通过；文档随当前功能分支提交推送。
