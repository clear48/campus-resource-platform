# 部署关键流程问答速查

整理日期：2026-10-27。按本次对话顺序收录 14 个问题，保留问题原文，解答用于快速回忆；详细执行命令以对应运行手册为准。

核对基线：`dev@afd7bfcb`。本文基于代码、配置和历史记录整理，不代表本次已执行构建、迁移、SSH 配置或生产验收。服务器内部构建是讨论中的替代方案，现有部署手册仍采用本地构建、镜像离线交付。

## 一分钟回忆

| 主线 | 关键顺序 |
| --- | --- |
| 上线 | 固定版本 → 测试 → 构建镜像 → 验收 → 备份和必要迁移 → 启动 → HTTPS 与公网业务验证 |
| 配置 | `.env` → Compose 变量替换与显式映射 → 容器环境 → Spring Boot 内存配置 |
| 数据 | 排空增量并停写 → MySQL、Redis、上传文件联合备份 → 独立恢复 → 路径和结构适配 → 对账 → 切换 |
| SSH | 本机保存私钥 → 目标账号配置公钥 → 客户端签名、服务器验证 → 按该 Linux 账号权限操作 |

阅读导航：全流程见 Q01；环境变量见 Q02—Q03；账号见 Q04；测试、打包、镜像见 Q05—Q10；数据与备份见 Q11—Q12；SSH 与密钥见 Q13—Q14。

## Q01：讲清楚线上部署的完整流程

**精简解答：先得到可追踪、可运行的应用镜像，再准备数据、配置和公网入口，最后证明业务与恢复能力可用。**

1. 固定发布提交，确认工作区干净，记录源码、配置与镜像版本。
2. 运行后端、前端测试，构建两份应用镜像，并完成隔离运行、迁移和回滚演练。
3. 准备服务器、Docker、Compose、SSH 和防火墙；公网入口为 Nginx，后端、MySQL、Redis 不发布公网端口。
4. 准备生产 `.env`、持久卷和镜像；新库初始化与旧库升级分别处理。
5. 启动 HTTP，检查入口、后端存活和依赖就绪，再配置 DNS、证书、HTTPS 和续期 hook。
6. 通过公网验证注册、上传、审核、搜索、收藏、下载；执行备份、独立恢复和上一镜像业务兼容验收。
7. 持续观察日志、资源、证书、同步任务和备份结果。

请求链路：`浏览器 → Nginx → Spring Boot → MySQL / Redis / 上传卷`。前端静态文件由 Nginx 提供，API 使用同源 `/api/v1`。

三个检查层次：`/healthz` 检查 Nginx；`/api/v1/health` 检查后端存活；`/api/v1/health/readiness` 检查 MySQL、Redis、上传目录和磁盘水位。容器 healthy 不等于全部业务或恢复验收通过。

首次初始化只建结构，不预置分类、普通用户或管理员。后续更新复用既有数据卷和 HTTPS 配置，不需要重新初始化数据。

## Q02：如何读取生产环境变量

**精简解答：区分“文件里配置了什么”和“容器实际收到了什么”。**

- 文件配置：在服务器受控终端查看 `deploy/.env`，仅核对非敏感项时使用字段白名单。
- Compose 配置：通过 `--env-file deploy/.env` 明确指定来源；当前项目使用 `environment` 映射注入后端。
- 容器实际值：通过 `docker compose ... exec -T backend printenv <变量名>` 查看指定变量；HTTPS 环境保持同一 `.env` 和两个编排文件。
- 应用配置：查看 `application.yaml` 中的占位符，以及 Compose 注入的 Spring 配置变量。

实际映射示例：`MYSQL_APP_USERNAME → 容器 MYSQL_USERNAME → spring.datasource.username`。

不要整体输出 `printenv` 或完整 `docker compose config`，它们可能包含 Secret；配置校验使用 `config --quiet`。宿主机同名环境变量可能覆盖文件值；`--env-file` 不会把变量导入当前终端。修改文件后需要重建受影响的容器，单纯 `restart` 不会重新注入变量。前端 `VITE_*` 会进入浏览器产物，不能存放 Secret。

## Q03：相当于compose将.env文件读取到容器环境，spring boot再将容器环境中的变量写入.yaml文件

**精简解答：前半句要补充“显式映射”，后半句应改成“解析占位符，在内存中得到配置值”。**

