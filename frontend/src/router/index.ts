import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import DefaultLayout from '../layouts/DefaultLayout.vue'
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

// 首版保持固定路由，后续只在对应任务中逐页扩展，避免提前生成所有页面。
const routes: RouteRecordRaw[] = [
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
      },
      {
        path: 'me/uploads',
        name: 'my-uploads',
        component: MyUploadsView,
      },
      {
        path: 'me/favorites',
        name: 'my-favorites',
        component: MyFavoritesView,
      },
      {
        path: 'me/downloads',
        name: 'my-downloads',
        component: MyDownloadsView,
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
      },
    ],
  },
  {
    path: '/login',
    name: 'login',
    component: LoginView,
  },
  {
    path: '/register',
    name: 'register',
    component: RegisterView,
  },
  // 兜底路由避免未知地址渲染空白页面。
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: NotFoundView,
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
