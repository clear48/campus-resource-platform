# 本地压测脚本

脚本使用 Python 标准库与 JMeter 5.6.3 自带组件，不修改应用依赖。运行前按 [本地压测设计](../../docs/12-local-load-test-plan.md) 准备独立 MySQL、Redis、上传目录和打包后端。所有运行文件放仓库外。

## 1. 环境与合成数据

由环境准备流程在仓库外写入 `context.json`，字段为：

| 字段 | 含义 |
| --- | --- |
| `apiBase` | 本机 HTTP 地址，含 `/api/v1`，如 `http://127.0.0.1:18080/api/v1` |
| `runId`、`evidenceDir` | 本批次标识和仓库外证据目录 |
| `mysqlExecutable` | 本机 `mysql.exe` 绝对路径 |
| `mysqlHost`、`mysqlPort`、`mysqlUser`、`mysqlPassword` | 专用本机实例连接参数；密码只留本地 |
| `mysqlDatabase` | 必须符合 `campus_loadtest_` 前缀的库名 |
| `userPassword` | 仅合成账号使用的随机测试密码，满足 API 的 8～50 字符限制 |

脚本不创建数据库或表，要求事先从 `sql/init.sql` 复制并替换两处库名后导入到全新专用库。9张业务表必须全部为空。先用SQL插入五个带随机标记的启用分类，通过分类GET核对API与SQL的ID/名称一致，之后才允许注册等API写入；另有提升专用管理员角色使用SQL。用户、上传、资料创建和审核全部走真实API。

`evidenceDir` 必须位于仓库外，路径链拒绝符号链接/ReparsePoint；新批次的 `synthetic-files` 目录不得已存在，源文件采用独占创建，避免覆盖其他批次数据。

在仓库根目录执行：

```powershell
python .\tests\performance\seed.py --context '<仓库外context.json>' --resources 1000 --users 100
```

合成文件正文包括 runId 和序号，每个内容独立；前三份文件分别为 100 KiB、1 MiB、10 MiB，其余为 2 KiB，全部为合法 UTF-8 文本。1000 条资料分为通过800、待审100、拒绝50、下架50。该规模用于查询数据量实验，上传大小实验另列。

清单位于 `<evidenceDir>/manifest.json`，包含 `apiBase,mysqlTarget,runId,status,accounts,admin,categories,resources,approvedIds,keywords,statusCounts`：

- `accounts` / `admin`：`userId,username,token`。真实密码只在 context，Token 只在清单，不进 Git。
- `resources`：`resourceId,fileId,userId,status,md5,size,path,title`；`path` 是合成源文件路径，不是服务端物理路径。
- `approvedIds`：所有已通过资料 ID，为搜索/榜单断言白名单。
- `status`：准备阶段为 `IN_PROGRESS`，全部完成才为 `COMPLETE`。

相同 runId、规模且已完成的清单，只有API地址、SQL目标及实时SQL/API分类映射均一致时才复用，不发送写请求；旧清单缺少目标绑定时也会拒绝复用，但不修改旧清单或数据。未完成或不同规模清单拒绝自动重跑。写请求超时可能已经在服务端生效，脚本不盲目重试，请保留现场并换新独立库/批次，或先人工核对。

## 2. JMeter 原生 HTTP 计划

先生成 JMX 到仓库外：

```powershell
python .\tests\performance\generate_plan.py --output '<证据目录>\generic.jmx'
```

再运行 JMeter，变量路径替换为真实值：

```powershell
# duration 是整组持续秒数，包含 ramp；报告应按时间戳剔除 ramp。
& '<JMeter目录>\bin\jmeter.bat' -n `
  -t '<证据目录>\generic.jmx' `
  -Jmanifest='<证据目录>\manifest.json' `
  -Jscenario=search -Jthreads=1 -Jduration=30 -Jramp=0 `
  -l '<证据目录>\search-smoke.jtl' -j '<证据目录>\search-smoke.log'
```

每轮使用全新 JTL/日志名；测量结束后再生成 HTML，避免同机报告生成影响结果。JMX 不包含 GUI 监听器或密码，JTL 使用默认 CSV，禁止打开响应体/请求头保存选项。

推荐使用可复用运行器管理命名、资源保护和统计：

```powershell
python .\tests\performance\run.py `
  --context '<仓库外context.json>' `
  --jmeter-home '<JMeter目录>' --java '<java.exe完整路径>' `
  --name search-c10-r1 --scenario search --threads 10 `
  --duration 330 --ramp 30 --exclude 30
```

`run.py` 没有固定机器路径，`--java` 可省略而使用PATH中的java；默认计划为 `<evidenceDir>/generic.jmx`，不存在时自动生成，也可用 `--plan` 显式指定。`--exclude` 必须不小于 `--ramp` 且小于总时长；统计以首个HTTP样本开始时间为基准剔除指定秒数，因此保守排除相应启动/升压区间。

运行器发压前要求manifest的runId、apiBase、mysqlTarget与context严格一致，并实时核对SQL/API分类绑定；旧清单缺少目标绑定也拒绝发压，必须先独立核实目标后补充，不能仅凭COMPLETE标记放行。

