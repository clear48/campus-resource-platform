<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCategories } from '../api/categories'
import { searchResources } from '../api/search'
import type { PageResult } from '../types/api'
import type { CategoryItem } from '../types/category'
import type { SearchResourceItem, SearchResourceQuery, SearchResourceSortBy } from '../types/search'
import { getResourceTypeLabel } from '../types/enums'
import { formatDateTime, formatNumber } from '../utils/format'

const route = useRoute()
const router = useRouter()
const categories = ref<CategoryItem[]>([])
const results = ref<PageResult<SearchResourceItem>>({
  records: [],
  pageNo: 1,
  pageSize: 10,
  total: 0,
  pages: 0,
})
const loading = ref(false)
const categoryError = ref('')
const searchError = ref('')

// 页面自己的初始分页值会明确传给后端，不依赖文档中未说明的服务端分页默认值。
const filters = ref<SearchResourceQuery>({
  keyword: typeof route.query.keyword === 'string' ? route.query.keyword : '',
  sortBy: 'createdAt',
  order: 'desc',
  pageNo: 1,
  pageSize: 10,
})

const sortOptions: Array<{ value: SearchResourceSortBy; label: string }> = [
  { value: 'createdAt', label: '最新创建' },
  { value: 'hotScore', label: '热度最高' },
  { value: 'downloadCount', label: '下载最多' },
  { value: 'favoriteCount', label: '收藏最多' },
]

const resultStart = computed(() => (results.value.total === 0 ? 0 : (results.value.pageNo - 1) * results.value.pageSize + 1))

function buildQuery(): SearchResourceQuery {
  const keyword = filters.value.keyword?.trim()

  return {
    ...filters.value,
    keyword: keyword || undefined,
  }
}

async function loadCategories() {
  categoryError.value = ''

  try {
    categories.value = await getCategories({ parentId: 0 })
  } catch (error) {
    categoryError.value = error instanceof Error ? error.message : '分类加载失败'
  }
}

async function loadResources() {
  loading.value = true
  searchError.value = ''

  try {
    results.value = await searchResources(buildQuery())
  } catch (error) {
    searchError.value = error instanceof Error ? error.message : '资料搜索失败'
  } finally {
    loading.value = false
  }
}

function submitSearch() {
  filters.value.pageNo = 1
  const keyword = filters.value.keyword?.trim()

  // 只同步关键词到 URL，方便演示页刷新或分享时复现入口搜索条件。
  void router.replace({
    name: 'search',
    query: keyword ? { keyword } : {},
  })
  void loadResources()
}

function resetFilters() {
  filters.value = {
    keyword: '',
    sortBy: 'createdAt',
    order: 'desc',
    pageNo: 1,
    pageSize: 10,
  }
  void router.replace({ name: 'search' })
  void loadResources()
}

function changePage(pageNo: number) {
  filters.value.pageNo = pageNo
  void loadResources()
}

onMounted(() => {
  void loadCategories()
  void loadResources()
})
</script>

<template>
  <section class="search-view">
    <el-card shadow="never">
      <template #header>
        <div class="search-view__header">
          <span>公开资料搜索</span>
          <span class="search-view__summary">当前展示第 {{ resultStart }} 条起的已审核通过资料</span>
        </div>
      </template>

      <el-alert
        v-if="categoryError"
        class="search-view__alert"
        type="warning"
        :title="`分类筛选不可用：${categoryError}`"
        :closable="false"
        show-icon
      />
      <el-alert v-if="searchError" class="search-view__alert" type="error" :title="searchError" :closable="false" show-icon />

      <el-form class="search-view__form" :model="filters" label-position="top" @submit.prevent="submitSearch">
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12" :lg="8">
            <el-form-item label="关键词">
              <el-input v-model="filters.keyword" placeholder="标题、简介或标签" clearable @keyup.enter="submitSearch" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8">
            <el-form-item label="分类">
              <el-select v-model="filters.categoryId" placeholder="全部分类" clearable class="search-view__control">
                <el-option v-for="category in categories" :key="category.categoryId" :label="category.categoryName" :value="category.categoryId" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8">
            <el-form-item label="课程名称">
              <el-input v-model="filters.courseName" placeholder="例如：数据结构" clearable />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8">
            <el-form-item label="资料类型">
              <el-select v-model="filters.resourceType" placeholder="全部类型" clearable class="search-view__control">
                <el-option v-for="type in [1, 2, 3, 4, 5, 99]" :key="type" :label="getResourceTypeLabel(type)" :value="type" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="8">
            <el-form-item label="标签">
              <el-input v-model="filters.tag" placeholder="例如：复习" clearable />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="4">
            <el-form-item label="排序字段">
              <el-select v-model="filters.sortBy" class="search-view__control">
                <el-option v-for="option in sortOptions" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="4">
            <el-form-item label="排序方向">
              <el-radio-group v-model="filters.order">
                <el-radio value="desc">降序</el-radio>
                <el-radio value="asc">升序</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>

        <el-space>
          <el-button type="primary" native-type="submit">搜索资料</el-button>
          <el-button @click="resetFilters">重置筛选</el-button>
        </el-space>
      </el-form>
    </el-card>

    <el-card class="search-view__results" shadow="never">
      <el-table v-loading="loading" :data="results.records" empty-text="暂无符合条件的公开资料">
        <el-table-column prop="title" label="资料标题" min-width="190" />
        <el-table-column prop="courseName" label="课程" min-width="120" />
        <el-table-column label="类型" width="110">
          <template #default="scope">{{ getResourceTypeLabel(scope.row.resourceType) }}</template>
        </el-table-column>
        <el-table-column label="标签" min-width="150">
          <template #default="scope">
            <el-tag v-for="tag in scope.row.tags" :key="tag" class="search-view__tag" size="small">{{ tag }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="下载" width="90">
          <template #default="scope">{{ formatNumber(scope.row.downloadCount) }}</template>
        </el-table-column>
        <el-table-column label="收藏" width="90">
          <template #default="scope">{{ formatNumber(scope.row.favoriteCount) }}</template>
        </el-table-column>
        <el-table-column label="热度" width="90">
          <template #default="scope">{{ formatNumber(scope.row.hotScore) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170">
          <template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="results.total > 0"
        class="search-view__pagination"
        background
        layout="total, prev, pager, next"
        :current-page="results.pageNo"
        :page-size="results.pageSize"
        :total="results.total"
        @current-change="changePage"
      />
    </el-card>
  </section>
</template>
