<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, computed } from 'vue'
import { monitorApi, type Overview, type TrendPoint } from '../api/monitor'
import dayjs from 'dayjs'
import VChart from 'vue-echarts'
import { use as echartsUse } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, GridComponent, LegendComponent, DataZoomComponent,
} from 'echarts/components'

echartsUse([
  CanvasRenderer, LineChart, BarChart,
  TitleComponent, TooltipComponent, GridComponent, LegendComponent, DataZoomComponent,
])

const overview = ref<Overview | null>(null)
const trend = ref<TrendPoint[]>([])
const loading = ref(false)
let pollTimer: number | undefined

async function refresh() {
  loading.value = true
  try {
    const [ov, tr] = await Promise.all([monitorApi.overview(), monitorApi.trend(7)])
    overview.value = ov
    trend.value = tr
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  refresh()
  pollTimer = window.setInterval(refresh, 10_000)
})
onBeforeUnmount(() => { if (pollTimer) window.clearInterval(pollTimer) })

const trendOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['总局数', '全人玩家局', '平均时长 (分钟)'], top: 0 },
  grid: { left: '40', right: '40', top: 36, bottom: 24 },
  xAxis: {
    type: 'category',
    data: trend.value.map((p) => p.date.slice(5)),  // MM-DD
  },
  yAxis: [
    { type: 'value', name: '局数', minInterval: 1 },
    { type: 'value', name: '分钟', position: 'right' },
  ],
  series: [
    {
      name: '总局数', type: 'bar', yAxisIndex: 0,
      data: trend.value.map((p) => p.gameCount),
      itemStyle: { color: '#409EFF' },
    },
    {
      name: '全人玩家局', type: 'bar', yAxisIndex: 0,
      data: trend.value.map((p) => p.humanGameCount),
      itemStyle: { color: '#67C23A' },
    },
    {
      name: '平均时长 (分钟)', type: 'line', yAxisIndex: 1, smooth: true,
      data: trend.value.map((p) => Math.round(p.avgDurationSeconds / 60 * 10) / 10),
      itemStyle: { color: '#E6A23C' },
    },
  ],
}))

const uptimeHuman = computed(() => {
  if (!overview.value) return '—'
  const sec = overview.value.uptimeSeconds
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  const m = Math.floor((sec % 3600) / 60)
  return d > 0 ? `${d}d ${h}h ${m}m` : `${h}h ${m}m`
})

const heapPct = computed(() => {
  if (!overview.value || overview.value.jvmHeapMaxMb === 0) return 0
  return Math.round((overview.value.jvmHeapUsedMb / overview.value.jvmHeapMaxMb) * 100)
})
const heapStatus = computed(() => {
  if (heapPct.value >= 85) return 'exception'
  if (heapPct.value >= 70) return 'warning'
  return 'success'
})
</script>

<template>
  <div class="dashboard">
    <h2 class="page-title">数据总览</h2>
    <el-row :gutter="16" v-loading="loading && !overview">
      <el-col :span="6">
        <el-card>
          <el-statistic title="在线房间" :value="overview?.totalRooms ?? 0" />
          <div class="stat-sub">
            等待 {{ overview?.roomsByStatus?.WAITING ?? 0 }} ·
            进行 {{ overview?.roomsByStatus?.IN_GAME ?? 0 }} ·
            结束 {{ overview?.roomsByStatus?.FINISHED ?? 0 }}
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card>
          <el-statistic title="在线玩家" :value="overview?.totalPlayers ?? 0" />
          <div class="stat-sub">
            真人 {{ overview?.humanPlayersConnected ?? 0 }} ·
            掉线 {{ overview?.humanPlayersDisconnected ?? 0 }} ·
            AI {{ overview?.aiPlayers ?? 0 }}（托管 {{ overview?.aiSubstituted ?? 0 }}）
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card>
          <el-statistic title="WS 会话" :value="overview?.totalSessions ?? 0" />
          <div class="stat-sub">当前活跃 WebSocket</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card>
          <el-statistic title="今日 / 累计游戏" :value="overview?.todayGameCount ?? 0" />
          <div class="stat-sub">累计 {{ overview?.totalGameCount ?? 0 }} 局</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px;">
      <el-col :span="12">
        <el-card>
          <template #header>服务运行</template>
          <div class="info-row"><span>启动时间</span><span>{{ overview ? dayjs(overview.serverStartedAt).format('YYYY-MM-DD HH:mm:ss') : '—' }}</span></div>
          <div class="info-row"><span>运行时长</span><span>{{ uptimeHuman }}</span></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>JVM Heap</template>
          <div class="info-row"><span>已用 / 上限</span><span>{{ overview?.jvmHeapUsedMb ?? 0 }} MB / {{ overview?.jvmHeapMaxMb ?? 0 }} MB</span></div>
          <el-progress :percentage="heapPct" :status="heapStatus" />
        </el-card>
      </el-col>
    </el-row>

    <el-row style="margin-top: 16px;">
      <el-col :span="24">
        <el-card>
          <template #header>过去 7 天游戏趋势</template>
          <v-chart class="trend-chart" :option="trendOption" autoresize />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.dashboard { max-width: 1400px; }
.page-title { margin: 0 0 16px; color: #303133; font-weight: 600; font-size: 18px; }
.stat-sub { margin-top: 4px; color: #909399; font-size: 12px; }
.info-row { display: flex; justify-content: space-between; padding: 6px 0; color: #606266; }
.trend-chart { width: 100%; height: 320px; }
</style>
