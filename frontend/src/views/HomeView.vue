<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getHotResources, getHotSearchKeywords } from '../api/rankings'
import type {
  HotResourceRankingItem,
  HotSearchKeywordRankingItem,
  ResourceRankingPeriod,
  SearchKeywordRankingPeriod,
} from '../types/ranking'
import { formatNumber } from '../utils/format'

const resourcePeriod = ref<ResourceRankingPeriod>('all')
const keywordPeriod = ref<SearchKeywordRankingPeriod>('daily')
const hotResources = ref<HotResourceRankingItem[]>([])
const hotKeywords = ref<HotSearchKeywordRankingItem[]>([])
const resourceLoading = ref(false)
const keywordLoading = ref(false)
const resourceError = ref('')
const keywordError = ref('')

const resourcePeriods: Array<{ value: ResourceRankingPeriod; label: string }> = [
  { value: 'daily', label: '日榜' },
  { value: 'weekly', label: '周榜' },
  { value: 'monthly', label: '月榜' },
  { value: 'all', label: '总榜' },
]

const keywordPeriods: Array<{ value: SearchKeywordRankingPeriod; label: string }> = [
  { value: 'daily', label: '日榜' },
  { value: 'weekly', label: '周榜' },
  { value: 'monthly', label: '月榜' },
]

async function loadHotResources() {
  resourceLoading.value = true
  resourceError.value = ''

  try {
    hotResources.value = await getHotResources({ limit: 10, period: resourcePeriod.value })
  } catch (error) {
    resourceError.value = error instanceof Error ? error.message : '热门资料加载失败'
  } finally {
    resourceLoading.value = false
  }
}

async function loadHotKeywords() {
  keywordLoading.value = true
  keywordError.value = ''

  try {
    // 后端 Redis 降级为空数组时直接显示空状态，不把运营数据缺失误展示成系统故障。
    hotKeywords.value = await getHotSearchKeywords({ limit: 10, period: keywordPeriod.value })
  } catch (error) {
    keywordError.value = error instanceof Error ? error.message : '热门搜索词加载失败'
  } finally {
    keywordLoading.value = false
  }
}

function switchResourcePeriod(period: ResourceRankingPeriod) {
  resourcePeriod.value = period
  void loadHotResources()
}

function switchKeywordPeriod(period: SearchKeywordRankingPeriod) {
  keywordPeriod.value = period
  void loadHotKeywords()
}

onMounted(() => {
  void loadHotResources()
  void loadHotKeywords()
})
</script>

<template>
  <section class="home-view">
    <el-card class="home-view__intro" shadow="never">
      <p class="eyebrow">Campus Resource Platform</p>
      <h1>校园资料共享与智能检索平台</h1>
      <p class="description">通过热门榜单演示 Redis ZSet 实时排行与后端降级能力。</p>
    </el-card>

    <el-row :gutter="20">
      <el-col :xs="24" :lg="14">
        <el-card shadow="never">
          <template #header>
            <div class="ranking-card__header">
              <span>热门资料</span>
              <el-button-group>
                <el-button
                  v-for="period in resourcePeriods"
                  :key="period.value"
                  :type="resourcePeriod === period.value ? 'primary' : 'default'"
                  @click="switchResourcePeriod(period.value)"
                >
                  {{ period.label }}
                </el-button>
              </el-button-group>
            </div>
          </template>

          <el-alert v-if="resourceError" class="ranking-card__alert" type="error" :title="resourceError" :closable="false" show-icon />
          <el-table v-else v-loading="resourceLoading" :data="hotResources" empty-text="暂无热门资料">
            <el-table-column prop="rank" label="排名" width="70" />
            <el-table-column prop="title" label="资料标题" min-width="180" />
            <el-table-column prop="courseName" label="课程" min-width="110" />
            <el-table-column label="下载" width="90">
              <template #default="scope">{{ formatNumber(scope.row.downloadCount) }}</template>
            </el-table-column>
            <el-table-column label="热度" width="100">
              <template #default="scope">{{ formatNumber(scope.row.hotScore) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="10" class="home-view__keyword-column">
        <el-card shadow="never">
          <template #header>
            <div class="ranking-card__header">
              <span>热门搜索词</span>
              <el-button-group>
                <el-button
                  v-for="period in keywordPeriods"
                  :key="period.value"
                  :type="keywordPeriod === period.value ? 'primary' : 'default'"
                  @click="switchKeywordPeriod(period.value)"
                >
                  {{ period.label }}
                </el-button>
              </el-button-group>
            </div>
          </template>

          <el-alert v-if="keywordError" class="ranking-card__alert" type="error" :title="keywordError" :closable="false" show-icon />
          <el-table v-else v-loading="keywordLoading" :data="hotKeywords" empty-text="暂无热门搜索词">
            <el-table-column prop="rank" label="排名" width="70" />
            <el-table-column prop="keyword" label="关键词" min-width="130" />
            <el-table-column label="搜索次数" width="110">
              <template #default="scope">{{ formatNumber(scope.row.searchCount) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </section>
</template>
