export type ResourceRankingPeriod = 'daily' | 'weekly' | 'monthly' | 'all'
export type SearchKeywordRankingPeriod = Exclude<ResourceRankingPeriod, 'all'>

export interface HotResourceQuery {
  limit?: number
  categoryId?: number
  period?: ResourceRankingPeriod
}

export interface HotSearchKeywordQuery {
  limit?: number
  period?: SearchKeywordRankingPeriod
}

export interface HotResourceRankingItem {
  rank: number
  resourceId: number
  title: string
  courseName: string
  downloadCount: number
  favoriteCount: number
  hotScore: number
}

export interface HotSearchKeywordRankingItem {
  rank: number
  keyword: string
  searchCount: number
}
