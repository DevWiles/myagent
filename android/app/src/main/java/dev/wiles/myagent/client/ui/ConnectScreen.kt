package dev.wiles.myagent.client.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import dev.wiles.myagent.client.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 招牌：图标里那个 ❯，放大成一枚陶土橙的方块
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary) {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_stat_agent),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("myagent", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "在电脑上跑 ./myagent-tui --relay …（或 --lan），界面里敲 /qr，扫那个码",
                style = MaterialTheme.typography.bodyMedium,
                color = AgentColors.skipped,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    scanner.launch(ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("对准终端里的二维码")
                        setBeepEnabled(false)
                        // 库自带的扫码页默认锁横屏——换成我们的竖屏子类。
                        // 人举着手机扫桌上的终端，姿势就是竖的
                        setCaptureActivity(PortraitCaptureActivity::class.java)
                        setOrientationLocked(true)
                    })
                },
                enabled = !connecting,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("扫码配对", style = MaterialTheme.typography.titleSmall)
            }

            if (lastUrl != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = AgentColors.card,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    onClick = { connect(lastUrl!!) },
                    enabled = !connecting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("重连上次的后端", style = MaterialTheme.typography.titleSmall)
                            Text(
                                lastUrl!!.substringBefore("?").substringBefore("#"),
                                style = CodeText.copy(fontSize = 11.sp),
                                color = AgentColors.skipped,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "或手填",
                    style = MaterialTheme.typography.labelSmall,
                    color = AgentColors.skipped,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            }

            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("配对地址") },
                placeholder = { Text("wss://…#密钥 或 ws://…?token=…", style = CodeText) },
                textStyle = CodeText.copy(fontSize = 14.sp),
                shape = MaterialTheme.shapes.large,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (manual.isNotBlank()) connect(manual) }),
            )
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = { connect(manual) },
                enabled = manual.isNotBlank() && !connecting,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("连接") }

            AnimatedVisibility(visible = connecting) {
                Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("连接中…", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val hint = problem ?: closedReason
            if (hint != null && !connecting) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                ) {
                    Text(
                        hint,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}
