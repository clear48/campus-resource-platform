"""生成无插件依赖的原生 HTTP Sampler 压测计划，不将凭据嵌入 JMX。"""

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


PREPROCESSOR = r'''
import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput
import java.net.URLEncoder

// 预处理失败时禁止沿用上一次迭代的场景变量或有效 URL，避免把健康响应误算成功。
vars.remove('chosenScenario')
sampler.setPath('/__crp_invalid_configuration')

// 所有线程共用只读清单；加载与派生集合只执行一次，不在每个请求重读文件。
def manifest = props.get('__crpManifest')
if (manifest == null) {
    synchronized (props) {
        manifest = props.get('__crpManifest')
        if (manifest == null) {
            def manifestFile = new File(props.getProperty('manifest', ''))
            if (!manifestFile.isFile()) throw new IllegalArgumentException('缺少有效 manifest 文件')
            // LazyMap会在首次get时再填充内部Map；共享前必须递归解析为普通Map/List。
            manifest = new JsonSlurperClassic().parseText(manifestFile.getText('UTF-8'))
            if (manifest.status != 'COMPLETE' || !manifest.approvedIds || !manifest.accounts) {
                throw new IllegalArgumentException('manifest 尚未完成或数据池为空')
            }
            def target = new URI(manifest.apiBase)
            if (!(target.host in ['127.0.0.1', 'localhost', '::1']) || target.scheme != 'http') {
                throw new IllegalArgumentException('只允许本地 HTTP 测试目标')
            }
            manifest._basePath = target.path.replaceAll('/+$', '')
            manifest._host = target.host
            manifest._port = target.port > 0 ? target.port.toString() : '80'
            if (!(manifest.categories instanceof List) || manifest.categories.isEmpty()) {
                throw new IllegalArgumentException('分类数据池为空或格式不合法')
            }
            // 在同步发布前校验并建立独立的不可变ID列表，HTTP组URL不再访问可惰性构建的分类对象。
            def categoryIds = manifest.categories.collect { row ->
                def rawId = row?.categoryId
                if (!(rawId instanceof Number)) throw new IllegalArgumentException('分类ID必须为正整数')
                long id = new BigDecimal(rawId.toString()).longValueExact()
                if (id < 1) throw new IllegalArgumentException('分类ID必须为正整数')
                id.toString()
            }
            manifest._categoryIds = Collections.unmodifiableList(categoryIds)
            manifest._approvedSet = manifest.approvedIds.collect { it.toString() }.toSet()
            manifest._owned = manifest.resources.findAll { it.status == 1 }.groupBy { it.userId.toString() }
            props.put('__crpManifest', manifest)
        }
    }
}
// 每线程固定自己的账号；只有专门的幂等竞争测试才允许多个线程共用账号。
int threadIndex = ctx.getThreadNum()
String scenario = props.getProperty('scenario', 'search')
if (!(scenario in ['search', 'detail', 'rank', 'keyword', 'mixed', 'auth', 'login', 'favorite'])) {
    throw new IllegalArgumentException('不支持的 scenario')
}
if (scenario in ['auth', 'login', 'favorite'] && threadIndex >= manifest.accounts.size()) {
    throw new IllegalArgumentException('账号数不足，拒绝跨线程复用写用户')
}
def account = manifest.accounts[threadIndex % manifest.accounts.size()]
int sequence = Integer.parseInt(vars.get('sequence') ?: '0')
vars.put('sequence', (sequence + 1).toString())
vars.putObject('expectedAccount', account)
vars.putObject('approvedSet', manifest._approvedSet)
int resourceIndex = Math.floorMod(threadIndex * 37 + sequence, manifest.approvedIds.size())
String resourceId = manifest.approvedIds[resourceIndex].toString()
String chosen = scenario
if (scenario == 'mixed') {
    // 100 次循环精确形成 50/35/10/5 请求分布，线程之间偏移避免同一时刻只打一个接口。
    int bucket = Math.floorMod(sequence + threadIndex * 17, 100)
    chosen = bucket < 50 ? 'search' : bucket < 85 ? 'detail' : bucket < 95 ? 'rank' : 'keyword'
}
String path
String method = 'GET'
String body = ''
String authorization = ''
String label = chosen
switch (chosen) {
    case 'search':
        String variant = props.getProperty('searchVariant', 'default')
        String keyword = variant == 'nomatch' ? 'definitely-no-synthetic-match-98765' : variant == 'empty' ? '' : '合成'
        String sort = variant in ['downloadCount', 'favoriteCount', 'hotScore', 'createdAt'] ? variant : 'createdAt'
        int page = variant == 'deep' ? Math.max(1, manifest.approvedIds.size().intdiv(10) - 1) : 1
        path = '/search/resources?keyword=' + URLEncoder.encode(keyword, 'UTF-8') + '&pageNo=' + page + '&pageSize=10&sortBy=' + sort + '&order=desc'
        if (variant == 'category') {
            String categoryId = manifest._categoryIds[threadIndex % manifest._categoryIds.size()]
            if (categoryId == null || !(categoryId ==~ /[1-9][0-9]*/)) {
                throw new IllegalArgumentException('分类ID未安全初始化，拒绝生成请求URL')
            }
            path += '&categoryId=' + categoryId
        }
        vars.put('searchVariant', variant)
        break
    case 'detail':
        path = '/resources/' + resourceId
        vars.put('expectedResourceId', resourceId)
        break
    case 'rank':
        path = '/rankings/resources/hot?period=weekly&limit=10'
        break
    case 'keyword':
        path = '/rankings/search-keywords/hot?period=daily&limit=10'
        break
    case 'auth':
        path = '/users/me'
        authorization = 'Bearer ' + account.token
        break
    case 'login':
        path = '/auth/login'
        method = 'POST'
        // 登录密码从仓库外 context 中读取，不放命令行、结果文件或 JMX。
        def loginPassword = props.get('__crpLoginPassword')
        if (loginPassword == null) {
            synchronized (props) {
                loginPassword = props.get('__crpLoginPassword')
                if (loginPassword == null) {
                    def contextFile = new File(props.getProperty('context', ''))
                    if (!contextFile.isFile()) throw new IllegalArgumentException('登录场景需要 context 文件')
                    loginPassword = new JsonSlurperClassic().parseText(contextFile.getText('UTF-8')).userPassword
                    if (!loginPassword) throw new IllegalArgumentException('登录场景缺少密码')
                    props.put('__crpLoginPassword', loginPassword)
                }
            }
        }
        body = JsonOutput.toJson([username: account.username, password: loginPassword])
        break
    case 'favorite':
        def owned = manifest._owned[account.userId.toString()]
        if (!owned) throw new IllegalArgumentException('当前账号没有已通过资料')
        resourceId = owned[0].resourceId.toString()
        path = '/resources/' + resourceId + '/favorites'
        method = sequence % 2 == 0 ? 'POST' : 'DELETE'
        label = method == 'POST' ? 'favorite.add' : 'favorite.remove'
        authorization = 'Bearer ' + account.token
        vars.put('expectedResourceId', resourceId)
        vars.put('expectedFavorited', (method == 'POST').toString())
        break
}
vars.put('chosenScenario', chosen)
vars.put('authorization', authorization)
vars.put('requestBody', body)
sampler.setName(label)
sampler.setDomain(manifest._host)
sampler.setPort(Integer.parseInt(manifest._port))
sampler.setProtocol('http')
sampler.setMethod(method)
sampler.setPath(manifest._basePath + path)
'''