运行器把本轮 `.jtl/.log/.console/.meta.json/.summary.json` 和 `active-jmeter.json/progress.json` 全部写到 `context.evidenceDir`，拒绝覆盖同名证据。后两项是当前状态文件，外部资源采样器应从此目录读取PID，不能假设文件位于context的父目录。summary按真实请求label输出样本数、成功/失败、成功/尝试RPS、分位延迟与HTTP码；各label的RPS均使用整体共同稳态窗口分母，另列activeWindowSeconds描述该label活跃跨度，因此各label成功RPS之和与overall一致（允许四舍五入误差）。HTTP200但断言失败仍计失败，0样本视为无效。任何失败样本、非零进程退出、超时或保护停止都会使运行器非零退出，不能跳过失败后继续升压。

资源保护默认磁盘至少10GiB、可用物理内存比例至少10%；内存连续一分钟低于门槛停止，进程超过 `duration+120秒` 停止。可通过 `--min-disk-gib` 和 `--memory-threshold` 在运行前设定并记录条件。进程/CPU、GC、SQL、Redis等资源时间线仍由独立采样器收集，运行器并不替代完整性能分析。

| 属性 | 支持值/说明 |
| --- | --- |
| `scenario` | `search/detail/rank/keyword/mixed/auth/login/favorite` |
| `threads`、`duration`、`ramp` | 默认1线程、60秒、0秒升压；线程组为闭合负载，不能当成固定RPS |
| `manifest` | 必须指向已完成的仓库外清单 |
| `context` | 仅 `login` 场景必需，密码从该文件加载而不写入命令行 |
| `thinkMs` | 默认0；业务混合可设1000等思考时间，需记录测量条件 |
| `searchVariant` | `default/nomatch/empty/category/deep/createdAt/downloadCount/favoriteCount/hotScore` |

场景说明：

- `search` 轮询查询公开资料；`deep` 查询实际有数据的接近末页，`nomatch` 明确断言空结果，其余断言非空和ID白名单。
- `detail` 在线程间错位轮询已通过资料，校验ID、status=1及游客favorited为空；冷热点实验须先有选择地删除明确缓存Key。
- `rank` 查询周资料榜，校验已通过白名单、数量和连续名次；`keyword` 查询日热词榜，要求先搜索“合成”预热，正常容量断言非空、含该词、名次连续、计数为正。空榜只做独立正确性专项，不能算正常榜单容量。
- `mixed` 每100次循环按搜索50%、详情35%、资料榜10%、热词5%分配，不同线程错位；实际比例仍以JTL核验。
- `auth` 每线程固定自己的账号，调用 `/users/me` 并校验用户ID；`login` 单独循环登录，验证Token与用户。
- `favorite` 每线程固定自己的账号及其首份已通过资料，交替POST收藏和DELETE取消，分别标为 `favorite.add`、`favorite.remove`；不混入重复取消的404专项。线程数超过账号数时拒绝继续，停止时可能留下最后一次有效收藏，必须按数据库聚合对账，不能要求最终全零。

所有场景均使用原生 `HTTPSamplerProxy`、HTTPClient4、Keep-Alive、3秒连接超时、10秒响应超时，同时断言HTTP200、业务code=0和场景字段。不使用父事务样本，吞吐不会重复统计。JMeter Groovy预处理仅在首次加载清单，随后共享只读集合；账号固定到线程，单次请求失败不重试。

上传、下载限流/票据/完整文件传输、审核竞争与数据对账不由这份通用JMX实现，必须按设计专项执行，不能把通用JMX运行完成视为全部场景通过。

## 3. 并发、上传、下载与最终对账专项

专项只在容量场景停止后运行，避免新增公开资料使正在运行的JMeter白名单过时。依次执行：

```powershell
python .\tests\performance\specials.py --context '<仓库外context.json>' --mode correctness
python .\tests\performance\specials.py --context '<仓库外context.json>' --mode upload
python .\tests\performance\specials.py --context '<仓库外context.json>' --mode download
python .\tests\performance\specials.py --context '<仓库外context.json>' --mode verify
```

`correctness` 支持 `--case all|favorite|resource`，默认all。已发现某个独立用例失败时保留其失败，不放宽断言；先确认SQL计数一致，再单独验证其他用例：

```powershell
python .\tests\performance\specials.py --context '<仓库外context.json>' --mode correctness --case favorite
python .\tests\performance\specials.py --context '<仓库外context.json>' --mode correctness --case resource
```

两者分别写 `correctness-favorite.special.json` 与 `correctness-resource.special.json`，默认all仍写 `correctness.special.json`，互不覆盖。favorite仅验证收藏/取消，resource仅新增文件、竞争创建资料及竞争审批；各用例开始及资料用例批次结束均核对收藏SQL聚合。resource通过不能用于声称favorite通过，失败快照和lastAttempt始终保留在当前独立用例文件中。

