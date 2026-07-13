import type { PageResult } from '../types/api'
import type { FavoriteListQuery, FavoriteResourceItem } from '../types/favorite'
import type { DownloadRecordItem, DownloadRecordListQuery } from '../types/download'
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

/** 查询当前登录用户的收藏资料；不扩展收藏夹或分组等后端未提供的参数。 */
export function getMyFavorites(params: FavoriteListQuery): Promise<PageResult<FavoriteResourceItem>> {
  return request.get<PageResult<FavoriteResourceItem>>('/users/me/favorites', { params })
}

/** 查询当前登录用户的下载记录；不暴露后端审计字段。 */
export function getMyDownloadRecords(params: DownloadRecordListQuery): Promise<PageResult<DownloadRecordItem>> {
  return request.get<PageResult<DownloadRecordItem>>('/users/me/download-records', { params })
}
