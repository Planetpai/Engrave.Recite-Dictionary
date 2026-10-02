package top.southfish.engrave.importer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import top.southfish.engrave.appContainer
import kotlinx.coroutines.delay

/**
 * 词书导入入口：用户在文件管理器里打开 / 分享一个词书 JSON，系统把文件
 * 投递到这里。导入完成提示一句后自动退出。逻辑与 Wear 版一致，
 * 组件基座为 Material 3。
 *
 * 支持的路径：文件管理器 → 打开 json（ACTION_VIEW）或 分享（ACTION_SEND）。
 * 导入按内容哈希幂等，同一份文件不会插出第二本。
 */
class ImportBookActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data
            }

            Intent.ACTION_VIEW -> intent.data
            else -> null
        }

        setContent {
            var message by remember { mutableStateOf(if (uri == null) "没有收到文件" else "正在导入…") }
            MaterialTheme {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .wrapContentSize(Alignment.Center)
                )
            }
            LaunchedEffect(uri) {
                message = when {
                    uri == null -> "没有收到文件"
                    else -> runCatching {
                        "已导入「${appContainer.backupManager.importBook(uri)}」"
                    }.recover { it.message ?: "导入失败" }.getOrDefault("导入失败")
                }
                delay(2000)
                finish()
            }
        }
    }
}
