<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { monitorApi, type RoomSummary } from '../api/monitor'

const router = useRouter()
const rooms = ref<RoomSummary[]>([])
const loading = ref(false)
let pollTimer: number | undefined

async function refresh() {
  loading.value = true
  try {
    rooms.value = await monitorApi.rooms()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  refresh()
  pollTimer = window.setInterval(refresh, 5_000)
})
onBeforeUnmount(() => { if (pollTimer) window.clearInterval(pollTimer) })

function statusTagType(s: string) {
  switch (s) {
    case 'WAITING': return ''
    case 'STARTING': return 'warning'
    case 'IN_GAME': return 'success'
    case 'FINISHED': return 'info'
    default: return ''
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2 class="page-title">房间监控</h2>
      <el-button @click="refresh" :loading="loading">刷新</el-button>
    </div>
    <el-card>
      <el-table :data="rooms" v-loading="loading && rooms.length === 0" stripe>
        <el-table-column prop="roomCode" label="房号" width="100" />
        <el-table-column prop="roomName" label="房间名" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="人数" width="120">
          <template #default="{ row }">
            {{ row.playerCount }} / {{ row.maxPlayers }}
            <span style="color:#909399;font-size:12px;">（人 {{ row.humanCount }} · AI {{ row.aiCount }}）</span>
          </template>
        </el-table-column>
        <el-table-column prop="phase" label="阶段" width="100" />
        <el-table-column label="比分" width="120">
          <template #default="{ row }">
            <span style="color:#67c23a;">A {{ row.teamAScore }}</span> ·
            <span style="color:#f56c6c;">B {{ row.teamBScore }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="serverAiDelayMs" label="AI 速度" width="100">
          <template #default="{ row }">{{ row.serverAiDelayMs }} ms</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button size="small" link @click="router.push(`/rooms/${encodeURIComponent(row.roomId)}`)">
              详情
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="当前没有房间" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-title { margin: 0; color: #303133; font-weight: 600; font-size: 18px; }
</style>
