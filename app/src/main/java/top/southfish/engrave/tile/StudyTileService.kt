package top.southfish.engrave.tile

import android.util.Log
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ColorBuilders.argb
import androidx.wear.tiles.DimensionBuilders.degrees
import androidx.wear.tiles.DimensionBuilders.dp
import androidx.wear.tiles.DimensionBuilders.expand
import androidx.wear.tiles.DimensionBuilders.sp
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TimelineBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.runBlocking
import top.southfish.engrave.di.AppContainer
import top.southfish.engrave.reminder.ReminderIntents

/**
 * 学习 Tile：手表左滑一屏的卡片。贴屏缘的 3/4 圆环显示词书完成进度
 * （缺口朝下，与表圈留空隙），环中央放「待复习 N / 今日已完成 N」；
 * 点一下直达学习页（复用通知的直达 extra，断点续背）。
 * 圆环构建失败时自动回退纯文字版（异常见 logcat tag: EngraveTile）。
 *
 * 数据口径与首页 HomeViewModel 完全一致：走 AppContainer 的
 * StudyRepository.observeStats（含 activeBookIds 设置），不自己拼 SQL。
 * 用 15 分钟的 freshness 间隔让系统定时来取新数；用户背完词后，
 * 下一次刷新或明天再看就是新数字。
 */
class StudyTileService : TileService() {

    /** 全 lazy 容器：系统绑定服务但从不请求 Tile 时不付出建库成本 */
    private val container by lazy { AppContainer(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        serviceScope.future {
            // 圆环版构建失败（个别表盘渲染器不吃 Arc 等）时回退纯文字版，
            // 绝不让用户对着系统占位骨架；异常打到 logcat 供定位（tag: EngraveTile）
            runCatching { buildTile() }
                .onFailure { Log.e(TAG, "ring tile failed, fallback to text", it) }
                .getOrElse { buildFallbackTile() }
        }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        serviceScope.future {
            // 布局全用内联文本，没有位图资源：空资源表，版本与 Tile 对齐即可
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun buildTile(): TileBuilders.Tile {
        val data = loadTileData()
        // 贴屏缘大圆环（留 EDGE_GAP 空隙），两行字放环中央，不显示百分比数字
        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setPadding(
                                ModifiersBuilders.Padding.Builder()
                                    .setAll(dp(EDGE_GAP_DP))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(trackArc())
                    .addContent(progressArc(data.progress))
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        text(
                            if (data.due > 0) "待复习 ${data.due}" else "没有待复习",
                            18f, 0xFFFFFFFF.toInt()
                        )
                    )
                    .addContent(text("今日已完成 ${data.done}", 13f, 0xFF9E9E9E.toInt()))
                    .build()
            )
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("tap_study")
                            .setOnClick(launchStudyAction())
                            .build()
                    )
                    .build()
            )
            .build()

        return wrapInTile(root)
    }

    /** 纯文字兜底版：与圆环版同信息、同点击行为，不含 Arc/Box 等新元素。 */
    private suspend fun buildFallbackTile(): TileBuilders.Tile {
        val data = loadTileData()
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                text(
                    if (data.due > 0) "待复习 ${data.due}" else "没有待复习",
                    20f, 0xFFFFFFFF.toInt()
                )
            )
            .addContent(text("今日已完成 ${data.done}", 13f, 0xFF9E9E9E.toInt()))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("tap_study")
                            .setOnClick(launchStudyAction())
                            .build()
                    )
                    .build()
            )
            .build()
        return wrapInTile(column)
    }

    private fun wrapInTile(root: LayoutElementBuilders.LayoutElement): TileBuilders.Tile =
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MILLIS)
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder().setRoot(root).build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    /** 点 Tile 打开 MainActivity 并带直达 extra，与通知点击同一条路。 */
    private fun launchStudyAction(): ActionBuilders.LaunchAction {
        val appContext = applicationContext
        return ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(appContext.packageName)
                    .setClassName(appContext.packageName + ".MainActivity")
                    .addKeyToExtraMapping(
                        ReminderIntents.EXTRA_AUTO_START_STUDY,
                        ActionBuilders.AndroidBooleanExtra.Builder()
                    .setValue(true)
                    .build()
                    )
                    .build()
            )
            .build()
    }

    /**
     * 3/4 圆环：缺口朝下（90°），0° 在 12 点方向、顺时针增长，起始角 225°。
     * 注意：同一 Arc 里的多个 ArcLine 是首尾相接排列的，轨道和进度必须拆成
     * 两个独立的 Arc 叠放，进度才会从缺口左端开始增长。
     */
    private fun trackArc(): LayoutElementBuilders.Arc =
        LayoutElementBuilders.Arc.Builder()
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .setAnchorAngle(degrees(225f))
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(270f))
                    .setThickness(dp(9f))
                    .setColor(argb(0xFF3C4043.toInt()))
                    .build()
            )
            .build()

    private fun progressArc(progress: Float): LayoutElementBuilders.Arc =
        LayoutElementBuilders.Arc.Builder()
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .setAnchorAngle(degrees(225f))
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(270f * progress.coerceIn(0f, 1f)))
                    .setThickness(dp(9f))
                    .setColor(argb(0xFF8AB4F8.toInt()))
                    .build()
            )
            .build()

    private fun text(content: String, sizeSp: Float, color: Int) =
        LayoutElementBuilders.Text.Builder()
            .setText(content)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(sizeSp))
                    .setColor(argb(color))
                    .build()
            )
            .build()

    private data class TileData(val due: Int, val done: Int, val progress: Float)

    /** 读数失败（建库慢、无词书等）按 0 显示，Tile 不能崩。 */
    private suspend fun loadTileData(): TileData = runCatching {
        val bookIds = container.settingsRepository.queueConfig.first().activeBookIds
        val stats = container.studyRepository.observeStats(bookIds).first()
        TileData(stats.dueNow, stats.reviewedToday, stats.progress)
    }.getOrDefault(TileData(0, 0, 0f))

    private companion object {
        const val TAG = "EngraveTile"
        const val RESOURCES_VERSION = "1"
        const val FRESHNESS_MILLIS = 15L * 60L * 1000L
        const val EDGE_GAP_DP = 14f
    }
}
