<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { monitorApi, type PlayerSummary } from '../api/monitor'

const router = useRouter()
const players = ref<PlayerSummary[]>([])
const loading = ref(false)
let pollTimer: number | undefined

async function refresh() {
  loading.value = true
  try {
    players.value = await monitorApi.players()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  refresh()
  pollTimer = window.setInterval(refresh, 5_000)
})
onBeforeUnmount(() => { if (pollTimer) window.clearInterval(pollTimer) })
</script>

<template>
  <div>
    <div class="page-head">
      <h2 class="page-title">玩家列表（{{ players.length }}）</h2>
      <el-button @click="refresh" :loading="loading">刷新</el-button>
    </div>
    <el-card>
      <el-table :data="players" v-loading="loading && players.length === 0" stripe>
        <el-table-column prop="name" label="姓名" />
        <el-table-column prop="playerIdMasked" label="玩家 ID" width="140" />
        <el-table-column label="房间" width="180">
          <template #default="{ row }">
            <el-button link @click="router.push(`/rooms/${encodeURIComponent(row.roomId)}`)">
              {{ row.roomCode }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="seatIndex" label="座位" width="60" />
        <el-table-column prop="team" label="队伍" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.isAI" type="info">AI</el-tag>
            <el-tag v-else-if="row.isAISubstitute" type="warning">托管</el-tag>
            <el-tag v-else-if="row.isConnected" type="success">真人</el-tag>
            <el-tag v-else type="danger">掉线</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="handSize" label="手牌数" width="80" />
        <el-table-column prop="collectedScore" label="已收分" width="80" />
        <template #empty>
          <el-empty description="当前没有玩家" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-title { margin: 0; color: #303133; font-weight: 600; font-size: 18px; }
</style>
