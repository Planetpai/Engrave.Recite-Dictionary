package top.southfish.engrave.ui.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拼写卡分块规则：长词按「前 3 字母 + 从尾每 2 字母一组」，短词逐字母。 */
class SpellingChunksTest {

    @Test
    fun `长词拆成前缀加两字母组`() {
        assertEquals(listOf("dis", "a", "bi", "li", "ty"), spellingChunks("disability"))
        assertEquals(listOf("ind", "i", "vi", "du", "al"), spellingChunks("individual"))
    }

    @Test
    fun `短词保持逐字母`() {
        assertEquals(listOf("w", "o", "r", "d"), spellingChunks("word"))
        // 非字母字符（空格、数字等）不参与拼写
        assertEquals(listOf("w", "o", "r", "d"), spellingChunks("word1"))
        assertEquals(listOf("g", "i", "v", "e", "u", "p"), spellingChunks("give up"))
    }

    @Test
    fun `分块按顺序拼回等于单词的全部字母`() {
        for (term in listOf("abandon", "saturday", "individual", "internationalization")) {
            val chunks = spellingChunks(term)
            assertEquals(term, chunks.joinToString(separator = ""))
            assertTrue("$term 有超过 3 字母的分块", chunks.all { it.length <= 3 })
        }
    }
}
