package com.example.storage.preferences

import com.example.market.candle.Timeframe
import org.json.JSONObject

/** Explicit whitelist: never exports broker secrets, API keys, positions or auto-trading settings. */
data class PortableChartProfile(val theme: String, val language: String, val timeframe: Timeframe,
    val ema: Boolean, val rsi: Boolean, val macd: Boolean, val volume: Boolean, val sr: Boolean) {
    fun encode(): String = JSONObject()
        .put("schema",1).put("theme",theme).put("language",language).put("timeframe",timeframe.name)
        .put("ema",ema).put("rsi",rsi).put("macd",macd).put("volume",volume).put("sr",sr).toString(2)
    companion object {
        fun from(s: TerminalSettings) = PortableChartProfile(s.themeMode,s.language,s.defaultTimeframe,
            s.showEma,s.showRsi,s.showMacd,s.showVolume,s.showSupportResistance)
        fun decode(raw: String): PortableChartProfile {
            require(raw.length <= 4096) { "Profile too large" }
            val j = JSONObject(raw)
            require(j.getInt("schema") == 1)
            val theme = j.getString("theme")
            val language = j.getString("language")
            require(theme in setOf("DARK","LIGHT","AUTO") && language in setOf("id","en"))
            return PortableChartProfile(theme,language,Timeframe.valueOf(j.getString("timeframe")),
                j.getBoolean("ema"),j.getBoolean("rsi"),j.getBoolean("macd"),j.getBoolean("volume"),j.getBoolean("sr"))
        }
    }
}
