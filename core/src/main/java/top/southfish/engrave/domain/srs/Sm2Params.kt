package top.southfish.engrave.domain.srs

/**
 * SM-2-ish 调度器的可调参数。
 *
 * 设计说明（2026-09 改造）：
 * - 学习阶段：单步 [1 小时]，精确时间到期（不锚定 4AM）。
 *   一个词在学习会话内的「英→中→拼写」三遍考法只在离队时落一次 GOOD，
 *   于是新词学完统一在 1 小时后回到复习队列；1 小时那次复习再答会才毕业。
 *   早期的 [10 分钟, 1 小时] 两步会被同会话的三遍评分连续走完，分钟级到期
 *   时间被立刻覆盖，10 分钟那步在正常路径上永远不会出现，故移除。
 * - 复习阶段：先走固定阶梯 [1, 2, 4, 7, 15] 天，15 天后转 SM-2 乘法（interval × ease）
 * - 所有天级间隔的到期时间锚定到「下一个凌晨 4 点」+ (间隔-1) 天，
 *   保证同一天学的卡统一在次日 4 点到期，避免分散在不同时刻
 * - fuzz 只加在天级间隔上，单位为整数天（±5%），1 小时学习步不加抖动
 */
data class Sm2Params(
    val initialEase: Double = 2.5,
    val minimumEase: Double = 1.3,

    /** 学习阶段的分钟级步骤：学完 1 小时后到期复习。 */
    val learningStepsMinutes: List<Double> = listOf(60.0),
    /** 重记步骤（RELEARNING）：忘词后 1 小时再考一次，通过才按折半间隔毕业。 */
    val relearningStepsMinutes: List<Double> = listOf(60.0),

    /**
     * 复习阶段的天级阶梯。走完最后一档（15 天）后转 SM-2 乘法。
     * 用 stepIndex（REVIEW 阶段复用此字段做档位索引）定位：
     *   stepIndex=0 → 1 天, 1 → 2 天, 2 → 4 天, 3 → 7 天, 4 → 15 天
     *   stepIndex >= 5 → 走 interval × ease
     */
    val reviewIntervalLadderDays: List<Double> = listOf(1.0, 2.0, 4.0, 7.0, 15.0),

    val hardIntervalMultiplier: Double = 0.8,
    val hardEasePenalty: Double = 0.15,
    val againEasePenalty: Double = 0.20,

    /** GOOD 的 ease 奖励。ease 跌破 [lowEaseThreshold] 时用 [goodEaseBonusLowEase]。 */
    val goodEaseBonus: Double = 0.05,
    val goodEaseBonusLowEase: Double = 0.10,
    val lowEaseThreshold: Double = 2.0,

    val easyEaseBonus: Double = 0.25,
    val easyRungJump: Int = 2,

    /**
     * 浏览阶段「已学过」（NEW EASY）的毕业间隔档位：直接跳到阶梯末尾（15 天）。
     * 用户主动声明「已经熟练背诵过」，语义强于 Anki 的 Easy，间隔给最长的天级档。
     * 下次 GOOD 直接进 SM-2 乘法区（15 × ease）。
     */
    val easyGraduationRungIndex: Int = 4,
    /**
     * NEW EASY 毕业时额外的整数天 fuzz（±N 天），用于把「已学过」的词分散开，
     * 避免全部挤在同一天到期。15 天 × 5% 不足 1 天，通用 fuzz 给不出抖动，
     * 所以对 EASY 单独加这一份。
     */
    val easyFuzzDays: Int = 3,

    /** 忘词后重记毕业的新间隔 = max(minimumLapseIntervalDays, 旧间隔 × lapseIntervalMultiplier)。 */
    val lapseIntervalMultiplier: Double = 0.5,
    val minimumLapseIntervalDays: Double = 1.0,

    val maximumIntervalDays: Double = 365.0 * 5,
    val resetRepetitionsOnLapse: Boolean = true,

    /** fuzz：天级间隔的随机抖动比例（±5%），取整为整数天。1 小时学习步不加。 */
    val fuzzRatio: Double = 0.05,

    /** 逾期补偿：新间隔 += 逾期天数 × 此系数，上限 = 原间隔 × [overdueCompensationMaxMultiplier]。 */
    val overdueCompensationFactor: Double = 0.5,
    val overdueCompensationMaxMultiplier: Double = 2.0
) {
    companion object {
        val Default = Sm2Params()
    }
}
