import { ElMessage } from 'element-plus'
import { createRouter, createWebHistory, type RouterHistory, type RouteRecordRaw } from 'vue-router'
import DefaultLayout from '../layouts/DefaultLayout.vue'
import AdminLayout from '../layouts/AdminLayout.vue'
import HomeView from '../views/HomeView.vue'
import LoginView from '../views/LoginView.vue'
import NotFoundView from '../views/NotFoundView.vue'
import ProfileView from '../views/user/ProfileView.vue'
import MyUploadsView from '../views/user/MyUploadsView.vue'
import MyFavoritesView from '../views/user/MyFavoritesView.vue'
import MyDownloadsView from '../views/user/MyDownloadsView.vue'
import RegisterView from '../views/RegisterView.vue'
import ResourceDetailView from '../views/ResourceDetailView.vue'
import SearchView from '../views/SearchView.vue'
import UploadView from '../views/UploadView.vue'
import { session } from '../state/session'

// 首版保持固定路由，后续只在对应任务中逐页扩展，避免提前生成所有页面。
export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: DefaultLayout,
    children: [
      {
        path: '',
        name: 'home',
        component: HomeView,
      },
      {
        path: 'me/profile',
        name: 'profile',
        component: ProfileView,
        meta: { requiresAuth: true },
      },
      {
        path: 'me/uploads',
        name: 'my-uploads',
        component: MyUploadsView,
        meta: { requiresAuth: true },
      },
      {
        path: 'me/favorites',
        name: 'my-favorites',
        component: MyFavoritesView,
        meta: { requiresAuth: true },
      },
      {
        path: 'me/downloads',
        name: 'my-downloads',
        component: MyDownloadsView,
        meta: { requiresAuth: true },
      },
      {
        path: 'search',
        name: 'search',
        component: SearchView,
      },
      {
        path: 'resources/:resourceId',
        name: 'resource-detail',
        component: ResourceDetailView,
      },
      {
        path: 'upload',
        name: 'upload',
        component: UploadView,
        meta: { requiresAuth: true },
      },
    ],
  },
  {
    path: '/admin',
    component: AdminLayout,
    meta: { requiresAuth: true, requiresAdmin: true },
    children: [
      {
        path: 'reviews',
        name: 'admin-reviews',
        component: () => import('../views/admin/ReviewManagementView.vue'),
      },
      {
        path: 'resources',
        name: 'admin-resources',
        component: () => import('../views/admin/PublishedResourcesView.vue'),
      },
      {
        path: 'rankings',
        name: 'admin-rankings',
        component: () => import('../views/admin/RankingManagementView.vue'),
      },
    ],
  },
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { guestOnly: true },
  },
  {
    path: '/register',
    name: 'register',
    component: RegisterView,
    meta: { guestOnly: true },
  },
  // 兜底路由避免未知地址渲染空白页面。
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: NotFoundView,
  },
]

/** 创建路由并集中处理登录/管理员入口；后端始终承担最终鉴权责任。 */
export function createAppRouter(history: RouterHistory = createWebHistory()) {
  const router = createRouter({ history, routes })

  router.beforeEach((to) => {
    session.hydrateSession()
    const requiresAuth = to.matched.some((record) => record.meta.requiresAuth)
    const requiresAdmin = to.matched.some((record) => record.meta.requiresAdmin)
    const guestOnly = to.matched.some((record) => record.meta.guestOnly)

    if (guestOnly && session.isLoggedIn.value) {
      return { name: 'home' }
    }

    if (requiresAuth && !session.isLoggedIn.value) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }

    if (requiresAdmin && session.currentUser.value?.role !== 2) {
      ElMessage.error('无管理员权限，已返回首页。')
      return { name: 'home' }
    }

    return true
  })

  return router
}

export default createAppRouter()
