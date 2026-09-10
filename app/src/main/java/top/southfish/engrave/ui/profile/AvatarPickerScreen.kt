package top.southfish.engrave.ui.profile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.vocabViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 头像选图页。
 *
 * 选图链路（长按头像进入本页时自动走一遍）：
 * 1. 厂商图库：Intent.ACTION_PICK + MediaStore 图片 URI。三星 One UI Watch
 *    自带图库会响应它并给出全屏两列大图网格（与其他手表应用选图体验一致），
 *    返回的 URI 带临时读授权，不需要存储权限。注意 Wear 的
 *    frameworkpackagestubs 会给 ACTION_PICK 提供空壳 ResolveInfo，必须按
 *    包名排除，否则启动后什么都不会发生。
 * 2. 应用内相册：用户取消厂商图库（或表上没有厂商图库）时落到本页，
 *    参照三星 One UI Watch 图库的样式做全屏两列方格网格，直接读
 *    MediaStore（targetSdk 29 传统存储下申请 READ_EXTERNAL_STORAGE，
 *    33+ 上以「照片和视频」形式弹出）。选中后把图压进 filesDir，借
 *    ProfileScreen 已有的 avatarImageVersion 流刷新头像。
 */
private data class GalleryImage(val id: Long, val uri: Uri)

@Composable
fun AvatarPickerScreen(
    onDone: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<AvatarPickerViewModel> { container ->
        AvatarPickerViewModel(
            settingsRepository = container.settingsRepository,
            appContext = container.appContext
        )
    }

    val context = LocalContext.current

    // true = 展示应用内相册网格（厂商图库被取消 / 表上没有厂商图库）
    var inAppMode by remember { mutableStateOf(false) }

    // 厂商图库（三星 One UI Watch 图库等）：ACTION_PICK
    val vendorPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            viewModel.save(uri) { ok -> onDone(ok) }
        } else {
            // 用户取消 / 没选 → 落到应用内相册网格
            inAppMode = true
        }
    }

    /** 拉起厂商全屏图库；查不到（或只是 Wear 空壳）就直接进应用内网格。 */
    fun launchVendorGallery() {
        val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val resolved = runCatching { pickIntent.resolveActivity(context.packageManager) }.getOrNull()
        val isWearStub = resolved?.packageName?.contains("frameworkpackagestubs") == true
        if (resolved != null && !isWearStub) {
            vendorPicker.launch(pickIntent)
        } else {
            inAppMode = true
        }
    }

    // 进页面自动弹厂商图库；取消后切到应用内网格
    LaunchedEffect(Unit) { if (!inAppMode) launchVendorGallery() }

    // targetSdk 29 传统存储：全 API 级别统一用旧式 READ_EXTERNAL_STORAGE
    // （33+ 上它会以「照片和视频」形式弹出授权，且覆盖媒体库读取）
    val permission = Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember(inAppMode) {
        mutableStateOf(
            !inAppMode ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(inAppMode) { if (inAppMode && !granted) permissionLauncher.launch(permission) }

    // null = 加载中；空列表 = 相册没照片
    var images by remember(granted) { mutableStateOf<List<GalleryImage>?>(null) }
    LaunchedEffect(granted) {
        if (!inAppMode || !granted) return@LaunchedEffect
        images = withContext(Dispatchers.IO) { queryGalleryImages(context) }
    }

    Box(modifier.fillMaxSize()) {
        TimeText()

        when {
            !inAppMode -> {
                // 厂商图库正在拉起的短暂过渡态；若弹窗异常未出现，可手动切应用内相册
                Column(
                    modifier = Modifier.fillMaxSize().padding(top = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "正在打开系统图库…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    CompactButton(onClick = { inAppMode = true }) {
                        Text("用应用内相册", fontSize = 12.sp)
                    }
                }
            }

            !granted -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "需要照片权限\n读取手表相册选头像",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { permissionLauncher.launch(permission) }) {
                        Text("授予照片权限")
                    }
                }
            }

            images == null -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("读取相册…", fontSize = 12.sp, color = MaterialTheme.colors.onSurfaceVariant)
                }
            }

            images!!.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "手表相册里还没有照片\n（手机同步照片后会出现在这里）",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { launchVendorGallery() }) {
                        Text("打开系统图库")
                    }
                }
            }

            else -> GalleryGrid(
                images = images!!,
                onOpenSystemGallery = { launchVendorGallery() },
                onPick = { uri -> viewModel.save(uri) { ok -> onDone(ok) } }
            )
        }
    }
}

