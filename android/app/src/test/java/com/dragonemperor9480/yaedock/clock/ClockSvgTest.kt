package com.dragonemperor9480.yaedock.clock

import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSvgTest {
    private val evening = LocalDateTime.of(2026, 11, 6, 21, 7, 5)

    private fun values(twentyFourHour: Boolean, name: String = "Amrut", seconds: Boolean = false) =
        ClockSvg.values(evening, twentyFourHour, Locale.ENGLISH, name, "Good evening, $name", mapOf("accent" to "#5BE7C4"), seconds)

    @Test
    fun fillsTheTimeAsThePhoneIsSet() {
        val day = values(twentyFourHour = true)
        assertEquals("21:07", day.getValue("time"))
        assertEquals("21", day.getValue("hh"))
        assertEquals("", day.getValue("ampm"))
        val twelve = values(twentyFourHour = false)
        assertEquals("9:07", twelve.getValue("time"))
        assertEquals("09", twelve.getValue("hh"))
        assertEquals("9", twelve.getValue("h"))
        assertEquals("21", twelve.getValue("HH"))
        assertEquals("PM", twelve.getValue("ampm"))
        assertEquals("05", twelve.getValue("ss"))
    }

    @Test
    fun fillsTheDateAndColours() {
        val v = values(twentyFourHour = true)
        assertEquals("Friday", v.getValue("weekday"))
        assertEquals("FRI", v.getValue("WD"))
        assertEquals("NOVEMBER", v.getValue("MONTH"))
        assertEquals("6", v.getValue("day"))
        assertEquals("06", v.getValue("dd"))
        assertEquals("#5BE7C4", v.getValue("accent"))
    }

    @Test
    fun turnsTheHands() {
        val v = values(twentyFourHour = true)
        // 9:07 on a 12-hour dial: 9 * 30 + 7 * 0.5.
        assertEquals("273.50", v.getValue("hour_angle"))
        assertEquals("42.00", v.getValue("minute_angle"))
        assertEquals("30.00", v.getValue("second_angle"))
        // The minute hand moves on each second only for clocks drawn every second.
        assertEquals("42.50", values(twentyFourHour = true, seconds = true).getValue("minute_angle"))
        // Second 5 is odd, so a blinking part is hidden.
        assertEquals("0", v.getValue("blink"))
    }

    @Test
    fun writesTheTimeInWords() {
        assertEquals("nine" to "oh seven", ClockSvg.timeWords(21, 7))
        assertEquals("twelve" to "o'clock", ClockSvg.timeWords(0, 0))
        assertEquals("ten" to "fifteen", ClockSvg.timeWords(10, 15))
        assertEquals("one" to "forty", ClockSvg.timeWords(13, 40))
        assertEquals("eleven" to "fifty-nine", ClockSvg.timeWords(23, 59))
    }

    @Test
    fun escapesTextAndKeepsUnknownPlaceholders() {
        val v = values(twentyFourHour = true, name = "Tom & <Jerry>")
        assertEquals("Tom &amp; &lt;Jerry&gt;", v.getValue("name"))
        assertEquals("<text>21 {nope}</text>", ClockSvg.fill("<text>{HH} {nope}</text>", v))
    }

    private fun clock(body: String, attributes: String = """yd:id="mine" yd:name="Mine"""") =
        """<svg xmlns="http://www.w3.org/2000/svg" xmlns:yd="https://yaedock.amrutlabs.in/clock" $attributes viewBox="0 0 400 200">$body</svg>"""

    private fun reason(text: String) = (ClockSvg.check(text, setOf("classic"), emptySet()) as ClockCheck.Invalid).reason

    @Test
    fun refusesWhatIsNotASafeClock() {
        assertTrue("scripts" in reason(clock("<text>{time}</text><script>alert(1)</script>")))
        assertTrue("DOCTYPE" in reason("<!DOCTYPE svg [<!ENTITY a \"b\">]>" + clock("<text>{time}</text>")))
        assertTrue("links" in reason(clock("""<text>{time}</text><image href="https://example.com/a.png"/>""")))
        assertTrue("links" in reason(clock("""<text>{time}</text><rect fill="url(file:///etc/passwd)"/>""")))
        assertTrue("never shows the time" in reason(clock("<text>{weekday}</text>")))
        assertTrue("yd:id" in reason(clock("<text>{time}</text>", """yd:name="Mine"""")))
        assertTrue("built-in" in reason(clock("<text>{time}</text>", """yd:id="classic" yd:name="Mine"""")))
        assertTrue("SVG" in reason("<html>{time}</html>"))
    }

    @Test
    fun acceptsLinksInsideTheFileAndWarns() {
        val check = ClockSvg.check(
            clock("""<defs><linearGradient id="g"/></defs><rect fill="url(#g)"/><text>{time} {soon}</text><filter id="f"/>"""),
            setOf("classic"),
            setOf("mine"),
        ) as ClockCheck.Valid
        assertTrue(check.replaces)
        assertEquals(2, check.warnings.size)
        assertFalse(check.design.ticksEverySecond)
    }

    @Test
    fun theExampleAndEveryBuiltInClockPass() {
        // The example's comment names refused things; comments are not checked.
        val example = ClockSvg.check(File("../../clocks/example.svg").readText(), setOf("classic"), emptySet())
        assertTrue("$example", example is ClockCheck.Valid && example.warnings.isEmpty())
        example as ClockCheck.Valid
        assertTrue(example.design.ticksEverySecond)
        val files = File("src/main/assets/clocks").listFiles().orEmpty().sorted()
        assertEquals(10, files.size)
        val ids = files.map { file ->
            val check = ClockSvg.check(file.readText(), setOf("classic"), emptySet())
            assertTrue("${file.name}: $check", check is ClockCheck.Valid && check.warnings.isEmpty())
            (check as ClockCheck.Valid).design.id
        }
        assertEquals(ids.size, ids.toSet().size)
    }
}
