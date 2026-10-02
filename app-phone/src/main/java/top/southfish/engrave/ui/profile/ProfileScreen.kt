package top.southfish.engrave.ui.profile

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import top.southfish.engrave.ui.components.Chip
import top.southfish.engrave.ui.components.ListHeader
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import top.southfish.engrave.domain.model.UserProfile
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.vocabViewModel
import kotlinx.coroutines.delay

/**
 * 我的页（首页右往左滑的第二页）。
 *
 * 头像 + 昵称 + 坚持天数，一条灰线下面是功能入口：统计、词书管理、
 * 背单词设置、数据管理、错题集。
 *
 * 交互（用户定稿）：头像**只响应长按**（进应用内相册选图），点头像不再
 * 轮换预置表情；昵称默认只展示，**长按**才进入编辑。编辑框特意用原生
 * EditText——手表 HoneyBoard 的全屏提取模式下 Compose BasicTextField
 * 会出现打不出字的老问题（搜索框同款坑）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileContent(
    onOpenStats: () -> Unit,
    onOpenBooks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenData: () -> Unit,
    onPickAvatar: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<ProfileViewModel> { container ->
        ProfileViewModel(
            settingsRepository = container.settingsRepository,
            studyRepository = container.studyRepository
        )
    }
    val state by viewModel.uiState.collectAsState()

    // 自定义头像：选图后存 filesDir，版本号变了重新读。
    // 解码放到 IO 线程并按 2 倍降采样（源图 ≤512px，头像显示不到 200px）——
    // 旧实现在组合（主线程）里直接 decodeFile 512px JPEG，进「我的」页必卡一下。
    val context = androidx.compose.ui.platform.LocalContext.current
    val avatarFile = remember { java.io.File(context.filesDir, AvatarImage.FILE_NAME) }
    var avatarBitmap by remember(state.avatarImageVersion) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(state.avatarImageVersion) {
        avatarBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (!avatarFile.exists()) {
                    null
                } else {
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                    android.graphics.BitmapFactory.decodeFile(avatarFile.absolutePath, opts)
                        ?.let { soft ->
                            // 转 HARDWARE 位图：数据只留 GPU 纹理，列表滚动每帧重绘
                            // 不用再从 CPU 侧上传；低内存设备 copy 失败就退回软件位图
                            runCatching {
                                soft.copy(android.graphics.Bitmap.Config.HARDWARE, false)
                            }.getOrNull() ?: soft
                        }
                        ?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    // 昵称：默认只展示，长按才进入编辑；内容变化 400ms 后自动保存
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(state.userName) { mutableStateOf(state.userName) }
    LaunchedEffect(nameDraft) {
        if (nameDraft != state.userName) {
            delay(400)
            viewModel.setUserName(nameDraft)
        }
    }

    rememberRotaryScrollable(listState)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = top.southfish.engrave.ui.components.timeTextPadding(16.dp),
            bottom = 32.dp
        )
    ) {
        item { ListHeader { Text("我的") } }

        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        // 只保留长按：点头像不再有任何动作
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { onPickAvatar() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = avatarBitmap
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp,
                            contentDescription = "头像",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = UserProfile.AVATARS[state.avatarIndex],
                            fontSize = 26.sp
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "长按头像选照片",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "连续 ${state.streakDays} 天 · 已学 ${state.learnedWords} 词",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        item {
            if (editingName) {
                NameEditField(
                    initial = nameDraft,
                    onValueChange = { nameDraft = it },
                    onDone = {
                        viewModel.setUserName(nameDraft.trim())
                        editingName = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { editingName = true }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.userName.ifEmpty { "长按设置昵称" },
                        fontSize = 16.sp,
                        color = if (state.userName.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        item {
            // 大纲里那条灰线：把资料区和功能区分开
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .fillMaxWidth(0.7f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            )
        }

        menuChip("学习统计", onOpenStats)
        menuChip("词书管理", onOpenBooks)
        menuChip("背单词设置", onOpenSettings)
        menuChip("数据管理", onOpenData)

        item {
            // 第二条灰线：功能区和应用信息区分开
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .fillMaxWidth(0.7f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            )
        }
        item { AboutSection() }
    }
}

/**
 * 「我的」页底部的应用详情：应用图标 + 名称/版本号（运行时读 PackageInfo，
 * 不硬编码）、开发者署名、AI 生成声明和开源内容声明（Apache-2.0 组件 +
 * ECDICT 词典数据 MIT）。
 */
