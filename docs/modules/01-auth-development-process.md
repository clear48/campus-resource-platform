# 用户认证模块开发记录

## 0. 文档范围

本文档记录当前已经落地的“用户认证模块”，对应代码包路径为 `com.john.campus`。

当前模块覆盖注册、登录、退出登录、当前用户查询、JWT 鉴权拦截、密码加密、双 Token 自动刷新、Redis 可撤销会话和旧 Token 黑名单。登录限流、验证码、管理员用户管理、注解式权限控制等能力属于后续优化方向，本模块暂未涉及。

## 1. 模块目标

用户认证模块用于完成平台最基础的身份能力：用户注册、用户登录、JWT 签发、当前用户识别、退出登录、刷新凭据轮换、Redis 会话归属及 Token 黑名单校验。

该模块是后续资料上传、下载、收藏、管理员审核等模块的前置基础，后续业务接口可以通过 `UserContextHolder` 获取当前登录用户信息。

## 2. 本次实现功能

- 用户注册：接收用户名、密码、昵称、邮箱、手机号，使用 BCrypt 加密密码后写入 `user` 表。
- 用户登录：按用户名查询用户，校验 BCrypt 密码，校验用户状态，签发 JWT。
- 用户退出登录：优先用 Refresh 删除 sid 会话；旧有效 Bearer 路径删除 sid（若有）并写 jti 黑名单。
- 自动刷新：Access 默认 900 秒，40103 触发前端单飞刷新；opaque Refresh 固定七天，Lua CAS 轮换且不滑动 TTL。
- 当前用户查询：通过 JWT 拦截器解析当前用户 ID，再查询 `user` 表返回用户信息。
- JWT 鉴权拦截：解析 `Authorization: Bearer {token}`，提取 `userId`、`role`、`jti`、`sid` 并校验会话归属，写入 `UserContextHolder`。
- Redis 黑名单校验：请求进入受保护接口时，如果 `jti` 已存在于黑名单 Key，则返回 Token 已失效。
- 统一异常处理：业务异常、参数校验异常、Token 异常统一返回 `ApiResponse`。

本模块暂未涉及：

- 登录失败次数统计。
- 登录限流。
- 图形验证码或邮箱验证码。
- 全设备统一撤销与管理员用户管理。
- 管理员禁用用户接口。
- 用户资料修改接口。
- 注解式角色权限控制。
- 登录审计表或登录日志表。

## 3. 涉及接口

| 接口名称 | 方法 | 路径 | 是否登录 | 权限要求 | Controller 方法 |
| --- | --- | --- | --- | --- | --- |
| 用户注册 | `POST` | `/api/v1/auth/register` | 否 | 无 | `AuthController.register` |
| 用户登录 | `POST` | `/api/v1/auth/login` | 否 | 无 | `AuthController.login` |
| 刷新登录凭据 | `POST` | `/api/v1/auth/refresh` | Refresh 自行认证 | 持有有效当前 Refresh | `AuthController.refresh` |
| 用户退出登录 | `POST` | `/api/v1/auth/logout` | Refresh 自行认证或旧有效 Bearer | 无角色限制 | `AuthController.logout` |
| 获取当前用户 | `GET` | `/api/v1/users/me` | 是 | 学生或管理员 | `UserController.me` |

## 4. 涉及数据库表

### 4.1 `user` 用户表

本模块使用已有 `user` 表，没有新增表或新增索引。

涉及字段：

| 字段 | 使用场景 |
| --- | --- |
| `id` | 用户主键，JWT 中的 `userId` 来源 |
| `username` | 注册唯一性校验、登录查询 |
| `password_hash` | 保存 BCrypt 加密后的密码 |
| `nickname` | 注册和用户信息返回 |
| `email` | 注册和用户信息返回 |
| `phone` | 注册时保存手机号 |
| `role` | JWT 中的角色信息来源 |
| `status` | 登录和当前用户查询时校验账号是否正常 |
| `last_login_at` | 登录成功后更新最近登录时间 |
| `created_at` | 用户创建时间 |
| `updated_at` | 用户更新时间 |

