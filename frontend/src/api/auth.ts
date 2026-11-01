import type { AuthUser, LoginRequest, LoginResult, RegisterRequest } from '../types/auth'
import { ApiBusinessError, request } from '../utils/request'
import { session, withSessionLock } from '../state/session'

/** 注册普通用户账号。 */
export function register(data: RegisterRequest): Promise<AuthUser> {
  return request.post<AuthUser, RegisterRequest>('/auth/register', data)
}

/** 使用账号密码换取 JWT 登录凭据。 */
export function login(data: LoginRequest): Promise<LoginResult> {
  return request.post<LoginResult, LoginRequest>('/auth/login', data)
}

/** 等在途刷新提交，再持相同锁用最新 Refresh 撤销会话。 */
export async function logout(): Promise<null> {
  if (session.logoutPending.value) return null
  const expected = session.snapshot()
  session.logoutPending.value = true
  try {
    return await withSessionLock(async () => {
      const current = session.snapshot()
      if (current.generation !== expected.generation) return null
      try {
        const result = await request.post<null>('/auth/logout', current.refreshToken
          ? { refreshToken: current.refreshToken } : undefined)
        session.clearSession(current)
        return result
      } catch (error) {
        // 凭据已不可用时按已退出处理，避免旧客户端因过期被困在登录态。
        if (error instanceof ApiBusinessError && [40101, 40102, 40103, 40104, 40105].includes(error.code)) {
          session.clearSession(current)
          return null
        }
        throw error
      }
    })
  } finally { session.logoutPending.value = false }
}
