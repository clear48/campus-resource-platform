import type { FavoriteActionResult, FavoriteStatus } from '../types/favorite'
import { request } from '../utils/request'

/** 创建收藏关系；请求层会自动携带当前用户的 Authorization Token。 */
export function addFavorite(resourceId: number): Promise<FavoriteActionResult> {
  return request.post<FavoriteActionResult>(`/resources/${resourceId}/favorites`)
}

/** 取消已有收藏关系；不存在的关系由后端以业务错误明确返回。 */
export function removeFavorite(resourceId: number): Promise<FavoriteActionResult> {
  return request.delete<FavoriteActionResult>(`/resources/${resourceId}/favorites`)
}

/** 查询当前用户是否已收藏指定资料，详情页不依赖存在歧义的 favorited 字段。 */
export function getFavoriteStatus(resourceId: number): Promise<FavoriteStatus> {
  return request.get<FavoriteStatus>(`/resources/${resourceId}/favorite-status`)
}
