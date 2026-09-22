package dev.wiles.myagent.client.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.wiles.myagent.client.net.AgentClient
import dev.wiles.myagent.client.net.Pairing
import dev.wiles.myagent.client.store.Prefs
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 连接页：扫电脑终端里 `/qr` 出的码，或手填 ws URL。
 *
 * 扫码和手填走**同一条代码路径**——二维码的内容就是 URL 本身（协议 1.1），
 * 这正是当初不发明新 URI scheme 的理由。
 */
@Composable
fun ConnectScreen(client: AgentClient, prefs: Prefs, connecting: Boolean, closedReason: String?) {
    val lastUrl by prefs.lastUrl.collectAsStateWithLifecycle(initialValue = null)
    var manual by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun connect(raw: String) {
        // 两种配对都是「一个 URL 本身」，判别归 Pairing.parse（协议 1.1 / 1.3）
        val pairing = Pairing.parse(raw)
        if (pairing == null) {
            problem = "这不是 myagent 的配对地址。\n" +
                "中继：wss://<域名>/ws?room=…#<密钥>\n" +
                "局域网：ws://<IP>:<端口>/?token=<token>"
            return
        }
        problem = null
        scope.launch { prefs.saveUrl(raw.trim()) }
        client.connect(pairing)
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { connect(it) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("myagent", style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            Text(
                "在电脑上跑 ./myagent-tui --relay …（或 --lan），界面里敲 /qr，扫那个码",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))

            Button(onClick = {
                scanner.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("对准终端里的二维码")
                    setBeepEnabled(false)
                    // 库自带的扫码页默认锁横屏——换成我们的竖屏子类。
                    // 人举着手机扫桌上的终端，姿势就是竖的
                    setCaptureActivity(PortraitCaptureActivity::class.java)
                    setOrientationLocked(true)
                })
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  扫码配对")
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("或手填配对地址（wss://…#密钥 或 ws://…?token=…）") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { connect(manual) },
                enabled = manual.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("连接") }

            if (lastUrl != null) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { connect(lastUrl!!) }, modifier = Modifier.fillMaxWidth()) {
                    Text("重连上次的后端", maxLines = 1)
                }
                Text(
                    lastUrl!!.substringBefore("?"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            if (connecting) {
                Spacer(Modifier.height(16.dp))
                Text("连接中…", color = MaterialTheme.colorScheme.primary)
            }
            val hint = problem ?: closedReason
            if (hint != null && !connecting) {
                Spacer(Modifier.height(16.dp))
                Text(hint, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