涉及索引：

| 索引 | 使用场景 |
| --- | --- |
| `uk_user_username` | 注册防重复、登录按用户名查询 |
| `uk_user_email` | 数据库层防止邮箱重复 |
| `uk_user_phone` | 数据库层防止手机号重复 |

## 5. 涉及 Redis Key

### 5.1 Token 黑名单

| 项目 | 内容 |
| --- | --- |
| Key 模板 | `crp:auth:token:blacklist:{jti}` |
| 代码常量 | `RedisKeyConstants.TOKEN_BLACKLIST` |
| 数据结构 | String |
| Value | `logout` |
| TTL | JWT 剩余有效期 |
| 写入时机 | 无 body 的旧 Bearer 退出路径 |
| 读取时机 | `JwtAuthenticationInterceptor.preHandle` |
| 用途 | 用户退出登录后，让未过期 JWT 立即失效 |

本模块暂未实现 `crp:auth:user:token-version:{userId}`。

### 5.2 刷新会话

`crp:auth:session:{sid}` Hash 保存 userId/currentHash/used 摘要；Redis 仅存 SHA-256，无明文 Refresh。TTL 为登录起固定 604800 秒，轮换不滑动；2048 次轮换上限，超限撤销。创建、轮换、撤销由单 Key Lua 原子完成。详见 [生命周期指南](01-auth-token-lifecycle.md)。

### 5.3 Redis 与 MySQL 一致性

- Token 黑名单只用于补足 JWT 退出登录后的服务端失效能力，不回写 MySQL。
- 用户是否可用仍以 MySQL `user.status` 为准，登录、刷新和 `GET /api/v1/users/me` 都会校验用户状态；受保护接口未统一逐次查库。
- 黑名单 Key 的 TTL 使用 JWT 剩余有效期，避免 Token 过期后 Redis 仍长期保留无意义数据。
- 如果退出登录时 Redis 写入失败，当前代码会由 `GlobalExceptionHandler` 统一返回 `50001 SERVER_ERROR`。
- 如果后续实现管理员禁用用户后批量失效 Token，可增加 `crp:auth:user:token-version:{userId}`，当前模块暂未涉及。

## 6. 核心类与方法

### 6.1 代码落点

| 文件 | 说明 |
| --- | --- |
| `campus-resource-platform/src/main/java/com/john/campus/controller/AuthController.java` | 注册、登录、刷新、退出登录接口入口 |
| `campus-resource-platform/src/main/java/com/john/campus/controller/UserController.java` | 当前用户查询接口入口 |
| `campus-resource-platform/src/main/java/com/john/campus/service/AuthService.java` | 认证业务接口 |
| `campus-resource-platform/src/main/java/com/john/campus/service/impl/AuthServiceImpl.java` | 注册、登录、刷新、退出登录核心业务实现 |
| `campus-resource-platform/src/main/java/com/john/campus/service/UserService.java` | 当前用户业务接口 |
| `campus-resource-platform/src/main/java/com/john/campus/service/impl/UserServiceImpl.java` | 当前用户查询业务实现 |
| `campus-resource-platform/src/main/java/com/john/campus/service/PasswordService.java` | 密码加密与校验接口 |
| `campus-resource-platform/src/main/java/com/john/campus/service/impl/PasswordServiceImpl.java` | BCrypt 密码处理实现 |
| `campus-resource-platform/src/main/java/com/john/campus/mapper/UserMapper.java` | 用户表 MyBatis Mapper 接口 |
| `campus-resource-platform/src/main/resources/mapper/UserMapper.xml` | 用户表 SQL 映射 |
| `campus-resource-platform/src/main/java/com/john/campus/common/JwtUtils.java` | JWT 生成、解析和剩余有效期计算 |
| `campus-resource-platform/src/main/java/com/john/campus/interceptor/JwtAuthenticationInterceptor.java` | JWT 请求拦截器 |
| `campus-resource-platform/src/main/java/com/john/campus/common/UserContextHolder.java` | 当前用户 ThreadLocal 上下文 |
| `campus-resource-platform/src/main/java/com/john/campus/common/RedisKeyConstants.java` | Redis Key 常量 |
| `campus-resource-platform/src/main/java/com/john/campus/exception/GlobalExceptionHandler.java` | 全局异常处理 |
| `campus-resource-platform/src/main/java/com/john/campus/service/AuthSessionService.java` | 随机 Refresh、摘要、Redis 会话与 Lua 编排 |
| `campus-resource-platform/src/main/java/com/john/campus/dto/AuthRefreshDTO.java` | Refresh 请求非空与最大长度校验 |
| `campus-resource-platform/src/main/resources/lua/auth-session-*.lua` | 创建/轮换/撤销单 Key 原子状态机 |