ASSERTION = r'''
import groovy.json.JsonSlurper

// 普通成功同时校验 HTTP、业务 code 与真实返回字段；失败只输出有限的非敏感原因。
try {
    String scenario = vars.get('chosenScenario')
    assert scenario in ['search', 'detail', 'rank', 'keyword', 'auth', 'login', 'favorite'] : '场景初始化未完成'
    assert prev.getResponseCode() == '200' : 'HTTP 状态不为 200'
    def response = new JsonSlurper().parseText(prev.getResponseDataAsString())
    assert response.code == 0 : '业务 code 非零'
    def data = response.data
    def approved = vars.getObject('approvedSet')
    switch (scenario) {
        case 'search':
            assert data.records instanceof List : '缺少 records 列表'
            assert data.records.every { approved.contains(it.resourceId.toString()) } : '搜索泄露非公开资料'
            if (vars.get('searchVariant') == 'nomatch') assert data.total == 0 : '无命中关键词返回了数据'
            else assert data.records.size() > 0 : '预期有数据的搜索为空'
            break
        case 'detail':
            assert data.resourceId.toString() == vars.get('expectedResourceId') : '详情 ID 不符'
            assert data.status == 1 : '详情不是公开资料'
            assert data.favorited == null : '游客详情混入用户收藏状态'
            break
        case 'rank':
            assert data instanceof List && data.size() <= 10 : '榜单结构/大小不符'
            assert data.size() > 0 : '预期有成员的榜单为空'
            assert data.every { approved.contains(it.resourceId.toString()) } : '榜单泄露非公开资料'
            data.eachWithIndex { row, i -> assert row.rank == i + 1 : '榜单名次不连续' }
            break
        case 'keyword':
            assert data instanceof List && data.size() <= 10 : '热词榜结构/大小不符'
            assert data.size() > 0 : '正常热词容量场景必须预热，不能把空榜算成功'
            assert data.any { it.keyword == '合成' } : '缺少已预热合成关键词'
            data.eachWithIndex { row, i ->
                assert row.rank == i + 1 : '热词名次不连续'
                assert row.keyword instanceof String && row.keyword.trim().size() > 0 : '热词为空'
                assert row.searchCount instanceof Number && row.searchCount >= 1 : '热词计数不合法'
            }
            break
        case 'auth':
            assert data.userId.toString() == vars.getObject('expectedAccount').userId.toString() : '用户身份串线'
            break
        case 'login':
            assert data.accessToken instanceof String && data.accessToken.size() > 20 : '登录缺少 Token'
            assert data.user.userId.toString() == vars.getObject('expectedAccount').userId.toString() : '登录用户不符'
            break
        case 'favorite':
            assert data.resourceId.toString() == vars.get('expectedResourceId') : '收藏 ID 不符'
            assert data.favorited.toString() == vars.get('expectedFavorited') : '收藏状态不符'
            break
    }
} catch (Throwable failure) {
    AssertionResult.setFailure(true)
    // 不记录 responseData、headers 或异常对象中的完整表达式值，避免 Token 泄露。
    AssertionResult.setFailureMessage('HTTP/JSON/业务字段断言失败：' + failure.getClass().getSimpleName())
}
'''


