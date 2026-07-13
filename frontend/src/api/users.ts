import type { UserProfile } from '../types/user'
import { request } from '../utils/request'

/** 获取 JWT 对应的当前用户信息。 */
export function getCurrentUser(): Promise<UserProfile> {
  return request.get<UserProfile>('/users/me')
}
