package io.embodit.spatialcalc.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/** v0.1.5 (QA5 q5-1): default template names = short job name + " MM-dd HH:mm", ≤ 24 chars, the date always kept. */
class DefaultNameTest {
    private val utc = TimeZone.getTimeZone("UTC")
    /** 2026-12-31 23:59 UTC: two-digit month, day, hour, minute (the longest stamp). */
    private val dec31 = 1798761540000L

    private fun strings(dir: String): Map<String, String> {
        val f = listOf(File("src/main/res/$dir/strings.xml"), File("app/src/main/res/$dir/strings.xml")).first { it.exists() }
        return Regex("""<string name="(tpl_short_[a-z_]+)">([^<]*)</string>""").findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun stampFormat() {
        assertEquals("12-31 23:59", DefaultName.stamp(dec31, utc))
        assertEquals("Tank 12-31 23:59", DefaultName.of("Tank", dec31, utc))
    }

    @Test fun everyJobInBothLanguagesFitsWithTheDate() {
        val jobs = listOf("tank", "tank_round", "tile", "paint", "fit", "rug", "free")
        for (dir in listOf("values", "values-zh")) {
            val s = strings(dir)
            for (j in jobs) {
                val short = s.getValue("tpl_short_$j")
                val name = DefaultName.of(short, dec31, utc)
                assertTrue("$dir/$j \"$name\" ≤ ${DefaultName.MAX}", name.length <= DefaultName.MAX)
                assertTrue("$dir/$j keeps the date", name.endsWith(" 12-31 23:59"))
                assertEquals("$dir/$j base is the short name untouched", short, DefaultName.split(name).first)
            }
            // the seven jobs give seven different names in both languages (zh keeps the job names, round tank = 圆缸多少升)
            assertEquals("$dir: seven different bases", 7, jobs.map { s.getValue("tpl_short_$it") }.toSet().size)
        }
        // English bases stay short enough for one line in the job page tile (~97 dp of labelMedium)
        strings("values").values.forEach { assertTrue("\"$it\" ≤ 10 chars", it.length <= 10) }
    }

    @Test fun templateFromTemplateDoesNotStackDatesAndLongBasesAreCapped() {
        assertEquals("Tank 12-31 23:59", DefaultName.of("Tank 11-12 20:42", dec31, utc))
        val long = DefaultName.of("How many liters in this tank", dec31, utc)
        assertEquals(DefaultName.MAX, long.length)
        assertTrue(long.endsWith(" 12-31 23:59"))
        assertEquals("12-31 23:59", DefaultName.of("", dec31, utc))
    }

    @Test fun splitPutsTheDateOnItsOwnLine() {
        assertEquals("Round tank" to "12-31 23:59", DefaultName.split("Round tank 12-31 23:59"))
        assertEquals("这块地要多大地毯" to "12-31 23:59", DefaultName.split("这块地要多大地毯 12-31 23:59"))
        assertEquals("Balcony planter soil" to null, DefaultName.split("Balcony planter soil"))
    }
}