### 6.2 核心方法

| 类 | 方法 | 作用 |
| --- | --- | --- |
| `AuthController` | `register(AuthRegisterDTO)` | 注册接口入口 |
| `AuthController` | `login(AuthLoginDTO)` | 登录接口入口 |
| `AuthController` | `refresh(AuthRefreshDTO)` | 刷新接口入口 |
| `AuthServiceImpl` | `refresh(String)` / `logoutSession(String)` | 刷新轮换与整个会话撤销 |
| `AuthSessionService` | `newCredential` / `parse` / `create` / `inspect` / `rotate` / `revoke` | 凭据与 Redis 状态机 |
| `AuthController` | `logout(HttpServletRequest, AuthRefreshDTO)` | 退出登录接口入口 |
| `UserController` | `me()` | 当前用户接口入口 |
| `AuthService` | `register(AuthRegisterDTO)` | 注册业务接口 |
| `AuthService` | `login(AuthLoginDTO)` | 登录业务接口 |
| `AuthService` | `logout(String)` | 退出登录业务接口 |
| `AuthServiceImpl` | `register(AuthRegisterDTO)` | 用户重复校验、密码加密、入库 |
| `AuthServiceImpl` | `login(AuthLoginDTO)` | 用户查询、密码校验、JWT 签发 |
| `AuthServiceImpl` | `logout(String)` | 可信旧Bearer删除sid（若有）并写jti黑名单 |
| `UserService` | `getCurrentUser()` | 当前用户查询业务接口 |
| `UserServiceImpl` | `getCurrentUser()` | 根据上下文 userId 查询用户 |
| `UserMapper` | `selectByUsername(String)` | 按用户名查询用户 |
| `UserMapper` | `selectById(Long)` | 按 ID 查询用户 |
| `UserMapper` | `insert(User)` | 插入用户并回填自增 ID |
| `UserMapper` | `updateLastLoginAt(Long, LocalDateTime)` | 更新最近登录时间 |
| `PasswordService` | `encode(String)` | BCrypt 加密密码 |
| `PasswordService` | `matches(String, String)` | 校验明文密码和密文是否匹配 |
| `JwtUtils` | `generateToken(Long, Integer)` 及 sid/剩余期限重载 | 兼容旧 Token；新 Access 含 sid 并受会话期限约束 |
| `JwtUtils` | `parseToken(String)` | 解析 JWT |
| `JwtUtils` | `getUserId(String)` | 从 JWT 获取用户 ID |
| `JwtUtils` | `getRole(String)` | 从 JWT 获取角色 |
| `JwtUtils` | `getJti(String)` | 从 JWT 获取 jti |
| `JwtUtils` | `getRemainingSeconds(String)` | 获取 Token 剩余有效期 |
| `JwtAuthenticationInterceptor` | `preHandle(...)` | 验签/到期、黑名单、sid 归属、写入上下文 |
| `JwtAuthenticationInterceptor` | `afterCompletion(...)` | 清理 `ThreadLocal` |
| `UserContextHolder` | `set(LoginUser)` | 写入当前用户上下文 |
| `UserContextHolder` | `getRequiredUserId()` | 获取当前登录用户 ID |
| `UserContextHolder` | `clear()` | 清理当前线程用户上下文 |

