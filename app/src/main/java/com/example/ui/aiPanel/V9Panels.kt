package com.example.ui.aiPanel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.analysis.signal.TechnicalEvidenceResult
import com.example.analysis.signal.SnapshotEvidenceAdapter
import com.example.analysis.technical.MarketTechnicalSnapshot
import com.example.analysis.smc.SmcHistoricalReplay
import com.example.market.candle.Timeframe
import com.example.ui.components.premiumPanel
import java.util.Locale

@Composable
fun V9EvidencePanel(evidence: TechnicalEvidenceResult?, snapshot: MarketTechnicalSnapshot?) {
    Column(Modifier.fillMaxWidth().premiumPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("V9 · TECHNICAL EVIDENCE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        snapshot?.let { Text("Strategy focus: " + SnapshotEvidenceAdapter.strategyFocus(it.marketRegime), style = MaterialTheme.typography.bodySmall) }
        if (evidence == null) {
            Text("No validated directional evidence score available.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("${evidence.score}/100  ·  Coverage ${evidence.coverage}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            evidence.components.forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.category.name.replace('_',' '), style = MaterialTheme.typography.labelSmall)
                    Text("${item.points}/${item.category.weight} · ${item.state.name}", style = MaterialTheme.typography.labelSmall)
                }
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(item.points.toFloat()/item.category.weight).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Text("Diagnostic alignment only. Not a win probability or a bot entry permission.", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun V9ReplayPanel(symbol: String, timeframe: Timeframe, snapshot: MarketTechnicalSnapshot?, costBps: Double) {
    val history = snapshot?.replayCandles.orEmpty()
    var report by remember(symbol, timeframe, costBps) { mutableStateOf<SmcHistoricalReplay.Report?>(null) }
    var attempted by remember(symbol, timeframe, costBps) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().premiumPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SMC HISTORICAL REPLAY", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text("${history.size} loaded closed candles · cost per side ${String.format(Locale.US,"%.2f",costBps)} bps", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { report = SmcHistoricalReplay.run(history,timeframe,costBps); attempted = true },
            enabled = history.size >= 60 && timeframe.higherTimeframe() != null) { Text("Run historical replay") }
        if (timeframe.higherTimeframe() == null || history.size < 60) Text("Requires 60+ closed candles and a supported higher timeframe.")
        if (attempted && report == null) Text("Replay unavailable: check candle ordering, data quality and costs.")
        report?.let { r ->
            fun fmt(v: Double) = String.format(Locale.US,"%.2f",v)
            Text("${r.resolved} closed · ${r.wins} TP · ${r.losses} SL · ${r.open} open · ${r.pending} pending")
            Text("Win rate: " + (r.winRate?.let { fmt(it) + "%" } ?: "N/A"))
            Text("Gross ${fmt(r.grossR)}R · Net ${fmt(r.netR)}R · Drawdown ${fmt(r.maxDrawdownR)}R")
            Text("Sample: ${r.processed} candles · end ${java.util.Date(r.lastCloseTime)}", style = MaterialTheme.typography.labelSmall)
        }
        Text("SMC fixed-rule replay, not AI prediction. Loaded history only; theoretical fills, approximate tick and costs, SL-first on ambiguous bars. No live orders.", style = MaterialTheme.typography.labelSmall)
    }
}
