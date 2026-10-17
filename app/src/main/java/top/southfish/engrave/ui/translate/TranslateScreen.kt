package top.southfish.engrave.ui.translate

import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import top.southfish.engrave.translate.TranslateCatalog
import top.southfish.engrave.translate.TranslateEngineType
import top.southfish.engrave.translate.TranslateModelEntry
import top.southfish.engrave.translate.TranslatePhase
import top.southfish.engrave.translate.TranslateUiState
import top.southfish.engrave.translate.TranslateViewModel
import top.southfish.engrave.ui.books.FilePickerScreen
import top.southfish.engrave.ui.books.PickerMode
import top.southfish.engrave.ui.components.AnimatedTimeText
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.components.rememberTimeTextVisible
import top.southfish.engrave.ui.components.timeTextPadding
import top.southfish.engrave.ui.vocabViewModel

private val TranslateFieldBackground = Color(0xFF1B202A)

/**
 * 整句翻译页：搜索页长按搜索框进入（见执行清单 §8）。
 *
 * 手动输入（原生文本框，中英自动判方向）→ 出译文；下方是「翻译引擎」模型管理区，
 * 用户可自选 ONNX 量化档（两个方向）或 Google ML Kit，逐条下载/删除，
 * 国内拉不动 GitHub 时可用「手动导入」选本地 zip 兜底。
 *
 * 内存纪律：进页创建引擎（首次点「翻译」时），**离开页面立刻释放**（[DisposableEffect]）。
 * 手表内存小，不允许把 ONNX session 留在后台。
 *
 * 不带库版（[BuildConfig.WITH_TRANS] = false）没有长按入口，本页不可达，
 * 所以这里不写降级文案。
 */
@Composable
fun TranslateScreen(modifier: Modifier = Modifier) {
    val viewModel = vocabViewModel<TranslateViewModel> { container ->
        TranslateViewModel(container.appContext)
    }
    val state by viewModel.uiState.collectAsState()
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)
    val timeVisible = rememberTimeTextVisible(listState)

    // 离开页面立刻释放模型（ONNX session 很占内存）
    DisposableEffect(Unit) {
        onDispose { viewModel.releaseEngine() }
    }

    // 手动导入模型时必须先选本地 zip：以整屏覆盖层复用文件选择器，
    // 选中的文件直接回传本页导入（避免跨 ViewModel 通信）
    var pendingImport by remember { mutableStateOf<TranslateModelEntry?>(null) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = modifier,
            timeText = { AnimatedTimeText(timeVisible) },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 24.dp, start = 12.dp, end = 12.dp)
            ) {
                // 输入框用原生 EditText——与搜索页同款，手表键盘的提取编辑条才能实时同步
                TranslateInputField(onValueChange = viewModel::onInputChange)

                // 方向提示 + 翻译按钮常驻顶部（不用滚动去找）
                TranslateActionBar(
                    directionText = "自动识别：${state.direction.displayName}",
                    busy = state.phase == TranslatePhase.TRANSLATING ||
                        state.phase == TranslatePhase.LOADING,
                    canTranslate = state.canTranslate,
                    onTranslate = viewModel::translate
                )

                Box(Modifier.weight(1f)) {
                    ScalingLazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = timeTextPadding(),
                            bottom = 16.dp
                        )
                    ) {
                        TranslateResultArea(state)

                        item {
                            ListHeader { Text("翻译引擎") }
                        }
                        item {
                            Text(
                                "可自选引擎与档位；首次使用需下载模型",
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                            )
                        }
                        items(TranslateCatalog.listed, key = { it.key }) { entry ->
                            ModelRow(
                                entry = entry,
                                selected = state.selected.key == entry.key,
                                installed = entry.engine == TranslateEngineType.ML_KIT ||
                                    state.installed.contains(entry.key),
                                busy = state.busyKey == entry.key,
                                progress = state.progress,
                                anyBusy = state.busyKey != null,
                                onSelect = { viewModel.select(entry) },
                                onDownload = { viewModel.download(entry) },
                                onDelete = { viewModel.delete(entry) },
                                onImport = { pendingImport = entry }
                            )
                        }
                    }
                }
            }
        }

        pendingImport?.let { entry ->
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
            ) {
                Chip(
                    label = { Text("取消导入") },
                    onClick = { pendingImport = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Box(Modifier.weight(1f)) {
                    FilePickerScreen(
                        mode = PickerMode.MODEL_ZIP,
                        onPicked = { file ->
                            viewModel.importZip(entry, Uri.fromFile(file))
                            pendingImport = null
                        }
                    )
                }
            }
        }
    }
}

/** 顶部固定区：方向提示（灰字）+ 翻译按钮；推理/加载中时按钮变状态文字。 */
@Composable
private fun TranslateActionBar(
    directionText: String,
    busy: Boolean,
    canTranslate: Boolean,
    onTranslate: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = directionText,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onTranslate,
            enabled = canTranslate && !busy,
            modifier = Modifier.weight(1f)
        ) {
            Text(if (busy) "翻译中…" else "翻译")
        }
    }
}

