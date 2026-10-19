package dev.akiel.weartrain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class AssessTest {
    private fun leg(line: String, vararg stops: Stop) = Leg(transit = true, line = line, stops = stops.toList())
    private fun trip(vararg legs: Leg, delay: Int = 0) = Itinerary(null, null, 15, delay, legs.toList())
    private val direct = trip(leg("S3", Stop("Jungfernstieg", null), Stop("Langenfelde", null)))
    private val change = trip(leg("S3", Stop("A", null)), leg("S5", Stop("B", null)))

    @Test
    fun cleanDirectRunWinsOverSlowerChange() {
        val list = listOf(change, direct)
        assertEquals(1, recommendedIndex(list))
        assertEquals(State.OK, assess(list).state)
        assertEquals("S3 + S5 · 1 change", shapeOf(change))
    }

    @Test
    fun grading() {
        assertEquals("Route changed", assess(listOf(change)).verdict)
        assertEquals("S5 covering this run instead of S3", deviation(trip(leg("S5", Stop("A", null)))))
        assertEquals("skipping B", deviation(trip(leg("S3", Stop("A", null), Stop("B", null, cancelled = true)))))
        assertEquals(State.WARN, assess(listOf(direct.copy(delayMin = 4))).state)
        val noted = direct.copy(legs = direct.legs.map { it.copy(alerts = listOf("works")) })
        assertEquals(State.WARN, assess(listOf(noted)).state)
        // Trouble on a non-recommended option doesn't grade the commute.
        assertEquals(State.OK, assess(listOf(direct, direct.copy(delayMin = 12), noted.copy(legs = change.legs))).state)
        assertEquals("Disrupted", assess(listOf(direct.copy(delayMin = 12))).verdict)
        assertEquals("No route", assess(emptyList()).verdict)
        assertEquals(null, trouble(direct))
        assertEquals(State.WARN, trouble(direct.copy(delayMin = 4))?.state)
        assertEquals(State.BAD, trouble(direct.copy(delayMin = 12))?.state)
    }

    @Test
    fun upcomingSkipsDepartedAndChangesThenAdvances() {
        val t0 = Instant.parse("2026-10-18T19:00:00Z")
        fun at(min: Long, it: Itinerary) = it.copy(start = t0.plusSeconds(min * 60))
        val gone = at(-1, direct)
        val a = at(3, change)
        val b = at(5, direct)
        val c = at(10, direct)
        val slots = upcoming(listOf(gone, a, b, c), t0)
        // The change at +3 is skipped while a direct one is coming; after b leaves only c remains.
        assertEquals(listOf(b, c), slots.map { it.it })
        // Switches a few seconds before departure so a once-a-minute redraw never shows a gone train.
        assertEquals(listOf(t0, b.start!!.minusSeconds(10)), slots.map { it.from })
        assertEquals(listOf(b.goneAt, c.goneAt), slots.map { it.until })
        assertEquals(listOf(c), stillToCome(listOf(b, c), b.start!!.minusSeconds(5)))
        // Arcs span the gap between recommended trains; the first one assumes the next gap's headway.
        assertEquals(listOf(b.start!!.minusSeconds(300), b.start), slots.map { it.since })
    }
}