专项开始先核对清单API地址以及SQL/API分类映射。兼容首轮缺少mysqlTarget的清单，但不能跳过实时绑定。每种模式在 `<evidenceDir>/<mode>.special.json` 保存聚合结果和进行中状态；已有结果拒绝自动重跑/覆盖。Token、票据、原始响应不进入结果JSON。异常、超时或断言失败立即停止，写请求不重试，必须人工处理结果不确定项。

关键并发断言前会先持久化 `lastAttempt`，记录操作、并发、批次、HTTP状态/业务码/duplicateIgnored计数；不确定响应也保留计数。并发正确性失败时，另查SQL的用户资料有效收藏数、资料favorite_count、有效收藏聚合与全库计数不一致数，保留实际失败状态而不放宽断言。下载429原因只归类为用户阈值/IP阈值/限流服务不可用/其他，不保存原始消息或票据。

- `correctness`：2/5/10/20并发，各3批，屏障同时起跑。验证收藏只有一次真实新增、取消只有一次成功、同用户同文件有效资料只有一次创建、同资料审批只有一次状态流转及流水；对账收藏聚合。新文件/资料追加到清单，保留原始索引和 `baseResourceCount`，因此后续JMeter需重新加载清单。
- `upload`：先验证已授权预检命中、未授权false/null、新文件首次miss与后续NOT_FOUND负缓存（TTL不超过300秒），上传后正缓存覆盖负哨兵且预检命中；新增文件记入清单。同内容2/5/10/20账号并发后核对唯一文件、各账号授权、物理字节与MD5；新内容分别100KiB/1MiB/10MiB、1/2/5/10线程，每档20请求。文件均提前独占生成，计时只涵盖HTTP窗口；持久源文件和服务端副本计划合计小于2GiB。磁盘须至少剩10GiB加本批待新增字节。这是Python urllib无连接池/keepalive的短批实验，P95/P99为低样本参考，不可冒充JMeter五分钟稳态容量。
- `download`：12次低速完整下载，每次全局间隔5秒，3对账号/资料轮换；验证首次计数和10分钟去重、真实字节/MD5/附件头。验证一次票据并发重放、过期与换账号；分开验证用户11次/IP31次阈值及拒绝消息；1/5/10/20预签票后并发取流。各限流轮次之间等62秒，所有签票均纳入counted汇总。初始基线与最终对账均只读检查delta/current/所有UUID批次，要求空状态跨60秒稳定，再读取MySQL计数；不能凭一次空快照跳过初始排空。正常约需10～15分钟，期间禁止其他下载流量；文件GET耗时是读完整内容，未单独测首字节。
- `verify`：收藏聚合/关系唯一、有效资料防重、文件MD5+size唯一、所有有效物理文件大小和摘要、清单文件存在/MD5/大小、上传用户授权、资料数量/状态/file_id/uploader_id与审核链；核对 `.part` 与多余物理文件均为0。验证待审/拒绝/下架详情400/40901、不存在404、无Token个人接口401、批准详情公开。停压后仅DEL一个明确的详情缓存Key，20并发GET验证ID/状态全部正确，回填TTL在1～2100秒；不测量或声称SQL只执行一次。瞬时 `.readiness-*.tmp` 探针不参与文件集合比较，最终停止请求后仍须检查目录。

`upload/download/verify` 需要context另含专用本地 `redisPort`（可选redisHost/redisDatabase/redisPassword），`verify` 需要 `uploadDir` 指向本轮独立上传目录。Redis对账读取使用只读命令与SELECT/AUTH；唯一DEL是verify明确指定的单个详情缓存Key，不清空实例或删除锁/票据/统计键。容量结束后应先核对清单仍有效、后台任务开启和依赖正常，再启动专项。

## 4. 静态校验与冒烟

在不生成仓库内缓存文件的情况下，可验证 Python 语法和 JMX 结构：

```powershell
@'
from pathlib import Path
import xml.etree.ElementTree as ET
for p in Path('tests/performance').glob('*.py'):
    compile(p.read_text(encoding='utf-8'), str(p), 'exec')
ET.parse(r'<证据目录>\generic.jmx')
print('Python / JMX 静态校验通过')
'@ | python -
```

静态校验不能代替真实 HTTP 冒烟。至少先以1线程分别运行查询、鉴权和收藏，再核对JTL没有断言失败、日志没有脚本异常，才逐档升压；JMeter进程退出码0本身不表示请求全部成功。

隔离与统计单测可执行 `python -m unittest discover -s tests/performance -p test_harness.py -v`，使用临时文件和模拟API/SQL，覆盖远程目标、日常库、仓库内/链接证据目录、非空文件表、HTTP/SQL错绑、源文件覆盖、完成清单目标切换、HTTP200断言失败计数、升压排除和0样本无效。缺失manifest还应使用真实JMeter负向冒烟，要求非零样本全部失败、URL为无效配置路径，不能以0样本或health成功误报通过。

全部工具单测：`python -m unittest discover -s tests/performance -p "test_*.py" -v`。专项单测只模拟协议/请求，不连接真实HTTP、SQL、Redis；它们不能替代真实专项结果。
