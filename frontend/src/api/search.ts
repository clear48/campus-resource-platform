import type { PageResult } from '../types/api'
import type { SearchResourceItem, SearchResourceQuery } from '../types/search'
import { request } from '../utils/request'

/** 搜索公开资料；后端在查询条件中固定限制为 APPROVED 状态。 */
export function searchResources(params: SearchResourceQuery): Promise<PageResult<SearchResourceItem>> {
  return request.get<PageResult<SearchResourceItem>>('/search/resources', { params })
}
