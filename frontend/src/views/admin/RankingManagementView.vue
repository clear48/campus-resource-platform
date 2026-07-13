<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { rebuildHotResourceRanking } from '../../api/admin/rankings'
import { getHotResources, getHotSearchKeywords } from '../../api/rankings'
import { session } from '../../state/session'
import type { HotResourceRankingItem, HotSearchKeywordRankingItem, ResourceRankingPeriod, SearchKeywordRankingPeriod } from '../../types/ranking'
import { formatNumber } from '../../utils/format'

const router = useRouter()
const resourcePeriod = ref<ResourceRankingPeriod>('all')
const keywordPeriod = ref<SearchKeywordRankingPeriod>('daily')
const resourceRanking = ref<HotResourceRankingItem[]>([])
const keywordRanking = ref<HotSearchKeywordRankingItem[]>([])
const resourceLoading = ref(false)
const keywordLoading = ref(false)
const rebuilding = ref(false)
const resourceError = ref('')
const keywordError = ref('')
const rebuildError = ref('')

const resourcePeriods: Array<{ value: ResourceRankingPeriod; label: string }> = [
  { value: 'daily', label: '日榜' },
  { value: 'weekly', label: '周榜' },
  { value: 'monthly', label: '月榜' },
  { value: 'all', label: '总榜' },
]
const keywordPeriods: Array<{ value: SearchKeywordRankingPeriod; label: string }> = resourcePeriods.filter((item): item is { value: SearchKeywordRankingPeriod; label: string } => item.value !== 'all')

/** 管理员入口仅做基础提示，统一路由守卫将在 T42 收口。 */
async function ensureAdmin(): Promise<boolean> {
  session.hydrateSession()

  if (!session.isLoggedIn.value) {
    await router.replace({ name: 'login', query: { redirect: '/admin/rankings' } })
    return false
  }

  if (session.currentUser.value?.role !== 2) {
    resourceError.value = '此页面仅供管理员演示使用。'
    return false
  }

  return true
}

async function loadResourceRanking() {
  resourceLoading.value = true
  resourceError.value = ''

  try {
    resourceRanking.value = await getHotResources({ period: resourcePeriod.value, limit: 10 })
  } catch (error) {
    resourceError.value = error instanceof Error ? error.message : '热门资料榜加载失败'
  } finally {
    resourceLoading.value = false
  }
}

async function loadKeywordRanking() {
  keywordLoading.value = true
  keywordError.value = ''

  try {
    keywordRanking.value = await getHotSearchKeywords({ period: keywordPeriod.value, limit: 10 })
  } catch (error) {
    keywordError.value = error instanceof Error ? error.message : '热门搜索词榜加载失败'
  } finally {
    keywordLoading.value = false
  }
}

/** 总榜重建没有进度查询接口，页面只显示确认与请求中状态。 */
async function rebuildAllRanking() {
  if (rebuilding.value) {
    return
  }

  try {
    await ElMessageBox.confirm('将按已审核通过资料重新构建热门资料总榜，确认继续吗？', '确认重建总榜', { type: 'warning' })
  } catch {
    return
  }

  rebuilding.value = true
  rebuildError.value = ''

  try {
    await rebuildHotResourceRanking()
    ElMessage.success('热门资料总榜已重建。')
    // 重建仅影响 all 总榜；主动切换后重新读取，便于演示结果更新。
    resourcePeriod.value = 'all'
    await loadResourceRanking()
  } catch (error) {
    rebuildError.value = error instanceof Error ? error.message : '总榜重建失败'
  } finally {
    rebuilding.value = false
  }
}

onMounted(async () => {
  if (!await ensureAdmin()) {
    return
  }

  void loadResourceRanking()
  void loadKeywordRanking()
})
</script>

<template>
  <section class="ranking-management-view">
    <el-row :gutter="20">
      <el-col :xs="24" :lg="14">
        <el-card shadow="never">
          <template #header>
            <div class="ranking-management-view__header">
              <span>热门资料榜</span>
              <el-space>
                <el-button text :loading="resourceLoading" @click="loadResourceRanking">刷新</el-button>
                <el-button data-test="rebuild-ranking" type="warning" :loading="rebuilding" @click="rebuildAllRanking">重建总榜</el-button>
              </el-space>
            </div>
          </template>
          <el-alert v-if="resourceError" class="ranking-management-view__alert" type="error" :title="resourceError" :closable="false" show-icon />
          <el-alert v-if="rebuildError" class="ranking-management-view__alert" type="error" :title="rebuildError" :closable="false" show-icon />
          <el-radio-group v-model="resourcePeriod" class="ranking-management-view__period" @change="loadResourceRanking">
            <el-radio-button v-for="option in resourcePeriods" :key="option.value" :value="option.value">{{ option.label }}</el-radio-button>
          </el-radio-group>
          <el-table v-loading="resourceLoading" :data="resourceRanking" empty-text="当前周期暂无热门资料">
            <el-table-column prop="rank" label="排名" width="70" />
            <el-table-column prop="title" label="资料标题" min-width="180" />
            <el-table-column prop="courseName" label="课程" min-width="110" />
            <el-table-column label="下载" width="85"><template #default="scope">{{ formatNumber(scope.row.downloadCount) }}</template></el-table-column>
            <el-table-column label="收藏" width="85"><template #default="scope">{{ formatNumber(scope.row.favoriteCount) }}</template></el-table-column>
            <el-table-column label="热度" width="85"><template #default="scope">{{ formatNumber(scope.row.hotScore) }}</template></el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="10" class="ranking-management-view__keyword-column">
        <el-card shadow="never">
          <template #header>
            <div class="ranking-management-view__header"><span>热门搜索词榜</span><el-button text :loading="keywordLoading" @click="loadKeywordRanking">刷新</el-button></div>
          </template>
          <el-alert v-if="keywordError" class="ranking-management-view__alert" type="error" :title="keywordError" :closable="false" show-icon />
          <el-radio-group v-model="keywordPeriod" class="ranking-management-view__period" @change="loadKeywordRanking">
            <el-radio-button v-for="option in keywordPeriods" :key="option.value" :value="option.value">{{ option.label }}</el-radio-button>
          </el-radio-group>
          <el-table v-loading="keywordLoading" :data="keywordRanking" empty-text="当前周期暂无热门搜索词">
            <el-table-column prop="rank" label="排名" width="70" />
            <el-table-column prop="keyword" label="关键词" min-width="150" />
            <el-table-column label="搜索次数" width="110"><template #default="scope">{{ formatNumber(scope.row.searchCount) }}</template></el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </section>
</template>
