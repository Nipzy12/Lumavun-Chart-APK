package com.example.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.storage.preferences.PortableChartProfile
import com.example.storage.preferences.TerminalSettings
import com.example.ui.components.premiumPanel
import com.example.update.AndroidReleaseIndex
import com.example.update.AndroidRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Composable
fun AndroidMaintenancePanel(settings: TerminalSettings, onApplyProfile: (PortableChartProfile) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PortableChartProfile?>(null) }
    var update by remember { mutableStateOf<AndroidRelease?>(null) }
    var busy by remember { mutableStateOf(false) }
    val client = remember { OkHttpClient.Builder().callTimeout(12,TimeUnit.SECONDS).followRedirects(false).build() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val data = PortableChartProfile.from(settings).encode()
            scope.launch {
                message = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                            ?: error("Cannot open document")
                        "Profil chart disimpan."
                    }.getOrDefault("Gagal menyimpan profil.")
                }
            }
        }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val chars = CharArray(4097)
                        var total = 0
                        while (total < chars.size) {
                            val n = reader.read(chars,total,chars.size-total)
                            if (n <= 0) break
                            total += n
                        }
                        require(total <= 4096)
                        String(chars,0,total)
                    } ?: error("Cannot open document")
                    PortableChartProfile.decode(raw)
                }.getOrNull()
            }
            pending = parsed
            if (parsed == null) message = "Profil tidak valid."
        }
    }
    Column(Modifier.fillMaxWidth().premiumPanel().padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("LUMAVUN V10 · ANDROID", fontWeight=FontWeight.Bold, color=MaterialTheme.colorScheme.primary)
        Text("Build ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}", style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            busy = true; update = null
            scope.launch {
                try {
                    val raw = withContext(Dispatchers.IO) {
                        val request = Request.Builder().url("https://api.github.com/repos/Nipzy12/Lumavun-Chart-APK/releases?per_page=30")
                            .header("Accept","application/vnd.github+json").header("User-Agent","LUMAVUN-Android").build()
                        client.newCall(request).execute().use { response ->
                            check(response.isSuccessful)
                            response.body?.string() ?: error("Empty release index")
                        }
                    }
                    update = AndroidReleaseIndex.findNewer(raw,BuildConfig.VERSION_CODE)
                    message = if (update == null) "Tidak ada update Android lebih baru." else "Update Android tersedia."
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { message = "Gagal memeriksa update. Periksa koneksi." }
                finally { busy = false }
            }
        }, enabled=!busy) { Text(if (busy) "Memeriksa..." else "Cek update APK") }
        update?.let { r ->
            Button(onClick={ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(r.downloadUrl))) }
                .onFailure { message = "Browser tidak tersedia." } }) { Text("Unduh build ${r.versionCode}") }
        }
        Text("Unduhan resmi dari GitHub. Instalasi tetap perlu persetujuan Android.", style=MaterialTheme.typography.labelSmall)
        HorizontalDivider()
        Text("BACKUP PROFIL CHART", fontWeight=FontWeight.Bold)
        OutlinedButton(onClick={save.launch("lumavun-chart-profile.json")}) { Text("Ekspor profil") }
        OutlinedButton(onClick={load.launch(arrayOf("application/json","text/plain","application/octet-stream"))}) { Text("Impor profil") }
        Text("Simpan di perangkat atau penyedia dokumen/cloud pilihan Anda. API key, token, saldo, posisi, dan bot tidak ikut.", style=MaterialTheme.typography.labelSmall)
        if (message.isNotBlank()) Text(message, style=MaterialTheme.typography.bodySmall)
    }
    pending?.let { p ->
        AlertDialog(onDismissRequest={pending=null},title={Text("Pulihkan profil chart?")},
            text={Text("Tema ${p.theme}, bahasa ${p.language}, timeframe ${p.timeframe.label} dan pilihan indikator akan diganti.")},
            confirmButton={Button(onClick={onApplyProfile(p);pending=null;message="Profil chart dipulihkan."}) { Text("Pulihkan") }},
            dismissButton={TextButton(onClick={pending=null}) { Text("Batal") }})
    }
}