```text
.env 中 MYSQL_APP_USERNAME=campus_app
→ Compose 映射 MYSQL_USERNAME=campus_app
→ Spring Boot 解析 username: ${MYSQL_USERNAME:root}
→ 内存中的 spring.datasource.username 为 campus_app
```

`.yaml` 文件保持原样，不会被写回实际值。`${变量名:默认值}` 表示解析环境配置，缺失时使用默认值；生产校验另会拒绝 root 应用账号等不合格配置。

`.env` 中所有变量不会自动进入容器，必须经 `environment`、服务级 `env_file` 等机制传入；当前项目采用前者。

## Q04：普通运维账号，应用账号，管理员账号，SSH连接账号的区别

**精简解答：先判断账号属于 Linux、MySQL 还是网站业务系统；“管理员”必须说明管理哪一层。**

| 身份 | 所属系统 | 用途 |
| --- | --- | --- |
| 普通运维账号，例如 `ops` | Linux | 部署、查看日志，按授权使用 `sudo` |
| SSH 连接账号 | Linux | 经 SSH 登录的目标账号，通常就是上述运维账号 |
| 应用账号，例如 `campus_app` | MySQL | Spring Boot 读写业务库；当前新库授予 `SELECT/INSERT/UPDATE` |
| 数据库管理员，例如 MySQL `root` | MySQL | 建库、结构迁移、账号和授权管理 |
| 系统管理员 `root` | Linux | 管理服务器；与 MySQL root 是两个身份 |
| 网站管理员，业务 `role=2` | 校园资料平台 | 审核、下架资料等业务操作 |

`ops` 是示例名。SSH 是连接方式；获得 SSH 登录能力不会自动获得 sudo 权限。容器内运行 Java 的 Linux 用户 `app`，也与 MySQL `campus_app` 不同。

网站管理员审核资料时，由后端校验业务角色，后端仍使用应用数据库账号访问 MySQL，不切换成 MySQL root。

## Q05：构建镜像的详细过程（测试，打包到完整镜像）

**精简解答：先验证源码，再通过 Docker 多阶段构建生成运行镜像，最后运行镜像验证。**

1. 检查 Git、工具版本和源码状态；构建结束复查版本和工作区。
2. 创建一次性 MySQL，为后端全量测试提供隔离依赖。
3. 宿主机依次执行后端 `mvnw.cmd test`、`mvnw.cmd -DskipTests package`。
4. 前端依次执行 `npm ci`、`npm run test:unit`、`npm run build`。
5. Compose 根据两个 Dockerfile，在 builder 阶段从源码重新生成 JAR 和 `dist`。
6. 将 JAR 复制到 JRE 阶段，将 `dist` 复制到 Nginx 阶段，形成最终镜像。
7. 记录镜像标签和 ID，清理临时测试依赖；再启动隔离环境验收。

| 产物 | 包含内容 | 作用 |
| --- | --- | --- |
| 后端可执行 JAR | 业务类、资源、Java 依赖和启动加载器 | 交给 Java 运行 |
| 前端 `dist` | HTML、JavaScript、CSS 等静态资源 | 由 Nginx 提供访问 |
| 后端镜像 | JRE、JAR、运行用户及启动等配置 | 创建后端容器 |
| 前端镜像 | Nginx、静态资源及镜像运行配置 | 创建前端容器；生产代理配置另由 Compose 挂载 |

`RUN` 在镜像构建时执行；`ENTRYPOINT` 指定容器启动程序；`EXPOSE` 只声明端口，不发布公网端口。

对应资产：[构建脚本](../deploy/scripts/Test-Deploy04Build.ps1)、[后端 Dockerfile](../campus-resource-platform/Dockerfile)、[前端 Dockerfile](../frontend/Dockerfile)。

## Q06：本地测试时的打包和构建后的打包是一回事吗，都使用maven吗

**精简解答：后端两次 JAR 打包都用 Maven 和同一份 POM，但在不同环境生成不同产物；第二次发生在 Docker 构建过程中。**