## 7. 请求处理流程

### 7.1 注册流程

1. `AuthController.register` 接收 `AuthRegisterDTO`。
2. `jakarta.validation` 校验用户名、密码、昵称、邮箱、手机号格式。
3. `AuthServiceImpl.register` 按用户名调用 `UserMapper.selectByUsername` 查询是否重复。
4. 调用 `PasswordService.encode` 使用 BCrypt 加密密码。
5. 构造 `User`，默认设置 `role = User.ROLE_STUDENT`、`status = User.STATUS_NORMAL`。
6. 调用 `UserMapper.insert` 写入 `user` 表。
7. 返回 `UserVO`，不返回密码字段。

### 7.2 登录流程

1. `AuthController.login` 接收 `AuthLoginDTO`。
2. `AuthServiceImpl.login` 调用 `UserMapper.selectByUsername` 查询用户。
3. 调用 `PasswordService.matches` 校验密码。
4. 调用 `user.isNormal()` 校验账号状态。
5. 调用 `UserMapper.updateLastLoginAt` 更新最近登录时间。
6. 调用 `JwtUtils.generateToken` 生成 JWT。
7. 创建固定七天的刷新会话，返回 `accessToken`、`refreshToken`、各自剩余秒数和 `UserVO`。

### 7.3 受保护接口鉴权流程

1. 请求进入 `/api/v1/**`。
2. `WebMvcConfig` 排除登录、注册、刷新、退出、健康检查、搜索、排行榜等路径；刷新和退出自行认证。
3. `JwtAuthenticationInterceptor.preHandle` 读取 `Authorization` 请求头。
4. 校验请求头格式必须为 `Bearer {token}`。
5. 调用 `JwtUtils.parseToken` 解析 JWT。
6. 使用 `RedisKeyConstants.tokenBlacklist(jti)` 查询 Redis 黑名单。
7. 黑名单命中则抛出 `TOKEN_BLACKLISTED`。
8. 新 Token 还需校验 sid 会话存在且归属当前用户，再构造 `LoginUser` 写入 `UserContextHolder`。
9. Controller 和 Service 可通过 `UserContextHolder` 获取当前用户。
10. 请求结束后 `afterCompletion` 调用 `UserContextHolder.clear()`。

### 7.4 退出登录流程

1. `WebMvcConfig` 排除退出路由，Controller 将 body 中的 Refresh 交给 Service 自行校验；旧客户端无 body 时交 Bearer 给 Service 验签。
2. Refresh 退出使用 Lua 验证当前或已用合法摘要，再删除整个 session；会话已不存在幂等成功。
3. 旧 Bearer 退出校验有效 JWT，有 sid 则删除该会话，再将当前 jti 写黑名单，TTL 为 Access 剩余秒数。
4. 后续鉴权先判断过期 40103，再判断黑名单 40102；未过期且未命中黑名单时，sid 不存在才返回 40105。

### 7.5 当前用户查询流程

1. 客户端携带 `Authorization: Bearer {token}` 请求 `GET /api/v1/users/me`。
2. `JwtAuthenticationInterceptor.preHandle` 解析 Token，校验 Redis 黑名单，并写入 `UserContextHolder`。
3. `UserController.me` 调用 `UserService.getCurrentUser`。
4. `UserServiceImpl.getCurrentUser` 通过 `UserContextHolder.getRequiredUserId()` 获取当前用户 ID。
5. 调用 `UserMapper.selectById` 查询 `user` 表。
6. 用户不存在时抛出 `RESOURCE_NOT_FOUND`。
7. 用户状态不是 `User.STATUS_NORMAL` 时抛出 `FORBIDDEN`。
8. 返回 `UserVO`，不返回 `password_hash`、`phone` 等敏感或非必要字段。

### 7.6 刷新流程

