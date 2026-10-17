package top.southfish.engrave

import android.content.Context

/**
 * 本端在 GitHub Release 资产名里的「端别标签」，供「检查更新」区分本端包。
 *
 * 带库版按本机 ABI 出 v7a / v8a 两个包，标签必须精确到 ABI，否则会下到装不上的包；
 * 不带库版无原生库，ABI 拆出来的两个包内容相同，标签固定即可（匹配逻辑允许 ABI 尾巴）。
 */
object AppReleaseInfo {

    fun assetTag(context: Context): String =
        if (BuildConfig.WITH_TRANS) "wearos-withtrans-${abiTag(context)}" else "wearos-notrans"

    /**
     * 本机主 ABI → 资产名里的 abi 段。优先看自己的原生库目录
     * （带库版 APK 只含本机 ABI 的 .so，装哪个包目录就是哪个），
     * 兜底再问系统支持列表。
     */
    private fun abiTag(context: Context): String {
        val libDir = context.applicationInfo.nativeLibraryDir
        if (libDir != null) {
            if (libDir.contains("arm64")) return "v8a"
            if (libDir.contains("arm")) return "v7a"
        }
        return if (android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("arm64") == true) "v8a"
        else "v7a"
    }
}
