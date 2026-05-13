<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { alertsApi } from '../api/alerts'
import { ElNotification } from 'element-plus'

/**
 * 全局轮询告警的隐形组件。30s 拉一次 unacked 列表；首次拉的时候**不**弹通知
 * （避免页面刚打开就被旧告警刷屏）；后续轮询发现新 id → 右上弹 ElNotification。
 *
 * 服务端 AlertEngine 周期 10s，所以 30s 客户端轮询足够及时；不上 SSE 是 MVP
 * 决策（PR 5 可选升级）。
 */
const emit = defineEmits<{ (e: 'ack-changed'): void }>()
const seen = ref<Set<number>>(new Set())
const firstFetch = ref(true)
let pollTimer: number | undefined

async function poll() {
  try {
    const list = await alertsApi.listUnacked(100)
    const currentIds = new Set(list.map((a) => a.id))
    if (firstFetch.value) {
      // 首次：仅记录现有 id，不发通知
      seen.value = currentIds
      firstFetch.value = false
      return
    }
    // 新增的 id（仍未 ack）
    const fresh = list.filter((a) => !seen.value.has(a.id))
    for (const a of fresh) {
      ElNotification({
        title: `[${a.severity}] ${a.rule}`,
        message: a.message,
        type: a.severity === 'ERROR' ? 'error' : a.severity === 'WARN' ? 'warning' : 'info',
        duration: 8000,
        position: 'top-right',
      })
    }
    seen.value = currentIds
    if (fresh.length > 0) emit('ack-changed')
  } catch (_e) {
    // 401 拦截器接管，忽略
  }
}

onMounted(() => {
  poll()
  pollTimer = window.setInterval(poll, 30_000)
})
onBeforeUnmount(() => { if (pollTimer) window.clearInterval(pollTimer) })
</script>

<template>
  <!-- 纯逻辑组件，无 UI -->
</template>
