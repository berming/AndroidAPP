<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { monitorApi, type SessionInfo } from '../api/monitor'
import dayjs from 'dayjs'

const sessions = ref<SessionInfo[]>([])
const loading = ref(false)
let pollTimer: number | undefined

async function refresh() {
  loading.value = true
  try {
    sessions.value = await monitorApi.sessions()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  refresh()
  pollTimer = window.setInterval(refresh, 5_000)
})
onBeforeUnmount(() => { if (pollTimer) window.clearInterval(pollTimer) })

function fmtDuration(sec: number): string {
  if (sec < 60) return `${sec} s`
  if (sec < 3600) return `${Math.floor(sec / 60)} min`
  return `${(sec / 3600).toFixed(1)} h`
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2 class="page-title">当前会话（{{ sessions.length }}）</h2>
      <el-button @click="refresh" :loading="loading">刷新</el-button>
    </div>
    <el-card>
      <el-table :data="sessions" v-loading="loading && sessions.length === 0" stripe>
        <el-table-column prop="sessionIdMasked" label="Session ID" width="140" />
        <el-table-column prop="playerName" label="玩家姓名" />
        <el-table-column prop="roomId" label="房间">
          <template #default="{ row }">{{ row.roomId ?? '—（未进房间）' }}</template>
        </el-table-column>
        <el-table-column prop="seatIndex" label="座位" width="60">
          <template #default="{ row }">{{ row.seatIndex >= 0 ? row.seatIndex : '—' }}</template>
        </el-table-column>
        <el-table-column label="连接时间" width="180">
          <template #default="{ row }">{{ dayjs(row.connectedAtIso).format('MM-DD HH:mm:ss') }}</template>
        </el-table-column>
        <el-table-column label="时长" width="120">
          <template #default="{ row }">{{ fmtDuration(row.connectedSeconds) }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="当前没有活跃会话" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-title { margin: 0; color: #303133; font-weight: 600; font-size: 18px; }
</style>
