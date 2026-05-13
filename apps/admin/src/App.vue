<script setup lang="ts">
// /me 已在 main.ts 的 bootstrap() 里 await 拉取过，router 才注册 + mount。
// 此组件不再做 onMounted fetchMe（之前的实现有 race：beforeEach 在 fetchMe
// 之前跑，valid cookie 也被当未登录踢回 /login，pr-reviewer P1）。
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
