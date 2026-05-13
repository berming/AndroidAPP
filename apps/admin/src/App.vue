<script setup lang="ts">
import { onMounted } from 'vue'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()

// 启动时尝试拉一次 /me：cookie 已有 → 直接进站；没 cookie → 401 触发拦截器
// 跳 login。这样浏览器 F5 后无需重新登录（cookie 仍有效时）
onMounted(async () => {
  await auth.fetchMe()
})
</script>

<template>
  <router-view />
</template>

<style>
html, body, #app {
  height: 100%;
  margin: 0;
  padding: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', Helvetica, Arial, sans-serif;
}
</style>
