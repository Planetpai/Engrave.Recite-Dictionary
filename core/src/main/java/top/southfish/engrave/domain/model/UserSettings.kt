package top.southfish.engrave.domain.model

/** 我的页顶部展示的用户资料，随数据一起导出。 */
data class UserProfile(
    val userName: String = "",
    val avatarIndex: Int = 0
) {
    companion object {
        /** 预置头像，按下标循环选择。 */
        val AVATARS = listOf("🦁", "🐼", "🦊", "🐸", "🐙", "🦉")
        const val DEFAULT = ""
    }
}

/** 详情页释义显示偏好：只中文 / 只英文 / 中英都要。 */
enum class DefinitionDisplay { ZH, EN, BOTH }
