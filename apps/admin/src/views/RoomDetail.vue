<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { monitorApi, type RoomDetail } from '../api/monitor'

const route = useRoute()
const router = useRouter()
const detail = ref<RoomDetail | null>(null)
const loading = ref(false)
let pollTimer: number | undefined

async function refresh() {
  loading.value = true
  try {
    detail.value = await monitorApi.room(route.params.id as string)
  } catch (_e) {
    detail.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  refresh()
  pollTimer = window.setInterval(refresh, 3_000)
})
onBeforeUnmount(() => { if (pollTimer) window.clearInterval(pollTimer) })
</script>

<template>
  <div v-loading="loading && !detail">
    <div class="page-head">
      <el-button link @click="router.push('/rooms')">← 返回房间列表</el-button>
      <el-button @click="refresh" :loading="loading">刷新</el-button>
    </div>

    <div v-if="!detail && !loading">
      <el-empty description="房间不存在" />
    </div>

    <template v-else-if="detail">
      <el-card class="section">
        <template #header>
          <strong>{{ detail.summary.roomCode }} · {{ detail.summary.roomName }}</strong>
          <el-tag style="margin-left: 12px;">{{ detail.summary.status }}</el-tag>
        </template>
        <el-descriptions :column="4" border>
          <el-descriptions-item label="阶段">{{ detail.summary.phase ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="当前出牌座位">{{ detail.summary.currentPlayerIndex ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="本回合分">{{ detail.currentRoundScore }}</el-descriptions-item>
          <el-descriptions-item label="连续 Pass">{{ detail.consecutivePasses }}</el-descriptions-item>
          <el-descriptions-item label="队 A 总分">{{ detail.summary.teamAScore }}</el-descriptions-item>
          <el-descriptions-item label="队 B 总分">{{ detail.summary.teamBScore }}</el-descriptions-item>
          <el-descriptions-item label="协议版本">{{ detail.summary.version }}</el-descriptions-item>
          <el-descriptions-item label="AI 延迟">{{ detail.summary.serverAiDelayMs }} ms</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card class="section">
        <template #header>玩家（{{ detail.players.length }}）</template>
        <el-table :data="detail.players" stripe>
          <el-table-column prop="seatIndex" label="座位" width="60" />
          <el-table-column prop="name" label="姓名" />
          <el-table-column prop="playerIdMasked" label="ID" width="140" />
          <el-table-column prop="team" label="队伍" width="80" />
          <el-table-column label="角色" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.isAI" type="info">AI</el-tag>
              <el-tag v-else-if="row.isAISubstitute" type="warning">托管</el-tag>
              <el-tag v-else-if="row.isConnected" type="success">真人</el-tag>
              <el-tag v-else type="danger">掉线</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="isReady" label="准备" width="60">
            <template #default="{ row }">
              <el-tag v-if="row.isReady" type="success" size="small">✓</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="handSize" label="手牌数" width="80" />
          <el-table-column prop="collectedScore" label="已收分" width="80" />
          <el-table-column prop="takeoverAiDelayMs" label="托管延迟" width="100">
            <template #default="{ row }">{{ row.takeoverAiDelayMs }} ms</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card class="section" v-if="detail.lastPlayedGroup">
        <template #header>最近一手</template>
        <div>
          <el-tag>{{ detail.lastPlayedGroup.type }} · {{ detail.lastPlayedGroup.primaryRank }}</el-tag>
          （由座位 {{ detail.lastPlayerId }} 出）
        </div>
        <div class="cards">
          <el-tag
            v-for="(c, i) in detail.lastPlayedGroup.cards"
            :key="i"
            type="warning"
            style="margin-right: 6px; margin-top: 6px;"
          >
            {{ c.rank }} {{ c.suit }}
          </el-tag>
        </div>
      </el-card>

      <el-card class="section" v-if="detail.finishOrder.length > 0">
        <template #header>已走完次序</template>
        <el-tag v-for="(seat, i) in detail.finishOrder" :key="seat" style="margin-right: 6px;">
          {{ i + 1 }}. 座位 {{ seat }}
        </el-tag>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.section { margin-bottom: 16px; }
.cards { margin-top: 8px; }
</style>