1. `AuthController.refresh` 校验非空、最长 128 字符的 Refresh body，传给 `AuthServiceImpl.refresh`。
2. `AuthSessionService.parse/inspect` 验证格式、当前摘要与会话期限；已知旧凭据重放会撤销，随机猜测只拒绝。
3. MySQL 查询当前用户状态和角色；不可用账号撤销会话，数据库失败不消耗凭据。
4. 准备含同 sid、新 jti 的 Access，期限不超过会话剩余时间；Lua CAS 轮换 Refresh，保留原 TTL。
5. 前端持锁条件提交双凭据，原业务请求最多重放一次；登录/注册/刷新/退出不递归刷新。

## 8. 模块内部类之间的关系

```mermaid
classDiagram
    AuthController --> AuthService
    AuthController --> AuthRefreshDTO
    AuthService <|.. AuthServiceImpl
    AuthServiceImpl --> UserMapper
    AuthServiceImpl --> PasswordService
    AuthServiceImpl --> JwtUtils
    AuthServiceImpl --> AuthSessionService
    AuthSessionService --> RedisLua
    JwtAuthenticationInterceptor --> JwtUtils
    JwtAuthenticationInterceptor --> Redis会话与黑名单
    JwtAuthenticationInterceptor --> UserContextHolder
    UserServiceImpl --> UserMapper
    UserServiceImpl --> UserContextHolder
```

`RedisLua` 表示三份 Lua 资源而非 Java 类；`AuthRefreshDTO` 同时用于刷新和有 body 的退出。JwtClaims 新增 sid，旧构造器保留兼容。

## 9. 模块核心时序图

```mermaid
sequenceDiagram
    participant Client as 前端
    participant Auth as AuthController/AuthServiceImpl
    participant DB as UserMapper/MySQL
    participant JWT as JwtUtils
    participant Store as AuthSessionService/Redis
    Client->>Auth: login(账号密码)
    Auth->>DB: 按用户名查询用户
    Auth->>Auth: BCrypt密码与账号状态校验
    Auth->>DB: 更新last_login_at
    Auth->>JWT: 生成含sid的Access
    Auth->>Store: create随机Refresh摘要与固定七天TTL
    Auth-->>Client: 双Token和用户信息
    Client->>Auth: refresh(当前Refresh)
    Auth->>Store: inspect摘要
    Auth->>DB: 当前用户状态与角色
    Auth->>JWT: 准备新Access
    Auth->>Store: Lua CAS轮换，TTL不变
    Auth-->>Client: 新双Token
    Client->>Auth: logout(Refresh)
    Auth->>Store: 当前或已用摘要认证后DEL sid
    Auth-->>Client: 成功
```

## 10. 自动刷新流程图

```mermaid
flowchart TD
    A[业务请求] --> B{是否40103且未重放}
    B -->|否| C[正常结果或错误分类处理]
    B -->|是| D[同页single-flight与Web Lock]
    D --> E[锁内重读generation和双Token]
    E --> F{同代Access已更新}
    F -->|是| H[用新Access重放原请求一次]
    F -->|否| G[Refresh认证和CAS轮换]
    G --> H
```

## 11. 鉴权流程图

```mermaid
flowchart TD
    A[受保护请求] --> B{Bearer格式与签名有效}
    B -->|否| C[40101]
    B -->|是| D{Access过期}
    D -->|是| E[40103]
    D -->|否| F{jti黑名单命中}
    F -->|是| G[40102]
    F -->|否| H{无sid的历史Token}
    H -->|是| K[写请求上下文并执行业务]
    H -->|否| I{Redis会话userId匹配}
    I -->|否| J[40105]
    I -->|是| K
    K --> L[请求结束清ThreadLocal]
```

Redis 查询异常统一失败关闭为 50001；顺序决定已退出且过期的 Token 先返回 40103。公开路径不走此图；刷新/退出由 Service 认证。完整前后端协作见 [生命周期指南](01-auth-token-lifecycle.md)。

## 12. 异常处理

