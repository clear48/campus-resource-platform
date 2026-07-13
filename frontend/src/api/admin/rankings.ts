import { request } from '../../utils/request'

/** 手动重建热门资料 all 总榜；成功响应 data 固定为 null，不伪造重建进度或结果。 */
export function rebuildHotResourceRanking(): Promise<null> {
  return request.post<null>('/admin/rankings/resources/hot/rebuild')
}
