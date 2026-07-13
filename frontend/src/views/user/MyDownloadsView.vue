<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createDownloadRecord, downloadFile } from '../../api/downloads'
import { getMyDownloadRecords } from '../../api/users'
import { session } from '../../state/session'
import type { PageResult } from '../../types/api'
import type { DownloadRecordItem } from '../../types/download'
import { formatDateTime } from '../../utils/format'
import { saveDownloadBlob } from '../../utils/file-download'

const router = useRouter()
const loading = ref(false)
const redownloadingResourceId = ref<number | null>(null)
const errorMessage = ref('')
const downloadNotice = ref('')
const result = ref<PageResult<DownloadRecordItem>>({
  records: [],
  pageNo: 1,
  pageSize: 10,
  total: 0,
  pages: 0,
})

/** 未登录用户不请求下载记录，登录后可按返回地址回到当前页面。 */
async function ensureLogin(): Promise<boolean> {
  session.hydrateSession()

  if (session.isLoggedIn.value) {
    return true
  }

  await router.replace({ name: 'login', query: { redirect: '/me/downloads' } })
  return false
}

async function loadDownloads() {
  if (!await ensureLogin()) {
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    result.value = await getMyDownloadRecords({ pageNo: result.value.pageNo, pageSize: result.value.pageSize })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '我的下载加载失败'
  } finally {
    loading.value = false
  }
}

function changePage(pageNo: number) {
  result.value.pageNo = pageNo
  void loadDownloads()
}

/** 再次下载仍创建新下载记录，必须由后端执行限流、去重和统计判定。 */
async function redownload(resourceId: number) {
  if (redownloadingResourceId.value !== null) {
    return
  }

  redownloadingResourceId.value = resourceId
  errorMessage.value = ''
  downloadNotice.value = ''

  try {
    const record = await createDownloadRecord(resourceId)
    const file = await downloadFile(record.downloadRecordId)

    // fileName 已由下载 API 从响应头解析，不能前端猜测或拼接存储路径。
    saveDownloadBlob(file.blob, file.fileName)
    downloadNotice.value = record.counted ? '下载已开始，本次下载已计入统计。' : '下载已开始，重复下载未重复计入统计。'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '下载失败'
  } finally {
    redownloadingResourceId.value = null
  }
}

function getDownloadStatusLabel(status: number): string {
  return status === 1 ? '成功' : status === 2 ? '失败' : '未知'
}

onMounted(() => {
  void loadDownloads()
})
</script>

<template>
  <section class="my-downloads-view">
    <el-card shadow="never">
      <template #header>
        <div class="my-downloads-view__header">
          <span>我的下载</span>
          <el-button text :loading="loading" @click="loadDownloads">刷新</el-button>
        </div>
      </template>

      <el-alert v-if="errorMessage" class="my-downloads-view__alert" type="error" :title="errorMessage" :closable="false" show-icon />
      <el-alert v-if="downloadNotice" class="my-downloads-view__alert" type="success" :title="downloadNotice" :closable="false" show-icon />

      <el-table v-loading="loading" :data="result.records" empty-text="暂无下载记录">
        <el-table-column prop="downloadRecordId" label="记录 ID" width="100" />
        <el-table-column label="资料标题" min-width="220">
          <template #default="scope"><RouterLink :to="{ name: 'resource-detail', params: { resourceId: scope.row.resourceId } }">{{ scope.row.title }}</RouterLink></template>
        </el-table-column>
        <el-table-column prop="fileId" label="文件 ID" width="100" />
        <el-table-column label="下载状态" width="110">
          <template #default="scope"><el-tag :type="scope.row.downloadStatus === 1 ? 'success' : 'danger'">{{ getDownloadStatusLabel(scope.row.downloadStatus) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="下载时间" min-width="170">
          <template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="scope">
            <el-button :data-test="`redownload-${scope.row.resourceId}`" text type="primary" :loading="redownloadingResourceId === scope.row.resourceId" @click="redownload(scope.row.resourceId)">再次下载</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="result.total > 0"
        class="my-downloads-view__pagination"
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