| 异常场景 | 抛出位置 | 错误码 |
| --- | --- | --- |
| 注册用户名已存在 | `AuthServiceImpl.register` | `40002 DATA_DUPLICATE` |
| 用户名、邮箱或手机号数据库唯一约束冲突 | `AuthServiceImpl.register` | `40002 DATA_DUPLICATE` |
| 登录账号或密码错误 | `AuthServiceImpl.login` | `40101 UNAUTHORIZED` |
| 用户被禁用 | `AuthServiceImpl.login`、`UserServiceImpl.getCurrentUser` | `40301 FORBIDDEN` |
| Token 缺失或格式错误 | `JwtAuthenticationInterceptor.preHandle` | `40101 UNAUTHORIZED` |
| Token 签名无效 | `JwtUtils.parseToken` | `40101 UNAUTHORIZED` |
| Token 已过期 | `JwtUtils.parseToken` | `40103 TOKEN_EXPIRED` |
| Refresh 失效、重放或会话超轮换上限 | `AuthSessionService` | `40104 REFRESH_TOKEN_INVALID` |
| sid 不存在或归属不匹配 | `JwtAuthenticationInterceptor.preHandle` | `40105 SESSION_REVOKED` |
| Token 已退出登录 | `JwtAuthenticationInterceptor.preHandle` | `40102 TOKEN_BLACKLISTED` |
| 当前用户不存在 | `UserServiceImpl.getCurrentUser` | `40401 RESOURCE_NOT_FOUND` |
| DTO 参数校验失败 | `GlobalExceptionHandler` | `40001 PARAM_ERROR` |

HTTP 状态码由 `GlobalExceptionHandler.resolveHttpStatus` 统一转换：

| 业务错误码 | HTTP 状态 |
| --- | --- |
| `40101 UNAUTHORIZED` | `401 Unauthorized` |
| `40102 TOKEN_BLACKLISTED` | `401 Unauthorized` |
| `40103 TOKEN_EXPIRED` | `401 Unauthorized` |
| `40104 REFRESH_TOKEN_INVALID` | `401 Unauthorized` |
| `40105 SESSION_REVOKED` | `401 Unauthorized` |
| `40301 FORBIDDEN` | `403 Forbidden` |
| `40401 RESOURCE_NOT_FOUND` | `404 Not Found` |
| `42901 RATE_LIMITED` | `429 Too Many Requests` |
| `50001 SERVER_ERROR` | `500 Internal Server Error` |
| 其他 4xxxx 错误 | `400 Bad Request` |

## 13. 权限控制

- `/api/v1/auth/register`：不需要登录。
- `/api/v1/auth/login`：不需要登录。
- `/api/v1/auth/refresh` 与 `/api/v1/auth/logout`：排除 Access 拦截，由 Service 校验 Refresh 或旧 Bearer 凭据。
- `/api/v1/users/me`：需要登录，由 `JwtAuthenticationInterceptor` 校验 Token。
- 本模块暂未实现管理员专属接口。
- 本模块暂未实现注解式权限控制。

## 14. 事务处理

| 方法 | 事务注解 | 原因 |
| --- | --- | --- |
| `AuthServiceImpl.register` | `@Transactional(rollbackFor = Exception.class)` | 插入用户时出现异常需要回滚 |
| `AuthServiceImpl.login` | `@Transactional(rollbackFor = Exception.class)` | 登录成功后更新 `last_login_at`，失败时回滚 |
| `AuthServiceImpl.refresh` | 无跨库事务 | 只读 MySQL 后 Redis Lua CAS；查库失败不消耗 Refresh |
| `AuthServiceImpl.logout` / `logoutSession` | 无 MySQL 事务 | Redis 会话撤销，旧 Bearer 路径还写 jti 黑名单 |
| `UserServiceImpl.getCurrentUser` | 本模块暂未涉及事务 | 只读查询 |

说明：

- `register` 先查用户名再插入，但并发注册相同用户名时仍可能同时通过前置查询，因此最终依赖数据库唯一索引 `uk_user_username` 兜底。
- `login` 更新 `last_login_at` 和签发 Token 在同一业务方法内完成，当前事务只覆盖 MySQL 更新，不覆盖 JWT 字符串生成。
- `logout` 只写 Redis，当前模块暂未使用数据库事务或分布式事务。

