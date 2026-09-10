package top.southfish.engrave.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 提醒时间的时/分转轮组合（设置页和首启引导共用）。
 * 分钟按 5 分钟一档，拖动即改。滑动只响应数字条区域（宽度自动与文字等宽），
 * 两侧空白不吞手势。
 */
@Composable
fun ReminderWheels(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        NumberWheel(
            values = (0..23).toList(),
            selected = hour,
            onSelected = onHourChange,
            format = { "%02d 时".format(it) },
            modifier = Modifier.weight(1f)
        )
        NumberWheel(
            values = (0..55 step 5).toList(),
            selected = minute,
            onSelected = onMinuteChange,
            format = { "%02d 分".format(it) },
            modifier = Modifier.weight(1f)
        )
    }
}