/**
 * 应用内相册网格：参照三星 One UI Watch 图库的全屏两列方格样式。
 * 顶部窄条是标题 + 系统图库入口，下面铺满缩略图，点格子即选。
 */
@Composable
private fun GalleryGrid(
    images: List<GalleryImage>,
    onOpenSystemGallery: () -> Unit,
    onPick: (Uri) -> Unit
) {
    val gridState = rememberLazyGridState()
    rememberRotaryScrollable(gridState)

    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 30.dp, start = 2.dp, end = 2.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 跨两列的窄标题条：相册名 + 系统图库入口
        item(span = { GridItemSpan(2) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择照片 · ${images.size}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                SystemGalleryPill(onClick = onOpenSystemGallery)
            }
        }

        items(images, key = { it.id }) { image ->
            GalleryCell(image = image, onClick = { onPick(image.uri) })
        }
    }
}

/** 标题条右侧的「系统图库」小胶囊按钮，重新拉起厂商图库。 */
@Composable
private fun SystemGalleryPill(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = "系统图库",
            fontSize = 10.sp,
            color = MaterialTheme.colors.primary
        )
    }
}

/** 两列方格缩略图单元，点一下即设为头像。 */
@Composable
private fun GalleryCell(image: GalleryImage, onClick: () -> Unit) {
    val context = LocalContext.current
    // 先查内存缓存：网格快速滚动时格子反复进出组合，旧实现每次都重新
    // loadThumbnail（跨进程调 MediaStore），滚动会一顿一顿。
    var thumb by remember(image.id) {
        mutableStateOf(ThumbnailCache.get(image.uri.toString()))
    }
    LaunchedEffect(image.id) {
        if (thumb != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                // 格子在 450px 圆屏上约 220px 见方，按这个尺寸取缩略图即可
                context.contentResolver.loadThumbnail(image.uri, Size(220, 220), null)
            }.getOrNull()
        }
        if (loaded != null) {
            ThumbnailCache.put(image.uri.toString(), loaded)
            thumb = loaded
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.surface)
            .clickable(onClick = onClick)
    ) {
        val bitmap = thumb
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "相册照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // 加载中只留白格（surface 底色），不放旋转进度条：一屏 6~8 个格子
        // 各挂一个无限动画会让 CPU/GPU 在相册页空转。
    }
}

/**
 * 相册缩略图内存缓存。220px ARGB 一张约 190KB，缓存 32 张 ≈ 6MB 封顶，
 * LRU 淘汰；进程级单例，进出相册页都复用。
 */
private object ThumbnailCache {
    private val cache = object : android.util.LruCache<String, Bitmap>(32) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun get(uri: String): Bitmap? = cache.get(uri)
    fun put(uri: String, bitmap: Bitmap) {
        if (cache.get(uri) == null) cache.put(uri, bitmap)
    }
}

/** 按加入时间倒序读 MediaStore 里的照片，上限 300 张，够头像挑选用。 */
private fun queryGalleryImages(context: Context): List<GalleryImage> = runCatching {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Images.Media._ID)
    context.contentResolver.query(
        collection,
        projection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val list = ArrayList<GalleryImage>()
        while (cursor.moveToNext() && list.size < 300) {
            val id = cursor.getLong(idCol)
            list.add(GalleryImage(id, Uri.withAppendedPath(collection, id.toString())))
        }
        list
    } ?: emptyList()
}.getOrDefault(emptyList())
