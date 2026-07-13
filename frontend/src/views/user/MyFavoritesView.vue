<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { removeFavorite } from '../../api/favorites'
import { getMyFavorites } from '../../api/users'
import { session } from '../../state/session'
import type { PageResult } from '../../types/api'
import type { FavoriteResourceItem } from '../../types/favorite'
import { formatDateTime, formatNumber } from '../../utils/format'

const router = useRouter()
const loading = ref(false)
const removingResourceId = ref<number | null>(null)
const errorMessage = ref('')
const result = ref<PageResult<FavoriteResourceItem>>({
  records: [],
  pageNo: 1,
  pageSize: 10,
  total: 0,
  pages: 0,
})

/** 未登录用户不进入个人收藏列表，避免发出必定失败的受保护请求。 */
async function ensureLogin(): Promise<boolean> {
  session.hydrateSession()

  if (session.isLoggedIn.value) {
    return true
  }

  await router.replace({ name: 'login', query: { redirect: '/me/favorites' } })
  return false
}

async function loadFavorites() {
  if (!await ensureLogin()) {
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    result.value = await getMyFavorites({ pageNo: result.value.pageNo, pageSize: result.value.pageSize })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '我的收藏加载失败'
  } finally {
    loading.value = false
  }
}

function changePage(pageNo: number) {
  result.value.pageNo = pageNo
  void loadFavorites()
}

/** 取消收藏以后重新读取后端列表，保证计数和分页仍以后端为准。 */
async function cancelFavorite(resourceId: number) {
  if (removingResourceId.value !== null) {
    return
  }

  removingResourceId.value = resourceId
  errorMessage.value = ''

  try {
    await removeFavorite(resourceId)
    ElMessage.success('已取消收藏')
    await loadFavorites()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '取消收藏失败'
  } finally {
    removingResourceId.value = null
  }
}

onMounted(() => {
  void loadFavorites()
})
</script>

<template>
  <section class="my-favorites-view">
    <el-card shadow="never">
      <template #header>
        <div class="my-favorites-view__header">
          <span>我的收藏</span>
          <el-button text :loading="loading" @click="loadFavorites">刷新</el-button>
        </div>
      </template>

      <el-alert v-if="errorMessage" class="my-favorites-view__alert" type="error" :title="errorMessage" :closable="false" show-icon />

      <el-table v-loading="loading" :data="result.records" empty-text="暂无收藏资料">
        <el-table-column label="资料标题" min-width="220">
          <template #default="scope"><RouterLink :to="{ name: 'resource-detail', params: { resourceId: scope.row.resourceId } }">{{ scope.row.title }}</RouterLink></template>
        </el-table-column>
        <el-table-column prop="courseName" label="课程" min-width="130" />
        <el-table-column label="下载" width="90">
          <template #default="scope">{{ formatNumber(scope.row.downloadCount) }}</template>
        </el-table-column>
        <el-table-column label="收藏" width="90">
          <template #default="scope">{{ formatNumber(scope.row.favoriteCount) }}</template>
        </el-table-column>
        <el-table-column label="资料创建时间" min-width="170">
          <template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="收藏时间" min-width="170">
          <template #default="scope">{{ formatDateTime(scope.row.favoriteAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="scope">
            <el-button :data-test="`remove-favorite-${scope.row.resourceId}`" text type="danger" :loading="removingResourceId === scope.row.resourceId" @click="cancelFavorite(scope.row.resourceId)">取消收藏</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="result.total > 0"
        class="my-favorites-view__pagination"
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
