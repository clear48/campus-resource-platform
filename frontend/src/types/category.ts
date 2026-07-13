/** 公开分类查询的 query 参数；省略 parentId 时由后端按根分类默认值处理。 */
export interface CategoryQuery {
  parentId?: number
}

/** 后端 CategoryVO 返回的启用分类字段。 */
export interface CategoryItem {
  categoryId: number
  parentId: number
  categoryName: string
  description: string | null
  sortOrder: number
}
