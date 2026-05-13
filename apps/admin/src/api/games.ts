import { http } from './http'

export interface GameSummary {
  id: number
  roomCode: string
  startedAt: string
  endedAt: string
  durationMs: number
  playerCount: number
  humanCount: number
  aiCount: number
  winnerTeam: string
  trigger: string
  teamAScore: number
  teamBScore: number
}

export interface GamePlayer {
  seatIndex: number
  playerIdMasked: string
  name: string
  team: string
  isAI: boolean
  wasSubstituted: boolean
  finished: boolean
  finishOrder: number
  collectedScore: number
  finalHandSize: number
}

export interface GameDetail {
  summary: GameSummary
  players: GamePlayer[]
}

export const gamesApi = {
  async list(params?: { from?: number; to?: number; limit?: number }): Promise<GameSummary[]> {
    return (await http.get<GameSummary[]>('/admin/api/games', { params })).data
  },
  async detail(id: number): Promise<GameDetail> {
    return (await http.get<GameDetail>(`/admin/api/games/${id}`)).data
  },
}
