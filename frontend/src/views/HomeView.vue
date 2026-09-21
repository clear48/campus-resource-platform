<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getHotResources, getHotSearchKeywords } from '../api/rankings'
import type {
  HotResourceRankingItem,
  HotSearchKeywordRankingItem,
  ResourceRankingPeriod,
  SearchKeywordRankingPeriod,
} from '../types/ranking'
import { formatNumber } from '../utils/format'

const router = useRouter()
const resourcePeriod = ref<ResourceRankingPeriod>('all')
const keywordPeriod = ref<SearchKeywordRankingPeriod>('daily')
const hotResources = ref<HotResourceRankingItem[]>([])
const hotKeywords = ref<HotSearchKeywordRankingItem[]>([])
const resourceLoading = ref(false)
const keywordLoading = ref(false)
const resourceError = ref('')
const keywordError = ref('')
const searchKeyword = ref('')

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

/**
 * 首页搜索统一由表单提交，确保回车和点击按钮都在 SPA 内完成相同跳转。
 */
function submitSearch() {
  const keyword = searchKeyword.value.trim()
  void router.push({ path: '/search', query: keyword ? { keyword } : {} })
}

onMounted(() => {
  void loadHotResources()
  void loadHotKeywords()
})
</script>

<template>
  <section class="home-view">
    <section class="home-hero" aria-labelledby="home-hero-title">
      <div class="home-hero__content">
        <p class="eyebrow">Campus Resource Platform</p>
        <h1 id="home-hero-title">让校园知识被看见，<span>也更容易抵达</span></h1>
        <p class="home-hero__description">汇集课程笔记、复习提纲与学习资料，在审核后公开共享，通过检索与实时热榜快速找到所需内容。</p>

        <form class="home-hero__search" role="search" aria-label="站内资料搜索" @submit.prevent="submitSearch">
          <el-input v-model="searchKeyword" size="large" placeholder="搜索课程、资料标题或关键词" clearable aria-label="搜索课程、资料标题或关键词" />
          <button class="home-hero__search-action" type="submit">搜索资料</button>
        </form>

        <div class="home-hero__actions">
          <RouterLink class="portal-button portal-button--primary" to="/upload">上传资料</RouterLink>
          <RouterLink class="portal-button portal-button--secondary" to="/search">浏览资料库</RouterLink>
        </div>

        <ul class="home-hero__capabilities" aria-label="平台能力">
          <li><span aria-hidden="true">✓</span>审核后公开</li>
          <li><span aria-hidden="true">✓</span>Redis 实时热榜</li>
          <li><span aria-hidden="true">✓</span>文件 MD5 去重</li>
        </ul>
      </div>
      <div class="home-hero__visual" aria-hidden="true">
        <span class="home-hero__glow"></span>
        <img src="../assets/hero.png" alt="" />
        <span class="home-hero__floating-card home-hero__floating-card--top">知识共享</span>
        <span class="home-hero__floating-card home-hero__floating-card--bottom">快速检索</span>
      </div>
    </section>

    <div class="home-view__section-heading">
      <div>
        <p class="eyebrow">Discover</p>
        <h2>校园热门内容</h2>
        <p>热门资料由公开状态校验后展示；搜索热词在 Redis 不可用时安全降级。</p>
      </div>
      <RouterLink class="home-view__more-link" to="/search">查看全部资料 →</RouterLink>
    </div>

    <el-row :gutter="24">
      <el-col :xs="24" :lg="14">
        <el-card class="ranking-card" shadow="never">
          <template #header>
            <div class="ranking-card__header">
              <div>
                <strong>热门资料</strong>
                <small>下载与收藏行为共同反映内容热度</small>
              </div>
              <el-button-group aria-label="热门资料榜单周期">
                <el-button
                  v-for="period in resourcePeriods"
                  :key="period.value"
                  :type="resourcePeriod === period.value ? 'primary' : 'default'"
                  :aria-pressed="resourcePeriod === period.value"
                  @click="switchResourcePeriod(period.value)"
                >
                  {{ period.label }}
                </el-button>
              </el-button-group>
            </div>
          </template>

          <el-alert v-if="resourceError" class="ranking-card__alert" type="error" :title="resourceError" :closable="false" show-icon>
            <template #default>
              <el-button data-test="retry-resource-ranking" link type="primary" @click="loadHotResources">重新加载</el-button>
            </template>
          </el-alert>
          <el-table v-else v-loading="resourceLoading" :data="hotResources" empty-text="暂无热门资料">
            <el-table-column label="排名" width="72">
              <template #default="scope"><span class="ranking-position" :class="{ 'ranking-position--top': scope.row.rank <= 3 }">{{ scope.row.rank }}</span></template>
            </el-table-column>
            <el-table-column label="资料标题" min-width="190">
              <template #default="scope"><strong class="ranking-title">{{ scope.row.title }}</strong></template>
            </el-table-column>
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
        <el-card class="ranking-card" shadow="never">
          <template #header>
            <div class="ranking-card__header">
              <div>
                <strong>热门搜索词</strong>
                <small>发现同学们正在关注的学习主题</small>
              </div>
              <el-button-group aria-label="热门搜索词榜单周期">
                <el-button
                  v-for="period in keywordPeriods"
                  :key="period.value"
                  :type="keywordPeriod === period.value ? 'primary' : 'default'"
                  :aria-pressed="keywordPeriod === period.value"
                  @click="switchKeywordPeriod(period.value)"
                >
                  {{ period.label }}
                </el-button>
              </el-button-group>
            </div>
          </template>

          <el-alert v-if="keywordError" class="ranking-card__alert" type="error" :title="keywordError" :closable="false" show-icon>
            <template #default>
              <el-button data-test="retry-keyword-ranking" link type="primary" @click="loadHotKeywords">重新加载</el-button>
            </template>
          </el-alert>
          <el-table v-else v-loading="keywordLoading" :data="hotKeywords" empty-text="暂无热门搜索词">
            <el-table-column label="排名" width="72">
              <template #default="scope"><span class="ranking-position" :class="{ 'ranking-position--top': scope.row.rank <= 3 }">{{ scope.row.rank }}</span></template>
            </el-table-column>
            <el-table-column label="关键词" min-width="140">
              <template #default="scope">
                <RouterLink class="keyword-link" :to="{ path: '/search', query: { keyword: scope.row.keyword } }"># {{ scope.row.keyword }}</RouterLink>
              </template>
            </el-table-column>
            <el-table-column label="搜索次数" width="110">
              <template #default="scope">{{ formatNumber(scope.row.searchCount) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </section>
</template>
