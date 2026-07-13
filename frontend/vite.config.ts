/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // 代理目标只读取本地环境变量，避免把后端地址或敏感配置写死在页面代码中。
  const environment = loadEnv(mode, process.cwd(), '')
  const apiProxyTarget = environment.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080'

  return {
    plugins: [vue()],
    server: {
      proxy: {
        '/api': {
          target: apiProxyTarget,
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: 'happy-dom',
      setupFiles: './tests/setup.ts',
      include: ['src/**/*.test.ts'],
      clearMocks: true,
    },
  }
})