| 操作 | 执行位置 | 目的 |
| --- | --- | --- |
| `mvnw.cmd test` | Windows 宿主机 | 编译并执行测试，不生成最终 JAR |
| `mvnw.cmd -DskipTests package` | Windows 宿主机 | 验证宿主机能打包成功 |
| `sh ./mvnw -DskipTests package` | Docker builder | 生成放入最终镜像的 JAR |
| Docker build | Docker 构建器 | 组装文件系统层及运行元数据，形成镜像 |

两次 Maven Wrapper 使用项目指定的 Maven 版本。后端 `target/`、前端 `dist/` 被 `.dockerignore` 排除，Docker 不直接使用宿主机产物。前端构建使用 npm/Vite，不使用 Maven。

## Q07：为什么需要多阶段构建

**精简解答：构建需要编译工具，运行只需要运行环境与产物；多阶段只把所需产物带入最终镜像。**

```text
后端：JDK + Maven + 源码 → JAR → JRE + JAR
前端：Node + npm + 源码 → dist → Nginx + dist
```

每个独立基础镜像的 `FROM` 开始一个阶段，`COPY --from=builder` 选择性复制产物。构建工具、源码目录和依赖缓存不会自动进入最终运行阶段，可减少镜像体积和不必要组件。

多阶段不是必须；也可以外部生成产物，再复制进单阶段运行镜像。它与“宿主机和 Docker 各打包一次”是两个概念，不表示线上需要同时运行构建阶段。

## Q08：镜像如何交付到服务器

**精简解答：当前方案通过离线镜像归档交付；Git 推送源码不会自动交付镜像。**

```text
本地已验证镜像
→ docker image save 导出 tar
→ 记录归档 SHA-256、镜像 ID 和平台
→ scp 经 SSH 传输
→ 服务器核对归档摘要
→ docker image load 导入
→ 核对镜像 ID 和标签
→ Compose 使用指定镜像启动
```

`save` 可以归档两个应用镜像及依赖层；`load` 恢复镜像和标签，不会启动容器。归档摘要校验传输完整性，镜像 ID 校验镜像身份；平台应匹配服务器 `linux/amd64`。

Compose、Nginx 配置需另外准备；生产 `.env` 独立管理；MySQL、Redis 固定镜像也须事先具备。启动用 `up -d --no-build --pull never`，HTTPS 使用基础编排加 HTTPS override。业务数据卷不在镜像包中。

另一种方式是推送镜像仓库，再由服务器拉取。详细离线流程见[生产部署手册](../deploy/README.md)。

## Q09：假设我的服务器支持内部构建镜像，不用本地构建镜像再上传，构建镜像的流程会有什么变化

**精简解答：交付源码和构建规则，服务器直接生成镜像，省去镜像包导出、传输和加载；测试及运行验收继续保留。**

| 项目 | 本地构建 | 服务器构建 |
| --- | --- | --- |
| 获取内容 | 应用镜像包、部署配置 | 指定提交的源码、Dockerfile、部署配置 |
| Maven/npm 构建位置 | 本地 Docker builder | 服务器 Docker builder |
| 应用镜像出现位置 | 本地镜像库，交付后加载到服务器 | 直接进入服务器镜像库 |
| 可省略步骤 | 无 | 应用镜像的 `save/scp/load` 与归档传输检查 |
| 始终保留 | 测试、镜像身份记录、运行验收、备份、必要迁移、回滚准备 | 同左 |

仅做 Docker 内构建时，服务器宿主机不必另装 JDK/Node；若测试也在宿主机执行，则仍需这些工具。服务器需具备依赖下载能力、构建资源和空间；与生产同机时，构建会争用资源。

现有两个 Dockerfile 可沿用。完整 Linux 测试流程需适配：现有 PowerShell 构建脚本调用 `mvnw.cmd`，不能在 Linux 原样套用。

## Q10：给新流程的完整构建过程

**精简解答：服务器独立目录获取源码 → 隔离测试 → 构建 → 独立运行验收 → 保存候选镜像，正式发布另设门禁。**

