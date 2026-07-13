import type { AuthUser, LoginRequest, LoginResult, RegisterRequest } from '../types/auth'
import { request } from '../utils/request'

/** 注册普通用户账号。 */
export function register(data: RegisterRequest): Promise<AuthUser> {
  return request.post<AuthUser, RegisterRequest>('/auth/register', data)
}

/** 使用账号密码换取 JWT 登录凭据。 */
export function login(data: LoginRequest): Promise<LoginResult> {
  return request.post<LoginResult, LoginRequest>('/auth/login', data)
}

/**
 * 后端从 Authorization 请求头读取 Token 并加入 Redis 黑名单。
 * Token 注入将在会话状态任务中统一实现，避免此 API 模块自行读取浏览器存储。
 */
export function logout(): Promise<null> {
  return request.post<null>('/auth/logout')
}
