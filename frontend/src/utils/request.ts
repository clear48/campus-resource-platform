import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'
import type { ApiResponse } from '../types/api'
import { session } from '../state/session'

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

// 浏览器侧只请求相对 API 地址，开发环境由 Vite 代理转发到后端。
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

export const httpClient = axios.create({
  baseURL: apiBaseUrl,
  timeout: 10_000,
})

httpClient.interceptors.request.use((config) => {
  // 登录页可能刚写入 localStorage，先同步会话再决定是否注入 Authorization。
  session.hydrateSession()

  if (session.accessToken.value) {
    config.headers.Authorization = `Bearer ${session.accessToken.value}`
  }

  return config
})

/** 将后端成功响应转换为页面实际需要的 data。 */
export function unwrapResponse<T>(response: AxiosResponse<ApiResponse<T>>): T {
  const responseBody = response.data

  if (responseBody.code !== 0) {
    throw new ApiBusinessError(responseBody.code, responseBody.message, responseBody.traceId)
  }

  return responseBody.data
}

/**
 * 网络异常和后端非 2xx JSON 错误统一转换为业务错误，避免页面重复判断 AxiosError。
 */
function rethrowRequestError(error: unknown): never {
  if (error instanceof ApiBusinessError) {
    throw error
  }

  if (axios.isAxiosError<ApiResponse<unknown>>(error)) {
    const responseBody = error.response?.data

    if (responseBody && typeof responseBody.code === 'number') {
      if (responseBody.code === 40101 || responseBody.code === 40102) {
        // Token 无效或已进入黑名单时，清理前端缓存，避免继续携带失效凭据。
        session.clearSession()
      }

      throw new ApiBusinessError(responseBody.code, responseBody.message, responseBody.traceId)
    }

    throw new ApiBusinessError(-1, error.message || '网络请求失败')
  }

  throw error
}

/**
 * 页面只通过这一层发起 JSON 请求；Token 注入和登录失效跳转将在后续会话任务中补充。
 */
export const request = {
  async get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    try {
      return unwrapResponse(await httpClient.get<ApiResponse<T>>(url, config))
    } catch (error) {
      return rethrowRequestError(error)
    }
  },

  async post<T, D = unknown>(url: string, data?: D, config?: AxiosRequestConfig): Promise<T> {
    try {
      return unwrapResponse(await httpClient.post<ApiResponse<T>>(url, data, config))
    } catch (error) {
      return rethrowRequestError(error)
    }
  },

  async put<T, D = unknown>(url: string, data?: D, config?: AxiosRequestConfig): Promise<T> {
    try {
      return unwrapResponse(await httpClient.put<ApiResponse<T>>(url, data, config))
    } catch (error) {
      return rethrowRequestError(error)
    }
  },

  async delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    try {
      return unwrapResponse(await httpClient.delete<ApiResponse<T>>(url, config))
    } catch (error) {
      return rethrowRequestError(error)
    }
  },
}
