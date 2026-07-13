<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { approveResource, getAuditRecords, getPendingReviews, rejectResource } from '../../api/admin/resources'
import { session } from '../../state/session'
import type { AuditRecordItem, PendingReviewQuery, PendingReviewResource } from '../../types/audit'
import type { PageResult } from '../../types/api'
import { getResourceTypeLabel } from '../../types/enums'
import { formatDateTime } from '../../utils/format'

const router = useRouter()
const filters = ref<PendingReviewQuery>({ pageNo: 1, pageSize: 10 })
const result = ref<PageResult<PendingReviewResource>>({ records: [], pageNo: 1, pageSize: 10, total: 0, pages: 0 })
const loading = ref(false)
const errorMessage = ref('')
const auditRecords = ref<AuditRecordItem[]>([])
const auditRecordsLoading = ref(false)
const auditRecordsError = ref('')
const auditDialogVisible = ref(false)
const selectedResourceTitle = ref('')
const actionLoadingResourceId = ref<number | null>(null)
const rejectDialogVisible = ref(false)
const rejectReason = ref('')
const rejectValidationMessage = ref('')
const rejectingResource = ref<PendingReviewResource | null>(null)

/** 本页先做最小入口提示，完整游客/普通用户路由守卫统一留到 T42 收口。 */
async function ensureAdmin(): Promise<boolean> {
  session.hydrateSession()

  if (!session.isLoggedIn.value) {
    await router.replace({ name: 'login', query: { redirect: '/admin/reviews' } })
    return false
  }

  if (session.currentUser.value?.role !== 2) {
    errorMessage.value = '此页面仅供管理员演示使用。'
    return false
  }

  return true
}

/** 只携带待审核列表接口已声明的筛选与分页字段。 */
async function loadPendingReviews() {
  if (!await ensureAdmin()) {
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    result.value = await getPendingReviews({ ...filters.value })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '待审核资料加载失败'
  } finally {
    loading.value = false
  }
}

function submitFilters() {
  filters.value.pageNo = 1
  void loadPendingReviews()
}

function resetFilters() {
  filters.value = { pageNo: 1, pageSize: 10 }
  void loadPendingReviews()
}

function changePage(pageNo: number) {
  filters.value.pageNo = pageNo
  void loadPendingReviews()
}

/** 审核流水是独立只读接口，不能从待审核列表字段推断或虚构。 */
async function openAuditRecords(resource: PendingReviewResource) {
  auditDialogVisible.value = true
  selectedResourceTitle.value = resource.title
  auditRecords.value = []
  auditRecordsError.value = ''
  auditRecordsLoading.value = true

  try {
    auditRecords.value = await getAuditRecords(resource.resourceId)
  } catch (error) {
    auditRecordsError.value = error instanceof Error ? error.message : '审核流水加载失败'
  } finally {
    auditRecordsLoading.value = false
  }
}

/** API 文档仅定义了 1/2/3 三种示例值，未知操作保留兜底展示。 */
function getAuditActionLabel(actionType: number): string {
  return ({ 1: '通过', 2: '拒绝', 3: '下架' } as Record<number, string>)[actionType] ?? '未知操作'
}

/** 成功后重新读取待审核队列，让前端不自行伪造后端状态或分页结果。 */
async function refreshAfterAction(message: string) {
  await loadPendingReviews()
  ElMessage.success(message)
}

/** 通过操作必须经管理员二次确认，后端仍会以状态机处理并发冲突。 */
async function approvePendingResource(resource: PendingReviewResource) {
  if (actionLoadingResourceId.value !== null) {
    return
  }

  try {
    await ElMessageBox.confirm(`确认审核通过“${resource.title}”吗？`, '确认审核通过', { type: 'warning' })
  } catch {
    return
  }

  actionLoadingResourceId.value = resource.resourceId
  errorMessage.value = ''

  try {
    await approveResource(resource.resourceId, {})
    await refreshAfterAction('审核已通过，资料已移出待审核列表。')
  } catch (error) {
    // 包括 40901 状态冲突在内的后端业务消息由统一请求层原样保留。
    errorMessage.value = error instanceof Error ? error.message : '审核通过失败'
  } finally {
    actionLoadingResourceId.value = null
  }
}

function openRejectDialog(resource: PendingReviewResource) {
  rejectingResource.value = resource
  rejectReason.value = ''
  rejectValidationMessage.value = ''
  rejectDialogVisible.value = true
}

