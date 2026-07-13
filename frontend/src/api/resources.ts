import type { CreateResourceRequest, CreateResourceResult, ResourceDetail } from '../types/resource'
import { request } from '../utils/request'

/** 获取公开资料详情；后端仅允许读取 APPROVED 资料。 */
export function getResourceDetail(resourceId: number): Promise<ResourceDetail> {
  return request.get<ResourceDetail>(`/resources/${resourceId}`)
}

/** 将已上传文件转为待审核资料；请求层会自动携带当前会话 Token。 */
export function createResource(data: CreateResourceRequest): Promise<CreateResourceResult> {
  return request.post<CreateResourceResult, CreateResourceRequest>('/resources', data)
}
