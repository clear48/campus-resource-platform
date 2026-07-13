<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCategories } from '../../api/categories'
import { searchResources } from '../../api/search'
import { session } from '../../state/session'
import type { PageResult } from '../../types/api'
import type { CategoryItem } from '../../types/category'
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
      </el-table>

      <el-pagination v-if="result.total > 0" class="published-resources-view__pagination" background layout="total, prev, pager, next" :current-page="result.pageNo" :page-size="result.pageSize" :total="result.total" @current-change="changePage" />
    </el-card>
  </section>
</template>
