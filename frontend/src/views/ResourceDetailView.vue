<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { addFavorite, getFavoriteStatus, removeFavorite } from '../api/favorites'
import { createDownloadRecord, downloadFile } from '../api/downloads'
import { getResourceDetail } from '../api/resources'
import { session } from '../state/session'
import type { ResourceDetail } from '../types/resource'
import { getResourceStatusLabel, getResourceTypeLabel } from '../types/enums'
import { formatDateTime, formatNumber } from '../utils/format'
import { saveDownloadBlob } from '../utils/file-download'

const route = useRoute()
const router = useRouter()
const detail = ref<ResourceDetail | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const favorited = ref(false)
const favoriteLoading = ref(false)
const favoriteError = ref('')
// 将嵌套会话计算属性提升为顶层绑定，确保模板按布尔值而非 Ref 对象判断。
const isLoggedIn = session.isLoggedIn
const downloadLoading = ref(false)
const downloadError = ref('')
const downloadNotice = ref('')

function getResourceId(): number | null {
  const rawResourceId = route.params.resourceId
  const resourceId = Number(rawResourceId)

  return Number.isInteger(resourceId) && resourceId > 0 ? resourceId : null
}

async function loadDetail() {
  const resourceId = getResourceId()

  if (!resourceId) {
    errorMessage.value = '资料 ID 不合法'
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    // 公开详情由后端严格限制为 APPROVED，前端只负责呈现真实返回字段。
    detail.value = await getResourceDetail(resourceId)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '资料详情加载失败'
  } finally {
    loading.value = false
  }
}

/** 仅在已有会话时读取收藏关系，游客不应调用受保护的收藏状态接口。 */
async function loadFavoriteStatus() {
  const resourceId = getResourceId()

  if (!resourceId || !session.isLoggedIn.value) {
    return
  }

  favoriteError.value = ''

  try {
    favorited.value = (await getFavoriteStatus(resourceId)).favorited
  } catch (error) {
    favoriteError.value = error instanceof Error ? error.message : '收藏状态加载失败'
  }
}

/** 切换收藏前先处理游客入口，成功后以服务端返回的收藏数为准。 */
async function toggleFavorite() {
  const resourceId = getResourceId()

  if (!resourceId) {
    errorMessage.value = '资料 ID 不合法'
    return
  }

  if (!session.isLoggedIn.value) {
    void router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }

  favoriteLoading.value = true
  favoriteError.value = ''

  try {
    const result = favorited.value ? await removeFavorite(resourceId) : await addFavorite(resourceId)
    favorited.value = result.favorited

    if (detail.value) {
      detail.value.favoriteCount = result.favoriteCount
    }

    ElMessage.success(result.favorited ? '已收藏资料' : '已取消收藏')
  } catch (error) {
    favoriteError.value = error instanceof Error ? error.message : '收藏操作失败'
  } finally {
    favoriteLoading.value = false
  }
}

/** 严格保持后端定义的两步下载链路，避免前端直接拼接任何存储路径。 */
async function startDownload() {
  const resourceId = getResourceId()

  if (!resourceId) {
    errorMessage.value = '资料 ID 不合法'
    return
  }

  if (!session.isLoggedIn.value) {
    void router.push({ name: 'login', query: { redirect: route.fullPath } })
    return
  }

  downloadLoading.value = true
  downloadError.value = ''
  downloadNotice.value = ''

  try {
    const record = await createDownloadRecord(resourceId)
    const file = await downloadFile(record.downloadRecordId)

    saveDownloadBlob(file.blob, file.fileName)
    downloadNotice.value = record.counted ? '下载已开始，本次下载已计入统计。' : '下载已开始，重复下载未重复计入统计。'
  } catch (error) {
    // 42901 等业务错误在请求层已保留后端 message，页面不伪造限流文案。
    downloadError.value = error instanceof Error ? error.message : '下载失败'
  } finally {
    downloadLoading.value = false
  }
}

function goToSearch() {
  void router.push({ name: 'search' })
}

onMounted(() => {
  void loadDetail()
  void loadFavoriteStatus()
})
</script>

<template>
  <section class="resource-detail-view" v-loading="loading">
    <el-alert v-if="errorMessage" type="error" :title="errorMessage" :closable="false" show-icon>
      <template #default>
        <el-button type="primary" link @click="goToSearch">返回资料搜索</el-button>
      </template>
    </el-alert>

    <el-card v-else-if="detail" shadow="never">
      <template #header>
        <div class="resource-detail-view__header">
          <div>
            <p class="eyebrow">公开资料详情</p>
            <h1>{{ detail.title }}</h1>
          </div>
          <el-tag type="success">{{ getResourceStatusLabel(detail.status) }}</el-tag>
        </div>
      </template>

      <p class="resource-detail-view__description">{{ detail.description || '暂无资料简介' }}</p>

      <el-alert
        v-if="favoriteError"
        class="resource-detail-view__favorite-alert"
        type="warning"
        :title="favoriteError"
        :closable="false"
        show-icon
      />
      <el-alert v-if="downloadError" class="resource-detail-view__favorite-alert" type="error" :title="downloadError" :closable="false" show-icon />
      <el-alert v-if="downloadNotice" class="resource-detail-view__favorite-alert" type="success" :title="downloadNotice" :closable="false" show-icon />

      <el-descriptions :column="2" border>
        <el-descriptions-item label="资料 ID">{{ detail.resourceId }}</el-descriptions-item>
        <el-descriptions-item label="分类">{{ detail.categoryName }}</el-descriptions-item>
        <el-descriptions-item label="课程">{{ detail.courseName }}</el-descriptions-item>
        <el-descriptions-item label="资料类型">{{ getResourceTypeLabel(detail.resourceType) }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDateTime(detail.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="标签">
          <el-tag v-for="tag in detail.tags" :key="tag" class="resource-detail-view__tag" size="small">{{ tag }}</el-tag>
          <span v-if="detail.tags.length === 0">--</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-row class="resource-detail-view__statistics" :gutter="16">
        <el-col :xs="24" :sm="8">
          <el-statistic title="下载量" :value="detail.downloadCount" />
        </el-col>
        <el-col :xs="24" :sm="8">
          <el-statistic title="收藏量" :value="detail.favoriteCount" />
        </el-col>
        <el-col :xs="24" :sm="8">
          <el-statistic title="热度分" :value="detail.hotScore" />
        </el-col>
      </el-row>

      <p class="resource-detail-view__notice">下载 {{ formatNumber(detail.downloadCount) }} 次。</p>
      <el-button
        data-test="favorite-button"
        :type="favorited ? 'warning' : 'primary'"
        :loading="favoriteLoading"
        :disabled="favoriteLoading"
        @click="toggleFavorite"
      >
        {{ isLoggedIn ? (favorited ? '取消收藏' : '收藏资料') : '登录后收藏' }}
      </el-button>
      <el-button data-test="download-button" type="success" :loading="downloadLoading" :disabled="downloadLoading" @click="startDownload">
        {{ isLoggedIn ? '下载资料' : '登录后下载' }}
      </el-button>
      <el-button @click="goToSearch">返回资料搜索</el-button>
    </el-card>
  </section>
</template>
