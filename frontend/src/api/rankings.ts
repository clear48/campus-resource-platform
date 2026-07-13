import type {
  HotResourceQuery,
  HotResourceRankingItem,
  HotSearchKeywordQuery,
  HotSearchKeywordRankingItem,
} from '../types/ranking'
import { request } from '../utils/request'

/** 查询公开热门资料榜，支持日/周/月/总榜。 */
export function getHotResources(params?: HotResourceQuery): Promise<HotResourceRankingItem[]> {
  return request.get<HotResourceRankingItem[]>('/rankings/resources/hot', { params })
}

/** 查询公开热门搜索词榜，后端只支持日/周/月周期。 */
export function getHotSearchKeywords(params?: HotSearchKeywordQuery): Promise<HotSearchKeywordRankingItem[]> {
  return request.get<HotSearchKeywordRankingItem[]>('/rankings/search-keywords/hot', { params })
}
