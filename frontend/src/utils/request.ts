import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'
import type { ApiResponse } from '../types/api'
import { session, withSessionLock, type SessionSnapshot } from '../state/session'
import type { LoginResult } from '../types/auth'

/**
 * 统一保留后端业务码和 traceId，页面可以直接展示后端错误信息并在演示时定位请求。
 */
export class ApiBusinessError extends Error {
  readonly code: number
  readonly traceId?: string

  constructor(code: number, message: string, traceId?: string) {
    super(message)
    this.name = 'ApiBusinessError'
    this.code = code
    this.traceId = traceId
  }
}

// 后端接口固定带 /api/v1 版本前缀；环境变量缺失时也必须保持该前缀，避免页面请求到不存在的 /api 路径。
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api/v1'

export const httpClient = axios.create({
  baseURL: apiBaseUrl,
  timeout: 10_000,
})

type SessionRequestConfig = AxiosRequestConfig & { authSnapshot?: SessionSnapshot }

httpClient.interceptors.request.use((config) => {
  const expected = (config as SessionRequestConfig).authSnapshot
  if (expected && !session.matches(expected, false)) throw new ApiBusinessError(40105, '登录状态已变更，请重试')
  // 不能在 Axios 排队期间把操作替换成另一账号的凭据。
  const current = expected ?? session.snapshot()
  if (current.accessToken) config.headers.Authorization = `Bearer ${current.accessToken}`
  else config.headers.delete('Authorization')
  return config
})

export function unwrapResponse<T>(response: AxiosResponse<ApiResponse<T>>): T {
  const body = response.data
  if (body.code !== 0) throw new ApiBusinessError(body.code, body.message, body.traceId)
  return body.data
}

/** 下载错误可能是 JSON Blob，先读取错误码再决定是否允许重试，绝不重试任意取流失败。 */
async function asBusinessError(error: unknown): Promise<ApiBusinessError> {
  if (error instanceof ApiBusinessError) return error
  if (axios.isAxiosError<ApiResponse<unknown>>(error)) {
    let body = error.response?.data
    if (body instanceof Blob) {
      try { body = JSON.parse(await body.text()) as ApiResponse<unknown> } catch { /* 非 JSON 错误保留网络语义。 */ }
    }
    if (body && typeof body.code === 'number') return new ApiBusinessError(body.code, body.message, body.traceId)
    return new ApiBusinessError(-1, error.message || '网络请求失败')
  }
  return new ApiBusinessError(-1, error instanceof Error ? error.message : '请求失败')
}
const authFailures = new Set([40101, 40102, 40104, 40105])
let refreshing: { generation: string; promise: Promise<SessionSnapshot> } | null = null

/** 同页 single-flight，跨页持锁后重读，复用其他标签页已轮换的结果。 */
async function refreshSession(expected: SessionSnapshot): Promise<SessionSnapshot> {
  if (refreshing?.generation === expected.generation) return refreshing.promise
  const operation = withSessionLock(async () => {
    let current = session.snapshot()
    if (session.logoutPending.value || current.generation !== expected.generation) {
      throw new ApiBusinessError(40105, '登录状态已变更，请重新登录')
    }
    if (current.accessToken !== expected.accessToken) return current
    if (!current.refreshToken) {
      session.clearSession(current)
      throw new ApiBusinessError(40103, '登录已过期，请重新登录')
    }
    try {
      const pair = unwrapResponse(await httpClient.post<ApiResponse<LoginResult>>('/auth/refresh',
        { refreshToken: current.refreshToken }, { authSnapshot: current } as SessionRequestConfig))
      // 退出等候此刷新提交后将用最新凭据撤销；不重放业务即可。
      if (!session.commitRefresh(pair, current)) throw new ApiBusinessError(40105, '登录状态已变更')
      current = session.snapshot()
      return current
    } catch (error) {
      const failure = await asBusinessError(error)
      if (authFailures.has(failure.code)) session.clearSession(current)
      throw failure
    }
  })
  const flight = { generation: expected.generation, promise: operation }
  refreshing = flight
  try { return await operation }
  finally { if (refreshing === flight) refreshing = null }
}

/** 每个业务请求最多重放一次，只有拦截器的 40103 允许刷新。 */
export async function authenticatedRequest<T>(config: AxiosRequestConfig,
  expected = session.snapshot(), retried = false): Promise<AxiosResponse<T>> {
  const excluded = /^\/auth\/(login|register|refresh|logout)(?:[/?]|$)/.test(config.url ?? '')
  try {
    return await httpClient.request<T>({ ...config, authSnapshot: expected } as SessionRequestConfig)
  } catch (error) {
    const failure = await asBusinessError(error)
    if (!excluded && failure.code === 40103 && !retried && !session.logoutPending.value &&
        session.matches(expected, false)) {
      const next = await refreshSession(expected)
      if (session.logoutPending.value || next.generation !== expected.generation) throw failure
      return authenticatedRequest<T>(config, next, true)
    }
    if (!excluded && !session.logoutPending.value && (authFailures.has(failure.code) || failure.code === 40103)) {
      await session.clearSessionIfCurrent(expected)
    }
    throw failure
  }
}
async function send<T>(method: string, url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return unwrapResponse(await authenticatedRequest<ApiResponse<T>>({ ...config, method, url, data }))
}
export const request = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> { return send<T>('GET', url, undefined, config) },
  post<T, D = unknown>(url: string, data?: D, config?: AxiosRequestConfig): Promise<T> {
    return send<T>('POST', url, data, config)
  },
  put<T, D = unknown>(url: string, data?: D, config?: AxiosRequestConfig): Promise<T> {
    return send<T>('PUT', url, data, config)
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> { return send<T>('DELETE', url, undefined, config) },
}
