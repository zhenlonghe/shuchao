package dev.zhenlong.reader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zhenlong.reader.BuildConfig
import dev.zhenlong.reader.R
import dev.zhenlong.reader.ReaderApp
import dev.zhenlong.reader.data.HighlightExport
import dev.zhenlong.reader.scan.ScanState
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val reader = app as ReaderApp
    private val prefs = reader.prefs

    val settings = reader.settings
    val scan = reader.scanner.state
    val exportBlocked = reader.highlightExportBlocked

    fun setLibraryRoot(uri: Uri) {
        val resolver = reader.contentResolver
        // 要写：划线统一导出到书库根目录的文本文件（HighlightExport）。系统选择器给的授权本来就含写，拿不到写就只拿读
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        // 只释放旧书库的授权（读写都放，新版拿的是读写）；字体文件夹的授权要留着。
        // 旧书库恰好也是字体文件夹时不放：字体还要从那里读
        val fontsFolder = settings.value?.fontsFolderUri?.let(Uri::parse)
        settings.value?.libraryRootUri?.let(Uri::parse)?.takeIf { it != uri && it != fontsFolder }?.let { old ->
            val held = resolver.persistedUriPermissions.firstOrNull { it.uri == old }
            val flags = (if (held?.isReadPermission != false) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (held?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { resolver.releasePersistableUriPermission(old, flags) }
        }
        viewModelScope.launch {
            prefs.setLibraryRootUri(uri.toString())
            reader.startScan(uri)
            reader.exportHighlights()
        }
    }

    fun rescan() {
        settings.value?.libraryRootUri?.let { reader.startScan(Uri.parse(it)) }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val exportBlocked by vm.exportBlocked.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setLibraryRoot(uri)
    }

    Column(Modifier.fillMaxSize().background(Color.White).stableSystemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = ScreenMargin - BarIconInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon(R.drawable.ic_back, "返回", onBack)
            Text("设置", fontSize = 18.sp)
        }
        val s = settings ?: return@Column

        val scanText = when (val st = scan) {
            is ScanState.Running -> if (st.total > 0) "正在扫描 ${st.done}/${st.total}" else "正在扫描"
            is ScanState.Failed -> st.message
            ScanState.Idle -> null
        }
        SettingRow(
            label = "书库文件夹",
            detail = listOfNotNull(
                s.libraryRootUri?.let(::displayPath) ?: "未选择",
                scanText,
                // 老版本只拿了读授权：划线文件写不进去，重新选一次同一个文件夹即可
                "划线写不进「${HighlightExport.FILE_NAME}」：点这里重新选择一次书库文件夹".takeIf { exportBlocked && s.libraryRootUri != null },
            ).joinToString("\n"),
            onClick = { pickFolder.launchOrToast(context, s.libraryRootUri?.let(Uri::parse)) },
            action = "重新扫描".takeIf { s.libraryRootUri != null && scan !is ScanState.Running },
            onAction = vm::rescan,
        )
        // 版本号放在页面最底下，一行灰色小字：不算一项设置，不占一行设置的位置
        Spacer(Modifier.weight(1f))
        Text(
            "墨案 ${BuildConfig.VERSION_NAME}",
            Modifier.fillMaxWidth().padding(bottom = ScreenMargin),
            color = Color(0xFF5A5A5A),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** content://…/tree/primary%3ABooks%2F漫画 → 内部存储/Books/漫画 */
private fun displayPath(treeUri: String): String = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
    val volume = docId.substringBefore(':')
    val path = docId.substringAfter(':', "")
    listOf(if (volume == "primary") "内部存储" else volume, path).filter { it.isNotEmpty() }.joinToString("/")
}.getOrDefault(treeUri)

@Composable
private fun SettingRow(
    label: String,
    value: String? = null,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(start = ScreenMargin, top = 12.dp, bottom = 12.dp)) {
            Text(label, fontSize = 16.sp)
            if (detail != null) {
                Text(detail, Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) Text(value, Modifier.padding(horizontal = ScreenMargin), fontSize = 16.sp)
        if (action != null) {
            Text(
                action,
                Modifier.clickable(onClick = onAction).padding(horizontal = ScreenMargin, vertical = 16.dp),
                fontSize = 16.sp,
                textDecoration = TextDecoration.Underline,
            )
        }
    }
}
