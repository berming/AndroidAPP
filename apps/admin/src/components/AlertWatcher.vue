<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { alertsApi, type Alert } from '../api/alerts'
import { ElNotification } from 'element-plus'

/**
 * 全局告警监听器（PR 5a：SSE 实时推送 + 30s 轮询双保险）。
 *
 * - **SSE**（主信道）：服务端 AlertEngine 每次成功插入 alerts 行就推一份 DTO；
 *   admin UI 通过 EventSource 实时接收 → 立刻弹 ElNotification
 * - **30s 轮询**（保底信道）：补 SSE 重连期间漏掉的（或反代 proxy 缓冲过的）告警；
 *   也用来同步 ack 后徽章计数
 * - **dedup**：用 `seen` Set 防止 SSE 与 polling 各推一次同一条告警导致重复弹窗
 */
const emit = defineEmits<{ (e: 'ack-changed'): void }>()
const seen = ref<Set<number>>(new Set())
const firstFetch = ref(true)
let pollTimer: number | undefined
let sse: EventSource | null = null

function showAlertNotification(a: Alert) {
  if (seen.value.has(a.id)) return  // SSE / polling 重复时只弹一次
  seen.value.add(a.id)
  ElNotification({
    title: `[${a.severity}] ${a.rule}`,
    message: a.message,
    type: a.severity === 'ERROR' ? 'error' : a.severity === 'WARN' ? 'warning' : 'info',
    duration: 8000,
    position: 'top-right',
  })
  emit('ack-changed')
}

async function poll() {
  try {
    const list = await alertsApi.listUnacked(100)
    const currentIds = new Set(list.map((a) => a.id))
    if (firstFetch.value) {
      // 首次：仅记录现有 id，不发通知（避免页面刚打开就被旧告警刷屏）
      seen.value = currentIds
      firstFetch.value = false
      return
    }
    // 新增的 id（仍未 ack）—— 走与 SSE 一样的 dedup 路径
    for (const a of list) {
      if (!seen.value.has(a.id)) showAlertNotification(a)
    }
    // ack 后徽章刷新（即便没新告警也要让 layout 更新计数）
    emit('ack-changed')
  } catch (_e) {
    // 401 拦截器接管，忽略
  }
}

function connectSse() {
  // EventSource 走同源；浏览器自动带 cookie（withCredentials=true）。
  // 注意 withCredentials 选项必须 explicit 设；不然 cookie 不会发
  try {
    sse = new EventSource('/admin/api/alerts/stream', { withCredentials: true })
    sse.onmessage = (e) => {
      try {
        const alert = JSON.parse(e.data) as Alert
        showAlertNotification(alert)
      } catch (_err) { /* 心跳或非 JSON，忽略 */ }
    }
    sse.onerror = () => {
      // EventSource 浏览器内置自动重连，不需要在这里 close + 重连
      // （SSE 一般 3s retry；服务端会在 connect 时发 retry: 3000 提示）
    }
  } catch (_e) {
    // 浏览器不支持 EventSource — 走 30s polling 兜底
    sse = null
  }
}

onMounted(() => {
  poll()
  connectSse()
  pollTimer = window.setInterval(poll, 30_000)
})
onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  sse?.close()
  sse = null
})
</script>

<template>
  <!-- 纯逻辑组件，无 UI -->
</template>
