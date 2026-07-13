import type { CategoryItem, CategoryQuery } from '../types/category'
import { request } from '../utils/request'

/** 查询公开启用分类；接口不需要 Authorization。 */
export function getCategories(params?: CategoryQuery): Promise<CategoryItem[]> {
  return request.get<CategoryItem[]>('/categories', { params })
}
