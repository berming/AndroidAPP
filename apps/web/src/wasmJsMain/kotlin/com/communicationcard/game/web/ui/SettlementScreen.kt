package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.communicationcard.game.web.viewmodel.Screen

@Composable
fun SettlementScreen(
    state: Screen.Settlement,
    onPlayAgain: () -> Unit,
    onBackToLobby: () -> Unit,
) {
    val winnerText = when (state.result.winner) {
        "TEAM_A" -> "A 队胜利！"
        "TEAM_B" -> "B 队胜利！"
        else -> "平局"
    }
    val triggerText = when (state.result.trigger) {
        "TEAM_ALL_FINISHED" -> "全队走完触发"
        "SCORE_REACHED_200" -> "得分达到 200 提前结算"
        else -> state.result.trigger
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0E3812))) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(winnerText, color = Color(0xFFFFC107), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(triggerText, color = Color(0xFFE8F5E9), fontSize = 14.sp)

            Spacer(Modifier.height(32.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    ScoreLine("A 队", state.result.teamAScore, color = Color(0xFFEF5350))
                    Spacer(Modifier.height(12.dp))
                    ScoreLine("B 队", state.result.teamBScore, color = Color(0xFF42A5F5))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "总分: ${state.result.teamAScore + state.result.teamBScore}",
                        color = Color(0xFFE8F5E9),
                        fontSize = 14.sp,
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = onPlayAgain,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFC107),
                    contentColor = Color.Black,
                ),
            ) {
                Text("再来一局", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onBackToLobby,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(52.dp),
            ) {
                Text("返回主菜单", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ScoreLine(label: String, score: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .padding(end = 12.dp)
                .background(color, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Text("$score 分", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}
