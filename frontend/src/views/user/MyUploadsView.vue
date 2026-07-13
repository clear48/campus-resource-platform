<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getMyResources } from '../../api/users'
import { session } from '../../state/session'
import type { PageResult } from '../../types/api'
import type { MyResourceItem } from '../../types/resource'
import { getResourceStatusLabel } from '../../types/enums'
import { formatDateTime } from '../../utils/format'

const router = useRouter()
const selectedStatus = ref<number | undefined>()
const loading = ref(false)
const errorMessage = ref('')
const result = ref<PageResult<MyResourceItem>>({
  records: [],
  pageNo: 1,
  pageSize: 10,
  total: 0,
  pages: 0,
})

const statusOptions = [
  { value: 0, label: '待审核' },
  { value: 1, label: '已通过' },
  { value: 2, label: '已拒绝' },
  { value: 3, label: '已下架' },
  { value: 4, label: '已删除' },
]

/** 登录态缺失时不请求个人列表，直接回到登录页并保留返回地址。 */
async function ensureLogin(): Promise<boolean> {
  session.hydrateSession()

  if (session.isLoggedIn.value) {
    return true
  }

  await router.replace({ name: 'login', query: { redirect: '/me/uploads' } })
  return false
}

/** 只传递接口文档已声明的状态和分页参数。 */
async function loadMyUploads() {
  if (!await ensureLogin()) {
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    result.value = await getMyResources({
      status: selectedStatus.value,
      pageNo: result.value.pageNo,
      pageSize: result.value.pageSize,
    })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '我的上传加载失败'
  } finally {
    loading.value = false
  }
}

function changeStatus() {
  result.value.pageNo = 1
  void loadMyUploads()
}

function changePage(pageNo: number) {
  result.value.pageNo = pageNo
  void loadMyUploads()
}

/** 拒绝和下架原因只在对应状态显示，不为其他状态虚构原因。 */
function getStatusReason(item: MyResourceItem): string {
  if (item.status === 2) {
    return item.rejectReason || '--'
  }

  if (item.status === 3) {
    return item.offlineReason || '--'
  }

  return '--'
}

function getStatusTagType(status: number): 'info' | 'success' | 'danger' | 'warning' {
  if (status === 1) return 'success'
  if (status === 2) return 'danger'
  if (status === 3 || status === 4) return 'info'
  return 'warning'
}

onMounted(() => {
  void loadMyUploads()
})
</script>

<template>
  <section class="my-uploads-view">
    <el-card shadow="never">
      <template #header>
        <div class="my-uploads-view__header">
          <span>我的上传</span>
          <el-button text :loading="loading" @click="loadMyUploads">刷新</el-button>
        </div>
      </template>

      <el-alert v-if="errorMessage" class="my-uploads-view__alert" type="error" :title="errorMessage" :closable="false" show-icon />

      <el-form inline>
        <el-form-item label="审核状态">
          <el-select v-model="selectedStatus" data-test="upload-status-filter" clearable placeholder="全部状态" @change="changeStatus">
            <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="result.records" empty-text="暂无上传资料">
        <el-table-column prop="title" label="资料标题" min-width="200" />
        <el-table-column prop="courseName" label="课程" min-width="130" />
        <el-table-column label="状态" width="110">
          <template #default="scope"><el-tag :type="getStatusTagType(scope.row.status)">{{ getResourceStatusLabel(scope.row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="拒绝/下架原因" min-width="190">
          <template #default="scope">{{ getStatusReason(scope.row) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170">
          <template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="result.total > 0"
        class="my-uploads-view__pagination"
        background
        layout="total, prev, pager, next"
        :current-page="result.pageNo"
        :page-size="result.pageSize"
        :total="result.total"
        @current-change="changePage"
      />
    </el-card>
  </section>
</template>
