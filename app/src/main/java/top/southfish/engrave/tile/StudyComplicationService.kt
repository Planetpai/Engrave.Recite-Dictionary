package top.southfish.engrave.tile

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.EmptyComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.southfish.engrave.di.AppContainer
import top.southfish.engrave.reminder.ReminderIntents

/**
 * 表盘复杂功能：显示待复习数（SHORT_TEXT），点一下直达学习页。
 *
 * 数据口径与 Tile 一致：AppContainer + StudyRepository.observeStats。
 * 系统按 manifest 里的 UPDATE_PERIOD_SECONDS（15 分钟）来拉数；
 * 表盘编辑器里展示 getPreviewData 的占位样例。
 */
class StudyComplicationService : ComplicationDataSourceService() {

    private val container by lazy { AppContainer(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        // 只声明了 SHORT_TEXT，其他类型理论上不会来，防御一下
        if (request.complicationType != ComplicationType.SHORT_TEXT) {
            listener.onComplicationData(EmptyComplicationData())
            return
        }
        serviceScope.launch {
            val due = runCatching {
                val bookIds = container.settingsRepository.queueConfig.first().activeBookIds
                container.studyRepository.observeStats(bookIds).first().dueNow
            }.getOrDefault(0)
            listener.onComplicationData(buildShortText(due))
        }
    }

    /** 表盘选择器里的预览样例。 */
    override fun getPreviewData(complicationType: ComplicationType) =
        if (complicationType == ComplicationType.SHORT_TEXT) {
            buildShortText(preview = true)
        } else {
            EmptyComplicationData()
        }

    private fun buildShortText(due: Int = 0, preview: Boolean = false) =
        ShortTextComplicationData.Builder(
            text = plain(if (preview) "12" else due.toString()),
            contentDescription = plain("待复习单词数")
        )
            .setTitle(plain("待复习"))
            .apply { if (!preview) setTapAction(tapIntent()) }
            .build()

    /** 1.1.1 没有 plainText 工厂，用 PlainComplicationText 造静态文案 */
    private fun plain(s: String) = PlainComplicationText.Builder(s).build()

    /** 点复杂功能直达学习页；requestCode 与通知的 PendingIntent 区分开 */
    private fun tapIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQ_CODE,
        Intent().setClassName(packageName, packageName + ".MainActivity").apply {
            putExtra(ReminderIntents.EXTRA_AUTO_START_STUDY, true)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REQ_CODE = 1001
    }
}