1. 准备 Git、Docker、Compose；采用宿主机测试时，另备 JDK 17、Node 24/npm、curl 和 OpenSSL。
2. 在独立构建目录检出完整提交 SHA；设置包含源码版本与本轮编号的候选镜像标签，保留旧镜像。
3. 启动仅 loopback 随机端口的一次性 MySQL，设置 `MYSQL_TEST_URL/USERNAME/PASSWORD` 指向独立测试库；不继承生产 profile、数据库配置或 Secret。
4. 后端执行 `sh ./mvnw clean test`，通过后执行 `sh ./mvnw -DskipTests package`。
5. 前端执行 `npm ci --include=dev`、`npm run test:unit`、`npm run build`。
6. 从两个工程上下文执行 Docker build，生成匹配服务器平台的两份镜像；Docker 内仍从源码生成交付产物。
7. 保存提交和镜像 ID，复查源码未变化；释放一次性测试数据库。
8. 使用唯一 Compose project、独立测试 Secret、独立卷和 loopback 端口启动候选，检查实际镜像、四服务健康、接口、权限、内部端口隔离、故障恢复和持久化，并验证业务。
9. 保存测试报告和验收证据。结束测试资源后保留候选镜像，待生产备份、迁移和回滚条件齐备，再按准确镜像身份发布；发布时不重新构建。

关键边界：审核数据库集成测试会重建测试表，绝不能连接生产库。当前 Maven 测试不要求真实 Redis，但镜像运行验收需要。基础 curl 检查不能替代完整业务、持久化和回滚门禁。

这是从现有代码推导的 Linux 流程，不是已经实测通过或已实现的 Linux 一键构建脚本。

## Q11：数据的迁移过程

**精简解答：首次搬迁到全新目标时，在同一停写切点联合迁移 MySQL、Redis 和上传文件，再适配结构与路径、对账和切换。**

```text
盘点数据并在独立环境演练
→ 正式窗口阻止业务写入、排空增量、停止后端与任务
→ 复核无 delta/syncing 和 .part
→ 联合导出、加密传输、校验
→ 独立恢复 MySQL、Redis 和上传目录
→ 按需升级表结构、逐文件转换路径
→ 数据对账与真实业务验证
→ 旧环境保持停写，开放新环境
```

- MySQL：保留原有 ID 和引用关系。完整库恢复采用源结构导入后按需升级，不盲目把最新初始化 SQL 与旧完整 dump 叠加；已有目标业务数据需要另外设计合并。
- 结构升级：按实际缺失情况核对 `20260714`、`20260715`、`20260813` 三份迁移，分别处理同步幂等、文件授权和有效资料唯一约束；遇到历史批次、越权引用或重复组先核查。
- 文件：`file_info.storage_path` 保存绝对路径，必须建立“文件 ID → 旧路径 → `/data/uploads/<真实相对路径>`”映射并核验摘要。只改环境变量不够；保留真实存储名、大小、MD5 和引用关系，不用展示文件名重新命名。
- Redis：来源须为项目专用实例；本地 RDB 包含全实例，生产使用 DB 0 和 AOF。独立恢复时核对 DB、版本、TTL，加载 RDB 后受控建立 AOF并验证重启，不能直接往已有 AOF 卷塞 RDB。
- 切换：检查登录、审核预览、搜索、详情、下载摘要和重启持久化；旧、新数据集不能同时接收业务写入。

本地备份脚本不会自动完成 Windows 路径转换或 Linux 生产恢复。详见[本地数据保护](LOCAL-DATA-PROTECTION.md)、[数据库变更记录](database/database-change-log.md)和[生产恢复手册](DEPLOY-08-RUNBOOK.md)。

## Q12：如何理解数据迁移过程中备份的作用

**精简解答：备份是迁移前可恢复、可核对的数据基准，也是本次逻辑迁移的数据来源。**

| 作用 | 具体用途 |
| --- | --- |
| 搬迁来源 | 从导出与归档恢复新环境 |
| 验收基准 | 对比记录、文件清单和摘要，发现遗漏和损坏 |
| 失败恢复点 | 导入或路径转换失败后，能在全新目标重新恢复 |
| 演练材料 | 在副本验证方法，保留源数据 |

原始备份保留不变，在副本上转换。MySQL、Redis、上传目录须对应同一停写切点；单独恢复数据库可能无法恢复完整业务。SHA-256 检测意外变化，不单独证明来源可信；来源认证需要独立可信签名等机制。

备份只覆盖其切点。新环境接收写入后，直接恢复旧备份会遗漏新增数据；回退前先停写、保存新数据并规划处理。备份生成成功不等于恢复成功，必须实际恢复并验证业务。

## Q13：SSH连接如何配置

