package com.example.update

import org.json.JSONArray

data class AndroidRelease(val versionCode: Int) {
    val downloadUrl: String get() = "https://github.com/Nipzy12/Lumavun-Chart-APK/releases/download/android-v$versionCode/LUMAVUN-Android.apk"
}

object AndroidReleaseIndex {
    fun findNewer(raw: String, installed: Int): AndroidRelease? {
        val list = JSONArray(raw)
        val pattern = Regex("android-v([0-9]{1,9})")
        var newest = installed
        for (i in 0 until list.length()) {
            val r = list.optJSONObject(i) ?: continue
            if (r.optBoolean("draft", true) || r.optBoolean("prerelease", true)) continue
            val version = pattern.matchEntire(r.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (version <= newest || version < 100000) continue
            val expected = AndroidRelease(version).downloadUrl
            val assets = r.optJSONArray("assets") ?: continue
            if ((0 until assets.length()).any { j ->
                    val a = assets.optJSONObject(j)
                    a != null && a.optString("name") == "LUMAVUN-Android.apk" &&
                        a.optString("state") == "uploaded" && a.optLong("size") > 0 &&
                        a.optString("browser_download_url") == expected
                }) newest = version
        }
        return newest.takeIf { it > installed }?.let(::AndroidRelease)
    }
}