/** 结果区：加载/翻译中提示、译文卡片、错误信息。 */
private fun ScalingLazyListScope.TranslateResultArea(state: TranslateUiState) {
    when (state.phase) {
        TranslatePhase.DOWNLOADING -> item {
            StatusText(
                "正在下载模型…" + if (state.progress >= 0f) " ${(state.progress * 100).toInt()}%"
                else ""
            )
        }
        TranslatePhase.LOADING -> item { StatusText("正在加载模型…") }
        TranslatePhase.TRANSLATING -> item { StatusText("翻译中…") }
        else -> Unit
    }

    if (state.output.isNotBlank()) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colors.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    "译文 · ${state.lastDirection?.displayName.orEmpty()}",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.primary
                )
                Text(
                    state.output,
                    style = MaterialTheme.typography.body1,
                    modifier = Modifier.padding(top = 2.dp)
                )
                state.lastEngineName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
            }
        }
    }

    state.message?.let { message ->
        item {
            Text(
                text = message,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }

    // 选中的引擎/档位在当前输入方向下还没下载：提示去下面下载对应模型
    if (!state.neededEntryReady && state.input.isNotBlank()) {
        val needed = if (state.selected.engine == TranslateEngineType.ML_KIT) null
        else TranslateCatalog.onnx(state.direction, state.selected.tier!!)
        needed?.let { entry ->
            item {
                Text(
                    text = "当前方向未下载：请在下方下载「${entry.label}」",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

/** 一条模型：点卡片=切到该引擎/档位；已下载显示「删除」，未下载显示「下载 / 手动导入」。 */
@Composable
private fun ModelRow(
    entry: TranslateModelEntry,
    selected: Boolean,
    installed: Boolean,
    busy: Boolean,
    progress: Float,
    anyBusy: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colors.primary.copy(alpha = 0.18f)
                else MaterialTheme.colors.surface
            )
            .clickable(enabled = !anyBusy) { onSelect() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = (if (selected) "✓ " else "") + entry.label,
            style = MaterialTheme.typography.body1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = entry.sizeLabel,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        when {
            busy -> Text(
                text = "下载中…" + if (progress >= 0f) " ${(progress * 100).toInt()}%" else "",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            entry.engine == TranslateEngineType.ML_KIT -> Text(
                text = if (selected) "已选用（模型由 Google 下发）" else "点击选用",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            installed -> Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Chip(
                    label = { Text(if (selected) "已就绪" else "已下载 · 选用") },
                    onClick = onSelect,
                    modifier = Modifier.weight(1f)
                )
                Chip(
                    label = { Text("删除") },
                    onClick = onDelete,
                    modifier = Modifier.weight(1f)
                )
            }
            else -> Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Chip(
                    label = { Text("下载") },
                    onClick = onDownload,
                    modifier = Modifier.weight(1f)
                )
                Chip(
                    label = { Text("手动导入") },
                    onClick = onImport,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 翻译输入框。与搜索页同款原生 [EditText]（手表上 HoneyBoard 走全屏提取编辑，
 * Compose 的 BasicTextField 在重开会话时提取文本不刷新，会“打不出字”）。
 */
@Composable
private fun TranslateInputField(onValueChange: (String) -> Unit) {
    val textColor = MaterialTheme.colors.onSurface.toArgb()
    val hintColor = MaterialTheme.colors.onSurfaceVariant.toArgb()
    val horizontalPadding = with(LocalDensity.current) { 14.dp.roundToPx() }

    var editText by remember { mutableStateOf<EditText?>(null) }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(TranslateFieldBackground)
            .clickable {
                editText?.let { field ->
                    field.requestFocus()
                    val imm = field.context.getSystemService(InputMethodManager::class.java)
                    imm?.showSoftInput(field, 0)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                EditText(ctx).apply {
                    background = null
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setTextColor(textColor)
                    setHintTextColor(hintColor)
                    hint = "输入要翻译的句子"
                    textSize = 15f
                    setPadding(horizontalPadding, 0, horizontalPadding, 0)
                    gravity = Gravity.CENTER_VERTICAL
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            onValueChange(s?.toString().orEmpty())
                        }
                    })
                    setOnEditorActionListener { v, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            val imm = v.context.getSystemService(InputMethodManager::class.java)
                            imm?.hideSoftInputFromWindow(v.windowToken, 0)
                            v.clearFocus()
                            true
                        } else {
                            false
                        }
                    }
                    editText = this
                    // 进页面自动聚焦并拉起键盘（等转场动画过去）
                    postDelayed(
                        {
                            requestFocus()
                            val imm = context.getSystemService(InputMethodManager::class.java)
                            imm?.showSoftInput(this, InputMethodManager.SHOW_FORCED)
                        },
                        400
                    )
                }
            }
        )
    }
}