**精简解答：Windows 保存私钥，Ubuntu 目标账号配置公钥，客户端指定地址、端口、账号和密钥连接。**

1. Windows 用 `ssh -V` 确认客户端；已有可用私钥直接使用，不覆盖或重新生成。
2. 无密钥时生成独立 Ed25519 密钥，可设置保护口令；私钥留在本机，`.pub` 才是公钥。
3. 通过已有连接或云控制台，将公钥追加到目标运维账号的 `~/.ssh/authorized_keys`；目录权限 `700`，文件 `600`，归属该账号，不覆盖已有授权。
4. 云安全组和主机防火墙允许当前出口 IP 访问实际 SSH 端口；首次连接通过可信渠道核对服务器指纹。
5. Windows 编辑 `%USERPROFILE%\.ssh\config`，配置别名，再新开连接验证登录身份及所需 sudo 权限。

```sshconfig
# 示例配置；替换服务器地址、端口、运维账号和本地私钥路径。
Host campus-prod
    HostName <服务器公网IP>
    User <普通运维账号>
    Port 22
    IdentityFile ~/.ssh/campusshare_ed25519
    IdentitiesOnly yes
```

配置后使用 `ssh campus-prod` 登录，`scp <本地文件> campus-prod:~/` 上传。客户端别名不要求修改服务器；仅追加公钥通常不需要重启 SSH。

超时先查网络、端口和防火墙；`Permission denied (publickey)` 先查账号、密钥、授权路径和权限。若修改服务端策略，保留旧会话、先做 `sshd -t` 语法检查，再验证新连接；语法正确不等于账号一定能登录。Ubuntu 24.04 还可能由 `ssh.socket` 监听，不能仅凭 `ssh.service` 暂时 inactive 判断服务不可用。

## Q14：公钥和私钥的作用

**精简解答：在 SSH 用户认证中，私钥生成签名，公钥验证签名，用于证明连接方持有对应私钥。**

| 密钥 | 保存位置 | 作用 |
| --- | --- | --- |
| 用户私钥 | 本机 | 对当前连接的认证数据签名，必须保密，不传给服务器 |
| 用户公钥 | 服务器目标账号的 `authorized_keys` | 验证签名，可以提供给需要授权的服务器 |

公钥认证证明身份，Linux 账号权限决定登录后能做什么。私钥保护口令用于本地解锁私钥；后续 SSH 通信通常使用协商出的对称会话密钥加密。

首次连接看到的主机指纹，属于服务器自己的另一对密钥，帮助客户端确认服务器身份；它与授权用户登录的公钥不同。

## 当前状态与执行边界

- 历史记录显示服务器部署、DNS、HTTPS 与续期已完成；本次对话没有重新连接生产核验。
- `DEPLOY-08` 仍为 `IN_PROGRESS`，完整生产业务、真实联合备份、独立恢复和上一镜像业务兼容尚未完成验收。脚本资产和预检通过不能代替结论。
- 生产备份脚本仍硬编码要求旧 `deploy` 分支，需要单独适配并验证后才能符合当前分支约定；本文未修复该问题。
- 本地数据保护脚本有模拟验证记录，真实联合恢复和跨系统自动迁移不能描述成已经通过。
- 当前只支持一个后端实例和固定容器上传路径 `/data/uploads`；生产不执行 `docker compose down -v`。
- 本文是用户明确要求创建的独立复习资料，不改变 `TECHNICAL_QA_LOG.md` 的 `DISABLED` 状态，也不向该日志追加内容。

## 详细资料入口

- [生产部署手册](../deploy/README.md)：参数、镜像交付、启动、HTTPS 和续期命令。
- [部署交接](DEPLOYMENT_HANDOFF.md)：部署架构、资源与历史阶段证据。
- [生产验收与恢复](DEPLOY-08-RUNBOOK.md)：未完成验收、联合备份、签名和独立恢复要求。
- [本地数据保护](LOCAL-DATA-PROTECTION.md)：Windows 联合备份与隔离恢复边界。
- [数据库变更记录](database/database-change-log.md)：结构迁移顺序与兼容性。
- [OpenSSH 客户端配置](https://man.openbsd.org/ssh_config)、[Ubuntu OpenSSH](https://ubuntu.com/server/docs/how-to/security/openssh-server/)：SSH 参数与服务端配置。