/** 拒绝原因是后端必填业务规则，前端先做非空提示以避免无效请求。 */
async function submitReject() {
  const resource = rejectingResource.value
  const reason = rejectReason.value.trim()

  if (!resource) {
    return
  }

  if (!reason) {
    rejectValidationMessage.value = '请填写拒绝原因。'
    return
  }

  try {
    await ElMessageBox.confirm(`确认拒绝“${resource.title}”吗？`, '确认审核拒绝', { type: 'warning' })
  } catch {
    return
  }

  actionLoadingResourceId.value = resource.resourceId
  rejectValidationMessage.value = ''
  errorMessage.value = ''

  try {
    await rejectResource(resource.resourceId, { rejectReason: reason })
    rejectDialogVisible.value = false
    await refreshAfterAction('资料已拒绝，已移出待审核列表。')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '审核拒绝失败'
  } finally {
    actionLoadingResourceId.value = null
  }
}

onMounted(() => {
  void loadPendingReviews()
})
</script>

<template>
  <section class="review-management-view">
    <el-card shadow="never">
      <template #header>
        <div class="review-management-view__header">
          <span>待审核资料</span>
          <el-button text :loading="loading" @click="loadPendingReviews">刷新</el-button>
        </div>
      </template>

      <el-alert v-if="errorMessage" class="review-management-view__alert" type="error" :title="errorMessage" :closable="false" show-icon />

      <el-form inline :model="filters" @submit.prevent="submitFilters">
        <el-form-item label="课程">
          <el-input v-model="filters.courseName" data-test="course-filter" placeholder="课程名称" clearable />
        </el-form-item>
        <el-form-item label="资料类型">
          <el-select v-model="filters.resourceType" data-test="type-filter" placeholder="全部类型" clearable>
            <el-option v-for="type in [1, 2, 3, 4, 5, 99]" :key="type" :label="getResourceTypeLabel(type)" :value="type" />
          </el-select>
        </el-form-item>
        <el-form-item label="上传者 ID">
          <el-input-number v-model="filters.uploaderId" data-test="uploader-filter" :min="1" :controls="false" />
        </el-form-item>
        <el-space>
          <el-button type="primary" native-type="submit">筛选</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-space>
      </el-form>

      <el-table v-loading="loading" :data="result.records" empty-text="暂无待审核资料">
        <el-table-column prop="resourceId" label="资料 ID" width="100" />
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column prop="courseName" label="课程" min-width="120" />
        <el-table-column label="类型" width="110"><template #default="scope">{{ getResourceTypeLabel(scope.row.resourceType) }}</template></el-table-column>
        <el-table-column prop="uploaderId" label="上传者 ID" width="120" />
        <el-table-column label="标签" min-width="150"><template #default="scope"><el-tag v-for="tag in scope.row.tags" :key="tag" class="review-management-view__tag" size="small">{{ tag }}</el-tag></template></el-table-column>
        <el-table-column label="创建时间" min-width="170"><template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="scope">
            <el-space>
              <el-button :data-test="`audit-records-${scope.row.resourceId}`" text type="primary" @click="openAuditRecords(scope.row)">查看流水</el-button>
              <el-button :data-test="`approve-${scope.row.resourceId}`" text type="success" :loading="actionLoadingResourceId === scope.row.resourceId" @click="approvePendingResource(scope.row)">通过</el-button>
              <el-button :data-test="`reject-${scope.row.resourceId}`" text type="danger" :loading="actionLoadingResourceId === scope.row.resourceId" @click="openRejectDialog(scope.row)">拒绝</el-button>
            </el-space>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination v-if="result.total > 0" class="review-management-view__pagination" background layout="total, prev, pager, next" :current-page="result.pageNo" :page-size="result.pageSize" :total="result.total" @current-change="changePage" />
    </el-card>

    <el-dialog v-model="auditDialogVisible" :title="`${selectedResourceTitle} 的审核流水`" width="min(760px, 92vw)">
      <el-alert v-if="auditRecordsError" type="error" :title="auditRecordsError" :closable="false" show-icon />
      <el-table v-loading="auditRecordsLoading" :data="auditRecords" empty-text="暂无审核流水">
        <el-table-column prop="auditRecordId" label="记录 ID" width="100" />
        <el-table-column label="操作" width="100"><template #default="scope">{{ getAuditActionLabel(scope.row.actionType) }}</template></el-table-column>
        <el-table-column prop="auditorId" label="审核人 ID" width="120" />
        <el-table-column prop="auditReason" label="原因" min-width="180" />
        <el-table-column label="操作时间" min-width="170"><template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template></el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="rejectDialogVisible" title="填写拒绝原因" width="min(560px, 92vw)">
      <el-alert v-if="rejectValidationMessage" type="warning" :title="rejectValidationMessage" :closable="false" show-icon />
      <el-input v-model="rejectReason" data-test="reject-reason" type="textarea" :rows="4" placeholder="请说明拒绝原因" />
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button data-test="reject-submit" type="danger" :loading="actionLoadingResourceId !== null" @click="submitReject">确认拒绝</el-button>
      </template>
    </el-dialog>
  </section>
</template>
