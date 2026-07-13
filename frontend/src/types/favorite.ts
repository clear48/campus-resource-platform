/** 收藏或取消收藏后由后端返回的最新关系与计数。 */
export interface FavoriteActionResult {
  resourceId: number
  favorited: boolean
  duplicateIgnored: boolean
  favoriteCount: number
  // 后端当前固定返回 0，真实热度异步维护，页面不可据此自行计算榜单。
  hotScoreDelta: number
}

/** 当前登录用户对指定资料的有效收藏关系。 */
export interface FavoriteStatus {
  resourceId: number
  favorited: boolean
}

/** “我的收藏”列表 records 内由接口文档明确的字段。 */
export interface FavoriteResourceItem {
  resourceId: number
  title: string
  courseName: string
  downloadCount: number
  favoriteCount: number
  createdAt: string
  favoriteAt: string
}

/** “我的收藏”接口仅接受的分页参数。 */
export interface FavoriteListQuery {
  pageNo?: number
  pageSize?: number
}
