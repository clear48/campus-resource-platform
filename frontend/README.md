# 校园资料平台前端演示

这是校园资料共享与智能检索平台的轻量演示前端，使用 Vue 3、Vite、TypeScript、Element Plus 和 Axios。页面只用于演示现有后端能力，不包含复杂权限菜单、全局状态管理或图表系统。

## 运行要求

- Node.js 22.18 或更高版本（当前锁定依赖要求该版本范围）。
- npm 10 或兼容版本。
- 可访问的后端服务；本地默认地址为 `http://127.0.0.1:8080`。

## 安装

在 `frontend/` 目录执行：

```bash
npm ci
```

`package-lock.json` 已提交，演示或 CI 环境优先使用 `npm ci`，以保证安装版本与当前验证环境一致。

## 环境变量

复制环境变量示例文件，并按实际后端地址调整：

```bash
copy .env.example .env.development
```

Windows PowerShell 也可以使用：

```powershell
Copy-Item .env.example .env.development
```

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api/v1` | 浏览器请求的版本化 API 前缀，保持相对路径以使用开发代理。 |
| `VITE_API_PROXY_TARGET` | `http://127.0.0.1:8080` | Vite 开发服务器转发 `/api/v1` 请求的后端地址。 |

不要在任何 `.env` 文件中写入数据库密码、JWT 密钥、Redis 地址、真实账号密码或 Authorization Token。

## 启动

先启动后端，再启动前端：

```bash
npm run dev
```

终端出现 Vite 地址后，在浏览器打开 `http://127.0.0.1:5173/`。开发服务器会把 `/api/v1` 请求转发给 `VITE_API_PROXY_TARGET`。

## 验证命令

```bash
npm run test:unit
npm run build
```

当前最终回归基线为 31 个测试文件、66 个用例。构建产物写入 `dist/`，该目录不提交到 Git。

## 演示准备

1. 确认后端健康检查可用：`GET /api/v1/health`。
2. 使用注册页创建普通用户；通过后端既有数据准备方式创建管理员账号和启用分类。不要把真实密码写入仓库。
3. 普通用户可演示搜索、资料详情、收藏、两步下载、上传、我的上传/收藏/下载记录。
4. 管理员可演示待审核资料的通过、拒绝、下架、审计流水和热门资料总榜重建。
5. 上传资料先创建为待审核状态；只有审核通过后才会出现在公开搜索、详情、收藏和下载链路中。

已审核资料可用于演示搜索、收藏、下载去重、限流、热门资料和热门搜索词。下架演示请使用单独的测试资料，避免影响需要持续展示的公开资料。

## 常见排查

| 现象 | 检查方式 |
| --- | --- |
| 页面请求失败 | 确认后端已启动，并核对 `VITE_API_BASE_URL=/api/v1` 与 `VITE_API_PROXY_TARGET`。 |
| 401 或跳转登录页 | 重新登录；后端退出登录后旧 Token 会进入黑名单。 |
| 403 管理员接口 | 使用管理员账号；前端入口提示不替代后端最终鉴权。 |
| 上传后无法公开搜索 | 资料仍处于待审核、被拒绝或已下架状态时属于正常后端规则。 |
| 下载提示过于频繁 | 等待后端限流窗口结束后再试；不要通过前端绕过限流。 |

接口对应关系见 [`../docs/frontend/03-api-mapping.md`](../docs/frontend/03-api-mapping.md)，页面设计见 [`../docs/frontend/02-page-design.md`](../docs/frontend/02-page-design.md)。
