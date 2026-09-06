package com.example.vocabwatch.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.vocabwatch.domain.model.UserProfile
import com.example.vocabwatch.ui.components.rememberRotaryScrollable
import com.example.vocabwatch.ui.vocabViewModel
import kotlinx.coroutines.delay

/**
 * 我的页（首页右往左滑的第二页）。
 *
 * 头像 + 昵称 + 坚持天数，一条灰线下面是功能入口：统计、词书管理、
 * 背单词设置、数据管理、错题集。头像点一下换预置形象，昵称就地编辑
 * （自动保存，不用找确认按钮）。
 */
@Composable
fun ProfileContent(
    onOpenStats: () -> Unit,
    onOpenBooks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenData: () -> Unit,
    listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState,
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<ProfileViewModel> { container ->
        ProfileViewModel(
            settingsRepository = container.settingsRepository,
            appContext = container.appContext,
            studyRepository = container.studyRepository
        )
    }
    val state by viewModel.uiState.collectAsState()

    // 自定义头像：选图后存 filesDir，版本号变了重新读
    val context = androidx.compose.ui.platform.LocalContext.current
    val avatarFile = remember { java.io.File(context.filesDir, ProfileViewModel.AVATAR_FILE) }
    val avatarBitmap = remember(state.avatarImageVersion) {
        if (avatarFile.exists()) {
            android.graphics.BitmapFactory.decodeFile(avatarFile.absolutePath)?.asImageBitmap()
        } else {
            null
        }
    }
    var pickFailed by remember { mutableStateOf(false) }
    val avatarPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.setAvatarFromUri(uri) { } }
    fun onAvatarTap() {
        // 优先打开相册/文件选择；手表上没有处理器时退回换预置表情
        runCatching { avatarPicker.launch("image/*") }
            .onFailure { pickFailed = !pickFailed; viewModel.cycleAvatar() }
    }

    // 昵称就地编辑：内容变化 400ms 后自动保存
    var nameDraft by remember(state.userName) { mutableStateOf(state.userName) }
    LaunchedEffect(nameDraft) {
        if (nameDraft != state.userName) {
            delay(400)
            viewModel.setUserName(nameDraft)
        }
    }

    rememberRotaryScrollable(listState)

    ScalingLazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        item { ListHeader { Text("我的") } }

        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colors.surface)
                        .border(2.dp, MaterialTheme.colors.primary, CircleShape)
                        .clickable { onAvatarTap() },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = avatarBitmap,
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
                    text = if (pickFailed) "没有相册，点了换表情" else "点头像换图片",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "连续 ${state.streakDays} 天 · 已学 ${state.learnedWords} 词",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        item {
            NameField(
                value = nameDraft,
                onValueChange = { nameDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 24.dp, end = 24.dp)
            )
        }

        item {
            // 大纲里那条灰线：把资料区和功能区分开
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .fillMaxWidth(0.7f)
                    .height(1.dp)
                    .background(MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.4f))
            )
        }

        menuChip("学习统计", onOpenStats)
        menuChip("词书管理", onOpenBooks)
        menuChip("背单词设置", onOpenSettings)
        menuChip("数据管理", onOpenData)
    }
}

private fun ScalingLazyListScope.menuChip(label: String, onClick: () -> Unit) {
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

@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colors.onSurface,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            ),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = "点这里写昵称",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                inner()
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