def property_node(parent, name, value, kind="stringProp"):
    ET.SubElement(parent, kind, {"name": name}).text = str(value)


def script_element(parent, tag, gui, name, script):
    element = ET.SubElement(parent, tag, {"guiclass": gui, "testclass": tag,
                                         "testname": name, "enabled": "true"})
    for key, value in (("cacheKey", "true"), ("scriptLanguage", "groovy"),
                       ("parameters", ""), ("filename", ""), ("script", script)):
        property_node(element, key, value)
    return element


def generate(output):
    """原生 ThreadGroup + HTTP Sampler，不需要额外 JMeter 插件。"""
    root = ET.Element("jmeterTestPlan", {"version": "1.2", "properties": "5.0", "jmeter": "5.6.3"})
    tree = ET.SubElement(root, "hashTree")
    plan = ET.SubElement(tree, "TestPlan", {"guiclass": "TestPlanGui", "testclass": "TestPlan",
                                           "testname": "本地 API 压测", "enabled": "true"})
    property_node(plan, "TestPlan.functional_mode", "false", "boolProp")
    variables = ET.SubElement(plan, "elementProp", {"name": "TestPlan.user_defined_variables",
                                                   "elementType": "Arguments",
                                                   "guiclass": "ArgumentsPanel", "testclass": "Arguments"})
    ET.SubElement(variables, "collectionProp", {"name": "Arguments.arguments"})
    plan_tree = ET.SubElement(tree, "hashTree")
    group = ET.SubElement(plan_tree, "ThreadGroup", {"guiclass": "ThreadGroupGui", "testclass": "ThreadGroup",
                                                     "testname": "固定线程闭合负载", "enabled": "true"})
    property_node(group, "ThreadGroup.on_sample_error", "continue")
    loop = ET.SubElement(group, "elementProp", {"name": "ThreadGroup.main_controller", "elementType": "LoopController",
                                               "guiclass": "LoopControlPanel", "testclass": "LoopController"})
    property_node(loop, "LoopController.continue_forever", "false", "boolProp")
    property_node(loop, "LoopController.loops", "-1")
    for key, value in (("num_threads", "${__P(threads,1)}"), ("ramp_time", "${__P(ramp,0)}"),
                       ("duration", "${__P(duration,60)}"), ("delay", "0")):
        property_node(group, "ThreadGroup." + key, value)
    property_node(group, "ThreadGroup.scheduler", "true", "boolProp")
    group_tree = ET.SubElement(plan_tree, "hashTree")
    sampler = ET.SubElement(group_tree, "HTTPSamplerProxy", {"guiclass": "HttpTestSampleGui",
                                                              "testclass": "HTTPSamplerProxy", "testname": "request",
                                                              "enabled": "true"})
    arguments = ET.SubElement(sampler, "elementProp", {"name": "HTTPsampler.Arguments", "elementType": "Arguments"})
    argument_collection = ET.SubElement(arguments, "collectionProp", {"name": "Arguments.arguments"})
    argument = ET.SubElement(argument_collection, "elementProp", {"name": "", "elementType": "HTTPArgument"})
    property_node(argument, "HTTPArgument.always_encode", "false", "boolProp")
    property_node(argument, "Argument.value", "${requestBody}")
    property_node(argument, "Argument.metadata", "=")
    for key, value in (("domain", "127.0.0.1"), ("port", "18080"), ("protocol", "http"),
                       ("method", "GET"), ("path", "/__crp_invalid_configuration"),
                       ("implementation", "HttpClient4"), ("connect_timeout", "3000"),
                       ("response_timeout", "10000"), ("contentEncoding", "UTF-8")):
        property_node(sampler, "HTTPSampler." + key, value)
    for key, value in (("follow_redirects", "false"), ("auto_redirects", "false"),
                       ("use_keepalive", "true"), ("postBodyRaw", "true")):
        property_node(sampler, "HTTPSampler." + key, value, "boolProp")
    sampler_tree = ET.SubElement(group_tree, "hashTree")
    headers = ET.SubElement(sampler_tree, "HeaderManager", {"guiclass": "HeaderPanel", "testclass": "HeaderManager",
                                                            "testname": "请求头", "enabled": "true"})
    header_collection = ET.SubElement(headers, "collectionProp", {"name": "HeaderManager.headers"})
    for key, value in (("Content-Type", "application/json"), ("Accept", "application/json"),
                       ("Authorization", "${authorization}")):
        item = ET.SubElement(header_collection, "elementProp", {"name": "", "elementType": "Header"})
        property_node(item, "Header.name", key)
        property_node(item, "Header.value", value)
    ET.SubElement(sampler_tree, "hashTree")
    script_element(sampler_tree, "JSR223PreProcessor", "TestBeanGUI", "场景关联与固定用户", PREPROCESSOR)
    ET.SubElement(sampler_tree, "hashTree")
    script_element(sampler_tree, "JSR223Assertion", "TestBeanGUI", "HTTP 与业务断言", ASSERTION)
    ET.SubElement(sampler_tree, "hashTree")
    # 默认容量场景无思考时间；业务混合可显式传 -JthinkMs=1000，并记录该条件。
    timer = ET.SubElement(sampler_tree, "ConstantTimer", {"guiclass": "ConstantTimerGui", "testclass": "ConstantTimer",
                                                         "testname": "可配置思考时间", "enabled": "true"})
    property_node(timer, "ConstantTimer.delay", "${__P(thinkMs,0)}")
    ET.SubElement(sampler_tree, "hashTree")
    destination = Path(output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(root, space="  ")
    ET.ElementTree(root).write(destination, encoding="utf-8", xml_declaration=True)
    print("已生成原生 HTTP JMeter 计划：" + str(destination))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    generate(parser.parse_args().output)
