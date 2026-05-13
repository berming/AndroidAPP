<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { gamesApi, type GameSummary, type GameDetail, type GameEvent } from '../api/games'
import dayjs from 'dayjs'

const games = ref<GameSummary[]>([])
const loading = ref(false)
const detailOpen = ref(false)
const detail = ref<GameDetail | null>(null)
const events = ref<GameEvent[]>([])
const eventsLoading = ref(false)
const activeTab = ref<'players' | 'events'>('players')

async function refresh() {
  loading.value = true
  try {
    games.value = await gamesApi.list({ limit: 100 })
  } finally {
    loading.value = false
  }
}

async function openDetail(g: GameSummary) {
  activeTab.value = 'players'
  detail.value = await gamesApi.detail(g.id)
  detailOpen.value = true
  // 异步拉事件（详情先弹）
  eventsLoading.value = true
  events.value = []
  try {
    events.value = await gamesApi.events(g.id)
  } finally {
    eventsLoading.value = false
  }
}

onMounted(() => refresh())

function formatPayload(p: unknown): string {
  try {
    return JSON.stringify(p)
  } catch (_e) {
    return String(p)
  }
}

function fmtDuration(ms: number): string {
  const sec = Math.floor(ms / 1000)
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m}m ${s.toString().padStart(2, '0')}s`
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2 class="page-title">历史游戏（{{ games.length }}）</h2>
      <el-button @click="refresh" :loading="loading">刷新</el-button>
    </div>
    <el-card>
      <el-table :data="games" v-loading="loading && games.length === 0" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="roomCode" label="房号" width="100" />
        <el-table-column label="开始" width="160">
          <template #default="{ row }">{{ dayjs(row.startedAt).format('MM-DD HH:mm:ss') }}</template>
        </el-table-column>
        <el-table-column label="时长" width="100">
          <template #default="{ row }">{{ fmtDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="人数" width="120">
          <template #default="{ row }">
            {{ row.playerCount }} <span style="color:#909399;">（人 {{ row.humanCount }} · AI {{ row.aiCount }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="胜者" width="100">
          <template #default="{ row }">
            <el-tag :type="row.winnerTeam === 'TEAM_A' ? 'success' : 'danger'">{{ row.winnerTeam }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="比分" width="120">
          <template #default="{ row }">
            <span style="color:#67c23a;">A {{ row.teamAScore }}</span> ·
            <span style="color:#f56c6c;">B {{ row.teamBScore }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="trigger" label="触发" width="180" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button size="small" link @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有历史游戏（游戏结束后自动入库）" />
        </template>
      </el-table>
    </el-card>

    <el-drawer v-model="detailOpen" title="游戏详情" size="600px">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="ID">{{ detail.summary.id }}</el-descriptions-item>
          <el-descriptions-item label="房号">{{ detail.summary.roomCode }}</el-descriptions-item>
          <el-descriptions-item label="开始">{{ dayjs(detail.summary.startedAt).format('YYYY-MM-DD HH:mm:ss') }}</el-descriptions-item>
          <el-descriptions-item label="结束">{{ dayjs(detail.summary.endedAt).format('YYYY-MM-DD HH:mm:ss') }}</el-descriptions-item>
          <el-descriptions-item label="时长">{{ fmtDuration(detail.summary.durationMs) }}</el-descriptions-item>
          <el-descriptions-item label="触发">{{ detail.summary.trigger }}</el-descriptions-item>
        </el-descriptions>

        <el-tabs v-model="activeTab" style="margin-top: 16px;">
          <el-tab-pane label="玩家" name="players">
            <el-table :data="detail.players" stripe>
              <el-table-column prop="seatIndex" label="座位" width="60" />
              <el-table-column prop="name" label="姓名" />
              <el-table-column prop="playerIdMasked" label="ID" width="140" />
              <el-table-column prop="team" label="队伍" width="80" />
              <el-table-column label="角色" width="80">
                <template #default="{ row }">
                  <el-tag v-if="row.isAI" type="info">AI</el-tag>
                  <el-tag v-else-if="row.wasSubstituted" type="warning">托管</el-tag>
                  <el-tag v-else type="success">真人</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="名次" width="80">
                <template #default="{ row }">{{ row.finished ? `第 ${row.finishOrder} 名` : '未走完' }}</template>
              </el-table-column>
              <el-table-column prop="collectedScore" label="收分" width="80" />
              <el-table-column prop="finalHandSize" label="剩牌" width="80" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`事件 (${events.length})`" name="events">
            <el-empty v-if="!eventsLoading && events.length === 0" description="本局无事件记录" />
            <el-table v-else :data="events" stripe v-loading="eventsLoading" max-height="500">
              <el-table-column prop="seq" label="#" width="60" />
              <el-table-column prop="eventType" label="类型" width="140">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.eventType === 'round_won' ? 'success' : row.eventType === 'player_passed' ? 'info' : ''">
                    {{ row.eventType }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="payload">
                <template #default="{ row }">
                  <code style="font-size: 12px;">{{ formatPayload(row.payload) }}</code>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="120">
                <template #default="{ row }">{{ dayjs(row.createdAt).format('HH:mm:ss') }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-title { margin: 0; color: #303133; font-weight: 600; font-size: 18px; }
</style>
