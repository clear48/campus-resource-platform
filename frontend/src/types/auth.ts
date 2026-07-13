/** 注册接口提交的账号信息。 */
export interface RegisterRequest {
  username: string
  password: string
  nickname: string
  email?: string
  phone?: string
}

/** 登录接口提交的凭据。 */
export interface LoginRequest {
  username: string
  password: string
}

/** 认证接口当前返回的最小用户信息。 */
export interface AuthUser {
  userId: number
  username: string
  nickname: string
  email: string | null
  role: number
  status: number
}

/** 登录成功后的 Token 与用户信息。 */
export interface LoginResult {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: AuthUser
}
