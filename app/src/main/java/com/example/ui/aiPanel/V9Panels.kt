package com.example.ui.aiPanel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.analysis.signal.V10Supervisor
import com.example.analysis.smc.V10StrategyResearch
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
        Text("V10 · INTELLIGENCE DESK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
        val supervision = V10Supervisor.review(evidence)
        HorizontalDivider()
        Text("SUPERVISOR · rule-based specialist reviews", fontWeight = FontWeight.Bold)
        supervision.reviews.forEach { role ->
            Text("${role.role}: ${role.verdict}", color = if (role.verdict == V10Supervisor.Verdict.BLOCK) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(role.detail, style = MaterialTheme.typography.labelSmall)
        }
        Text(if (supervision.blocksSimulationEntry) "Supervisor veto: automatic simulation entry blocked." else "No supervisor veto. Existing safety gates still apply.", style = MaterialTheme.typography.labelSmall)
        Text("Diagnostic alignment only. Not a win probability or independent LLM calls.", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun V9ReplayPanel(symbol: String, timeframe: Timeframe, snapshot: MarketTechnicalSnapshot?, costBps: Double) {
    val history = snapshot?.replayCandles.orEmpty()
    var report by remember(symbol, timeframe, costBps) { mutableStateOf<SmcHistoricalReplay.Report?>(null) }
    var attempted by remember(symbol, timeframe, costBps) { mutableStateOf(false) }
    var research by remember(symbol, timeframe, costBps) { mutableStateOf<V10StrategyResearch.Result?>(null) }
    var researchAttempted by remember(symbol, timeframe, costBps) { mutableStateOf(false) }
    var working by remember(symbol, timeframe, costBps) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().premiumPanel().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SMC HISTORICAL REPLAY", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text("${history.size} loaded closed candles · cost per side ${String.format(Locale.US,"%.2f",costBps)} bps", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            working = true
            scope.launch {
                try { report = withContext(Dispatchers.Default) { SmcHistoricalReplay.run(history,timeframe,costBps) }; attempted = true }
                finally { working = false }
            }
        }, enabled = !working && history.size >= 60 && timeframe.higherTimeframe() != null) { Text("Run historical replay") }
        if (timeframe.higherTimeframe() == null || history.size < 60) Text("Requires 60+ closed candles and a supported higher timeframe.")
        if (attempted && report == null) Text("Replay unavailable: check candle ordering, data quality and costs.")
        report?.let { r ->
            fun fmt(v: Double) = String.format(Locale.US,"%.2f",v)
            Text("${r.resolved} closed · ${r.wins} TP · ${r.losses} SL · ${r.open} open · ${r.pending} pending")
            Text("Win rate: " + (r.winRate?.let { fmt(it) + "%" } ?: "N/A"))
            Text("Gross ${fmt(r.grossR)}R · Net ${fmt(r.netR)}R · Drawdown ${fmt(r.maxDrawdownR)}R")
            Text("Sample: ${r.processed} candles · end ${java.util.Date(r.lastCloseTime)}", style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider()
        Text("STRATEGY LAB · chronological holdout", fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = {
            working = true
            scope.launch {
                try { research = withContext(Dispatchers.Default) { V10StrategyResearch.evaluate(history,timeframe,costBps) }; researchAttempted = true }
                finally { working = false }
            }
        }, enabled = !working && history.size >= 200 && timeframe.higherTimeframe() != null) { Text(if (working) "Research running..." else "Compare 6 presets") }
        if (history.size < 200) Text("Requires at least 200 closed candles.")
        if (researchAttempted && research == null) Text("Insufficient qualifying training trades or invalid history. No preset selected.")
        research?.let { r ->
            fun fmt(v: Double) = String.format(Locale.US,"%.2f",v)
            Text("Training choice: ${r.selected.quality} · target ${r.selected.targetRr}R")
            Text("Train: ${r.training.resolved} closed · ${fmt(r.training.netR)}R")
            Text("Unseen holdout: ${r.holdout.resolved} closed · ${fmt(r.holdout.netR)}R · DD ${fmt(r.holdout.maxDrawdownR)}R")
            if (r.holdout.resolved < 30) Text("Small holdout sample; do not infer strategy reliability.", color = MaterialTheme.colorScheme.error)
        }
        Text("Fixed-rule research on loaded candles, 70/30 split. No automatic strategy deployment. Theoretical fills and costs; no live orders.", style = MaterialTheme.typography.labelSmall)
    }
}