## 15. 测试用例

### 15.1 历史验证与本次代码验证

下表前两项为早期实现历史结果，保留其当时范围；2026-11-01 代码变更后，认证及 Controller mock/unit 58/58、前端 101/101 与构建通过。本轮文档同步未重跑业务测试，具体复验命令见 [指南](01-auth-token-lifecycle.md#7-配置验证与现有限制)。

| 测试项 | 命令 | 结果 |
| --- | --- | --- |
| 编译验证 | `.\mvnw.cmd -DskipTests compile` | 通过 |
| JWT 密钥启动校验 | `.\mvnw.cmd "-Dtest=JwtUtilsTest,CampusResourcePlatformApplicationTests" test` | 3/3 通过；缺失、过短和历史公开默认密钥均被拒绝 |

### 15.2 建议接口测试用例

| 用例 | 请求 | 预期结果 |
| --- | --- | --- |
| 注册成功 | `POST /api/v1/auth/register`，传入合法用户名、密码、昵称 | 返回 `code = 0`，数据库 `user.password_hash` 为 BCrypt 密文 |
| 重复用户名注册 | 使用相同 `username` 再次注册 | 返回 `40002 DATA_DUPLICATE` |
| 登录成功 | `POST /api/v1/auth/login`，传入正确账号密码 | 返回 `accessToken`、`tokenType = Bearer` |
| 密码错误 | 登录时传入错误密码 | 返回 `40101 UNAUTHORIZED` |
| 查询当前用户成功 | 携带 `Authorization: Bearer {token}` 请求 `GET /api/v1/users/me` | 返回当前用户信息 |
| 未携带 Token 查询当前用户 | 不传 `Authorization` 请求 `GET /api/v1/users/me` | 返回 `40101 UNAUTHORIZED` |
| 新客户端退出成功 | body 携带 Refresh 请求 `POST /api/v1/auth/logout` | `code = 0`，删除 sid 会话 |
| 退出后再次访问 | 使用退出后的 Access 请求受保护接口 | 按先后顺序：过期40103、黑名单40102、否则会话撤销40105 |

Postman 测试集合：

- `docs/api/postman/campus-resource-platform.postman_collection.json`
- `docs/api/postman/campus-local.postman_environment.json`

已补充 JWT、刷新 Service、会话存储、Controller Web 切片和拦截器单元测试；真实 Redis Lua 并发、完整 MySQL/HTTP 认证集成仍待验证。

## 16. 面试可讲点

- 密码没有明文存储，而是通过 `BCryptPasswordEncoder` 加密后保存到 `password_hash`。
- 新JWT携带 `sid`、`userId`、`role`、`jti`；sid用于会话撤销，jti黑名单保留旧Bearer退出兼容。
- JWT 自包含身份，加 Redis 会话撤销和旧黑名单；系统整体非完全无状态。
- Redis 黑名单 TTL 使用 Token 剩余有效期，避免黑名单 Key 长期占用内存。
- `JwtAuthenticationInterceptor` 将用户信息放入 `UserContextHolder`，业务层无需重复解析 Token。
- `afterCompletion` 清理 `ThreadLocal`，避免 Web 容器线程复用导致用户信息串线。
- 注册和登录使用 Service 层事务，避免异常时出现部分更新。
- Controller 不直接访问 Mapper，保持 Controller -> Service -> Mapper 的分层结构。

## 17. 后续优化方向

- 增加 `UserMapper.selectByEmail`、`selectByPhone`，注册前给出更精确的重复提示。
- 增加登录限流，使用 Redis 防止暴力破解。
- 增加验证码或邮箱验证。
- 持续完善刷新会话审计和异常监控。
- 增加管理员禁用用户后 Token 批量失效机制，例如 `crp:auth:user:token-version:{userId}`。
- 增加注解式权限控制，例如 `@RequireRole(User.ROLE_ADMIN)`。
- 增加认证模块单元测试和集成测试。

## 18. Git Commit Message 建议

```text
feat(auth): implement user authentication module

- add register, login, logout and current user APIs
- add BCrypt password encoding and JWT utilities
- add JWT interceptor with Redis token blacklist support
- add user mapper operations for auth flow
- document auth module implementation and sync API/Redis notes
```


## 2026-11-01 双 Token 刷新后端

已实现 Access + opaque Refresh；登录默认 Access 15 分钟、Refresh 会话固定七天。`JwtUtils` 增加 sid 与剩余期限签发；`AuthSessionService` 封装安全随机数、摘要、Lua 创建/CAS 轮换/撤销；`AuthServiceImpl.refresh` 重新查询当前用户状态和角色；`AuthController.refresh` 提供 `/api/v1/auth/refresh`。退出接受 Refresh，因此 Access 到期仍可退出。

每请求验证顺序为签名/到期、jti 黑名单、新 Token 的 sid 会话归属、写 ThreadLocal；请求结束清理。旧无 sid Token 兼容但无法刷新。专属错误码：40103 到期、40104 Refresh 无效、40105 会话撤销。40101/40102 不触发自动刷新。

每会话最多 2048 次轮换（正常七天约 672 次），超限删除会话并要求重新登录，防止已用摘要无限膨胀。同一会话由 Redis 单 Key Lua 串行化；刷新成功保留旧凭据摘要用于重放检测，退出删除会话并使所有同 sid Access 在后续鉴权失效（优先错误顺序见第 11 节），不影响其他设备。无新增数据库表、依赖或其他业务模块修改。Refresh 不记录日志，Redis 只存摘要；客户端须妥善保管明文。

前端已接入自动刷新（见下一节）。新增本地 mock/unit 测试覆盖 JWT、业务编排、控制器切片、会话存储返回值及拦截器。按用户要求不执行 Docker/真实 MySQL/Redis 集成测试；Lua 真正原子性和外部服务集成尚未验证。


## 2026-11-01 前端自动刷新

- 登录一次性保存 `crp.auth-session` JSON 主记录（generation、Access、Refresh、user），旧 `crp.access-token`/`crp.current-user` 只作兼容镜像。主记录优先；旧会话无 Refresh，只能重新登录获取刷新能力。
- `request.ts` 仅对受保护请求的 40103 自动刷新，每次原请求最多重放一次；黑名单、伪造、会话撤销、票据无效均不能触发刷新。并发到期请求共用一次刷新 Promise，晚到旧响应复用最新凭据。
- `navigator.locks` 的 `crp.auth-session` 锁覆盖刷新 HTTP+写入、退出 HTTP+清理、登录提交、资料更新和错误清理；锁内重新读取主记录。没有 Web Locks 的浏览器退化为单页串行及快照保护，多标签页竞争可能触发服务端重放防护并要求重新登录，不宣称完整跨页保证。
- generation 防止旧请求以新账号重放、旧响应覆盖新登录、旧错误清除新凭据；storage 事件同步其他标签页状态。资料更新持锁只改 user，不清掉刚轮换的 Refresh。
- 退出立即阻止本页新刷新/重放，等待在途刷新提交后以最新 Refresh 撤销；失效凭据视为已退出并清理本地，网络/500 保留以便重试。旧无 Refresh 的过期会话也能本地退出。
- 下载二进制请求使用同一认证编排；仅 JWT 拦截器的 40103 可在刷新后保留原 `X-Download-Ticket` 重放一次，此时下载业务和消费票据尚未执行。其他取流/票据错误不重放。
- 存储边界：本轮沿用项目浏览器存储模式，Refresh 为 bearer secret、由同源 JavaScript 可读；页面 XSS 可窃取凭据。没有引入 HttpOnly Cookie/BFF，不能将当前模式描述为防 XSS 凭据隔离；任何凭据不得进入日志和 URL。
- 验证仅本地 Vitest 和前端类型检查/生产构建；未执行 Docker/真实 MySQL/Redis 集成测试或真实多浏览器端到端验收，也未操作生产服务。
