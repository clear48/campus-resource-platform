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

运行器把本轮 `.jtl/.log/.console/.meta.json/.summary.json` 和 `active-jmeter.json/progress.json` 全部写到 `context.evidenceDir`，拒绝覆盖同名证据。后两项是当前状态文件，外部资源采样器应从此目录读取PID，不能假设文件位于context的父目录。summary按真实请求label输出样本数、成功/失败、成功/尝试RPS、分位延迟与HTTP码；HTTP200但断言失败仍计失败，0样本视为无效。任何失败样本、非零进程退出、超时或保护停止都会使运行器非零退出，不能跳过失败后继续升压。

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

## 3. 静态校验与冒烟

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
