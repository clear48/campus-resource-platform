import type { PageResult } from '../types/api'
import type { MyResourceItem, MyResourceQuery } from '../types/resource'
import type { UserProfile } from '../types/user'
import { request } from '../utils/request'

/** 获取 JWT 对应的当前用户信息。 */
export function getCurrentUser(): Promise<UserProfile> {
  return request.get<UserProfile>('/users/me')
}

/** 查询当前登录用户上传的资料；列表字段严格按接口文档声明。 */
export function getMyResources(params: MyResourceQuery): Promise<PageResult<MyResourceItem>> {
  return request.get<PageResult<MyResourceItem>>('/users/me/resources', { params })
}
