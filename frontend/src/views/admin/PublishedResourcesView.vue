<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { getAuditRecords, offlineResource } from '../../api/admin/resources'
import { getCategories } from '../../api/categories'
import { searchResources } from '../../api/search'
import { session } from '../../state/session'
import type { PageResult } from '../../types/api'
import type { CategoryItem } from '../../types/category'
import type { AuditRecordItem } from '../../types/audit'
import type { SearchResourceItem, SearchResourceQuery } from '../../types/search'
import { getResourceTypeLabel } from '../../types/enums'
import { formatDateTime, formatNumber } from '../../utils/format'

const router = useRouter()
const categories = ref<CategoryItem[]>([])
const filters = ref<SearchResourceQuery>({ sortBy: 'createdAt', order: 'desc', pageNo: 1, pageSize: 10 })
const result = ref<PageResult<SearchResourceItem>>({ records: [], pageNo: 1, pageSize: 10, total: 0, pages: 0 })
const loading = ref(false)
const categoryError = ref('')
const errorMessage = ref('')
const actionLoadingResourceId = ref<number | null>(null)
const auditDialogVisible = ref(false)
const auditRecords = ref<AuditRecordItem[]>([])
const auditRecordsLoading = ref(false)
const auditRecordsError = ref('')
const selectedResourceTitle = ref('')
const offlineDialogVisible = ref(false)
const offlineReason = ref('')
const offlineValidationMessage = ref('')
const offliningResource = ref<SearchResourceItem | null>(null)

/** 管理端入口提示保持最小化；统一路由守卫仍在 T42 集中实现。 */
async function ensureAdmin(): Promise<boolean> {
  session.hydrateSession()

  if (!session.isLoggedIn.value) {
    await router.replace({ name: 'login', query: { redirect: '/admin/resources' } })
    return false
  }

  if (session.currentUser.value?.role !== 2) {
    errorMessage.value = '此页面仅供管理员演示使用。'
    return false
  }

  return true
}

async function loadCategories() {
  categoryError.value = ''

  try {
    categories.value = await getCategories({ parentId: 0 })
  } catch (error) {
    categoryError.value = error instanceof Error ? error.message : '分类加载失败'
  }
}

/** 公开搜索接口在后端固定限制 APPROVED，页面无需也不能自行传入资料状态。 */
async function loadPublishedResources() {
  if (!await ensureAdmin()) {
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    result.value = await searchResources({
      ...filters.value,
      keyword: filters.value.keyword?.trim() || undefined,
      courseName: filters.value.courseName?.trim() || undefined,
    })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '发布资料加载失败'
  } finally {
    loading.value = false
  }
}

function submitFilters() {
  filters.value.pageNo = 1
  void loadPublishedResources()
}

function resetFilters() {
  filters.value = { sortBy: 'createdAt', order: 'desc', pageNo: 1, pageSize: 10 }
  void loadPublishedResources()
}

function changePage(pageNo: number) {
  filters.value.pageNo = pageNo
  void loadPublishedResources()
}