@Composable
private fun AboutSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 应用图标：自适应图标 XML 不能用 painterResource，先一次性转成
        // Bitmap 用 Image 画。旧实现用 AndroidView 包 ImageView——每一帧
        // 都走 View 互操作绘制（measure/draw 桥接），列表滚动时纯浪费。
        val iconBitmap = remember {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(context.packageName)
                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    .also { bmp ->
                        val canvas = android.graphics.Canvas(bmp)
                        drawable.setBounds(0, 0, w, h)
                        drawable.draw(canvas)
                    }
            }.getOrNull()
        }
        if (iconBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = iconBitmap.asImageBitmap(),
                contentDescription = "应用图标",
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(text = "刻词 v$versionName", fontSize = 13.sp)
        Text(
            text = "开发者 SouthFish",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "本应用由 AI coding 筑成",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "本应用使用了开源内容\n" +
                "Jetpack Compose · AndroidX · Room\n" +
                "（Apache License 2.0）\n" +
                "ECDICT 词典数据（MIT License）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )
        // 最底部的 QQ 交流群：点一下复制群号，方便在手表上记下后去手机加群
        Spacer(Modifier.height(8.dp))
        var qqCopied by remember { mutableStateOf(false) }
        val clipboard = LocalClipboardManager.current
        LaunchedEffect(qqCopied) {
            if (qqCopied) {
                delay(1500)
                qqCopied = false
            }
        }
        Text(
            text = if (qqCopied) "群号已复制" else "QQ 交流群 832695614",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(CircleShape)
                .clickable {
                    clipboard.setText(AnnotatedString(QQ_GROUP_NUMBER))
                    qqCopied = true
                }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** 用户反馈用的 QQ 交流群号，「我的」页底部点击复制。 */
private const val QQ_GROUP_NUMBER = "832695614"

private fun LazyListScope.menuChip(label: String, onClick: () -> Unit) {
    item {
        Chip(
            label = { Text(label) },
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 2.dp)
        )
    }
}

/**
 * 昵称编辑框。
 *
 * 特意用原生 [EditText] 而不是 Compose 的 BasicTextField：手表上
 * HoneyBoard 走全屏提取（extracted）编辑模式，Compose 1.5.x 的
 * BasicTextField 在「键盘收起后重新点开、字段里已有文字」的会话里，
 * 输入法的提取文本不再随输入刷新——用户打字时键盘上的文本条静止不动，
 * 看起来就是“打不出字”。原生 EditText 是 IME 提取机制的参考实现，
 * 全屏编辑条会实时同步，不存在这个问题（与搜索框同因同果）。
 */
@Composable
private fun NameEditField(
    initial: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val horizontalPadding = with(androidx.compose.ui.platform.LocalDensity.current) {
        10.dp.roundToPx()
    }

    AndroidView(
        modifier = modifier.height(40.dp),
        factory = { ctx ->
            EditText(ctx).apply {
                background = null
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_DONE
                setTextColor(textColor)
                setHintTextColor(hintColor)
                hint = "昵称"
                textSize = 16f
                setPadding(horizontalPadding, 0, horizontalPadding, 0)
                gravity = Gravity.CENTER
                setText(initial)
                setSelection(text.length)
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
                        onDone()
                        true
                    } else {
                        false
                    }
                }
                // 进编辑态自动聚焦并拉起键盘（等转场/组合稳定）
                postDelayed(
                    {
                        requestFocus()
                        val imm = context.getSystemService(InputMethodManager::class.java)
                        imm?.showSoftInput(this, InputMethodManager.SHOW_FORCED)
                    },
                    300
                )
            }
        }
    )
}
