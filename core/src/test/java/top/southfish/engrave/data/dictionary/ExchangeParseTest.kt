package top.southfish.engrave.data.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DictRepository.parseExchange 的纯函数单测：不碰 Android/数据库，只验 exchange 解析。 */
class ExchangeParseTest {

    @Test
    fun `lemma word lists its inflections`() {
        // play 的 exchange：列出各变形，0:play 指自己
        val info = DictRepository.parseExchange("p:played/d:played/i:playing/3:plays/s:plays/0:play/1:xyz")
        assertEquals("play", info?.lemma)
        assertEquals("played", info?.forms?.get("p"))
        assertEquals("playing", info?.forms?.get("i"))
        assertEquals("plays", info?.forms?.get("3"))
        assertEquals("plays", info?.forms?.get("s"))
    }

    @Test
    fun `inflected word points back to lemma with self relation`() {
        // dying 的 exchange：0:die 标原型，1:i 标自己是现在分词
        val info = DictRepository.parseExchange("0:die/1:i/s:dyings")
        assertEquals("die", info?.lemma)
        assertEquals("i", info?.selfRelation)
        assertEquals("dyings", info?.forms?.get("s"))
    }

    @Test
    fun `plural noun points to singular lemma`() {
        // dogs 的 exchange：0:dog 标原型，1:s 标复数
        val info = DictRepository.parseExchange("0:dog/1:s")
        assertEquals("dog", info?.lemma)
        assertEquals("s", info?.selfRelation)
    }

    @Test
    fun `irregular past tense maps to lemma`() {
        // abode 的 exchange：1:pd 过去式+过去分词，0:abide 原型，s:abodes 复数
        val info = DictRepository.parseExchange("1:pd/0:abide/s:abodes")
        assertEquals("abide", info?.lemma)
        assertEquals("pd", info?.selfRelation)
        assertEquals("abodes", info?.forms?.get("s"))
    }

    @Test
    fun `blank or null exchange returns null`() {
        assertNull(DictRepository.parseExchange(null))
        assertNull(DictRepository.parseExchange(""))
        assertNull(DictRepository.parseExchange("   "))
    }

    @Test
    fun `exchange without lemma or forms returns null`() {
        // 只有无法识别的码时不返回空壳
        assertNull(DictRepository.parseExchange("9:foo"))
    }

    @Test
    fun `relation labels cover the seven ECDICT codes`() {
        assertEquals("过去式", DictRepository.EXCHANGE_LABELS["p"])
        assertEquals("过去分词", DictRepository.EXCHANGE_LABELS["d"])
        assertEquals("现在分词", DictRepository.EXCHANGE_LABELS["i"])
        assertEquals("第三人称单数", DictRepository.EXCHANGE_LABELS["3"])
        assertEquals("比较级", DictRepository.EXCHANGE_LABELS["r"])
        assertEquals("最高级", DictRepository.EXCHANGE_LABELS["t"])
        assertEquals("复数", DictRepository.EXCHANGE_LABELS["s"])
    }
}