/** 审核流水必须使用管理员只读接口获取，不能从公开搜索结果推断。 */
async function openAuditRecords(resource: SearchResourceItem) {
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

function openOfflineDialog(resource: SearchResourceItem) {
  offliningResource.value = resource
  offlineReason.value = ''
  offlineValidationMessage.value = ''
  offlineDialogVisible.value = true
}

/** 下架原因是后端强制业务规则，前端先提示空值以避免无效请求。 */
async function submitOffline() {
  const resource = offliningResource.value
  const reason = offlineReason.value.trim()

  if (!resource) {
    return
  }

  if (!reason) {
    offlineValidationMessage.value = '请填写下架原因。'
    return
  }

  try {
    await ElMessageBox.confirm(`确认下架“${resource.title}”吗？`, '确认下架资料', { type: 'warning' })
  } catch {
    return
  }

  actionLoadingResourceId.value = resource.resourceId
  offlineValidationMessage.value = ''
  errorMessage.value = ''

  try {
    await offlineResource(resource.resourceId, { offlineReason: reason })
    offlineDialogVisible.value = false
    // 公开搜索只返回 APPROVED，重新查询后下架资料将自然退出当前列表。
    await loadPublishedResources()
    ElMessage.success('资料已下架，已移出公开列表。')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '资料下架失败'
  } finally {
    actionLoadingResourceId.value = null
  }
}

/** API 文档只列举 1/2/3，其他动作保持未知兜底。 */
function getAuditActionLabel(actionType: number): string {
  return ({ 1: '通过', 2: '拒绝', 3: '下架' } as Record<number, string>)[actionType] ?? '未知操作'
}

onMounted(() => {
  void loadCategories()
  void loadPublishedResources()
})
</script>

<template>
  <section class="published-resources-view">
    <el-card shadow="never">
      <template #header>
        <div class="published-resources-view__header">
          <span>已发布资料</span>
          <el-button text :loading="loading" @click="loadPublishedResources">刷新</el-button>
        </div>
      </template>

      <el-alert v-if="categoryError" class="published-resources-view__alert" type="warning" :title="`分类筛选不可用：${categoryError}`" :closable="false" show-icon />
      <el-alert v-if="errorMessage" class="published-resources-view__alert" type="error" :title="errorMessage" :closable="false" show-icon />

      <el-form inline :model="filters" @submit.prevent="submitFilters">
        <el-form-item label="关键词"><el-input v-model="filters.keyword" placeholder="标题、简介或标签" clearable /></el-form-item>
        <el-form-item label="分类"><el-select v-model="filters.categoryId" clearable placeholder="全部分类"><el-option v-for="category in categories" :key="category.categoryId" :label="category.categoryName" :value="category.categoryId" /></el-select></el-form-item>
        <el-form-item label="课程"><el-input v-model="filters.courseName" placeholder="课程名称" clearable /></el-form-item>
        <el-form-item label="类型"><el-select v-model="filters.resourceType" clearable placeholder="全部类型"><el-option v-for="type in [1, 2, 3, 4, 5, 99]" :key="type" :label="getResourceTypeLabel(type)" :value="type" /></el-select></el-form-item>
        <el-space><el-button type="primary" native-type="submit">筛选</el-button><el-button @click="resetFilters">重置</el-button></el-space>
      </el-form>

      <el-table v-loading="loading" :data="result.records" empty-text="暂无已发布资料">
        <el-table-column label="资料标题" min-width="220"><template #default="scope"><RouterLink :to="{ name: 'resource-detail', params: { resourceId: scope.row.resourceId } }">{{ scope.row.title }}</RouterLink></template></el-table-column>
        <el-table-column prop="courseName" label="课程" min-width="120" />
        <el-table-column label="类型" width="110"><template #default="scope">{{ getResourceTypeLabel(scope.row.resourceType) }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default><el-tag type="success">已通过</el-tag></template></el-table-column>
        <el-table-column label="下载" width="90"><template #default="scope">{{ formatNumber(scope.row.downloadCount) }}</template></el-table-column>
        <el-table-column label="收藏" width="90"><template #default="scope">{{ formatNumber(scope.row.favoriteCount) }}</template></el-table-column>
        <el-table-column label="创建时间" min-width="170"><template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="scope">
            <el-space>
              <el-button :data-test="`published-audit-records-${scope.row.resourceId}`" text type="primary" @click="openAuditRecords(scope.row)">查看流水</el-button>
              <el-button :data-test="`offline-${scope.row.resourceId}`" text type="danger" :loading="actionLoadingResourceId === scope.row.resourceId" @click="openOfflineDialog(scope.row)">下架</el-button>
            </el-space>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination v-if="result.total > 0" class="published-resources-view__pagination" background layout="total, prev, pager, next" :current-page="result.pageNo" :page-size="result.pageSize" :total="result.total" @current-change="changePage" />
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

    <el-dialog v-model="offlineDialogVisible" title="填写下架原因" width="min(560px, 92vw)">
      <el-alert v-if="offlineValidationMessage" type="warning" :title="offlineValidationMessage" :closable="false" show-icon />
      <el-input v-model="offlineReason" data-test="offline-reason" type="textarea" :rows="4" placeholder="请说明下架原因" />
      <template #footer>
        <el-button @click="offlineDialogVisible = false">取消</el-button>
        <el-button data-test="offline-submit" type="danger" :loading="actionLoadingResourceId !== null" @click="submitOffline">确认下架</el-button>
      </template>
    </el-dialog>
  </section>
</template>
