package dev.akiel.weartrain

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class Direction(val label: String, val from: String, val to: String) {
    ToWork("S3 · to work", "Langenfelde", "Hamburg Jungfernstieg"),
    ToHome("S3 · to home", "Hamburg Jungfernstieg", "Langenfelde");

    val title get() = "${from.removePrefix("Hamburg ")} → ${to.removePrefix("Hamburg ")}"

    companion object {
        fun forNow() = if (LocalTime.now().hour >= SWITCH_HOUR) ToHome else ToWork
    }
}

const val SWITCH_HOUR = 12
// Which lines count as the normal commute. The S5 also calls at Langenfelde,
// but by day it leaves the corridor at Altona, so an S5 reaching
// Jungfernstieg is a diversion.
val EXPECTED_LINES = listOf("S3")
const val MAX_TRANSIT_LEGS = 1
const val ITINERARIES = 5
const val WARN_DELAY_MIN = 3
const val BAD_DELAY_MIN = 10
const val MIN_REFRESH_SECONDS = 45L
const val AUTO_REFRESH_SECONDS = 90L
private const val MOTIS = "https://api.transitous.org/api"

data class Stop(val name: String, val time: Instant?, val cancelled: Boolean = false)

data class Leg(
    val transit: Boolean,
    val line: String,
    val headsign: String = "",
    val stops: List<Stop>,
    val alerts: List<String> = emptyList(),
    val cancelled: Boolean = false,
) {
    val expected get() = transit && EXPECTED_LINES.any { norm(it) == norm(line) }
}

data class Itinerary(
    val start: Instant?,
    val end: Instant?,
    val durationMin: Int,
    val delayMin: Int,
    val legs: List<Leg>,
) {
    val cancelled get() = legs.any { it.cancelled }
    val transitLegs get() = legs.filter { it.transit }
}

enum class State { OK, WARN, BAD, UNKNOWN }

data class Assessment(val state: State, val verdict: String, val sub: String)

private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), "")

private fun changes(n: Int) = "$n change${if (n > 1) "s" else ""}"

fun shapeOf(it: Itinerary): String {
    val t = it.transitLegs
    if (t.isEmpty()) return "walk only"
    return t.joinToString(" + ") { l -> l.line } + if (t.size > 1) " · ${changes(t.size - 1)}" else ""
}

// Only facts the feed states. No comparison against a guessed "normal".
fun deviation(it: Itinerary): String? {
    val t = it.transitLegs
    if (t.isEmpty()) return "no train at all"
    val skipped = t.flatMap { l -> l.stops.filter { s -> s.cancelled }.map { s -> s.name } }
    if (skipped.isNotEmpty()) return "skipping ${skipped.take(3).joinToString(", ")}"
    if (t.size > MAX_TRANSIT_LEGS) return "needs ${changes(t.size - 1)}"
    // Phrased from the train's side: an S5 working a run you'd normally take an S3 for.
    if (!t[0].expected) return "${t[0].line} covering this run instead of ${EXPECTED_LINES.joinToString(" or ")}"
    return null
}

fun collectNotes(list: List<Itinerary>) = list.flatMap { it.legs }.flatMap { it.alerts }.filter { it.isNotBlank() }.distinct()

fun recommendedIndex(list: List<Itinerary>) = list.indexOfFirst { !it.cancelled && deviation(it) == null }.coerceAtLeast(0)

fun assess(list: List<Itinerary>, notes: List<String>): Assessment {
    if (list.isEmpty()) return Assessment(State.BAD, "No route", "The planner cannot get you there at all right now.")
    // The planner returns slower multi-leg options alongside good ones; one clean
    // direct run means the commute isn't broken.
    val best = list[recommendedIndex(list)]
    val dev = deviation(best)
    val worst = maxOf(0, list.maxOf { it.delayMin })
    val cancelled = list.count { it.cancelled }

    if (dev != null) return Assessment(State.BAD, "Route changed", dev.replaceFirstChar { it.uppercase() } + ".")
    if (cancelled >= 2 || worst >= BAD_DELAY_MIN) {
        val bits = listOfNotNull(
            if (cancelled > 0) "$cancelled of ${list.size} cancelled" else null,
            if (worst > 0) "up to +$worst min" else null,
        )
        return Assessment(State.BAD, "Disrupted", bits.joinToString(" · "))
    }
    if (cancelled == 1 || worst >= WARN_DELAY_MIN || notes.isNotEmpty()) {
        val bits = listOfNotNull(
            if (cancelled > 0) "1 option cancelled" else null,
            if (worst >= WARN_DELAY_MIN) "up to +$worst min" else null,
        ).ifEmpty { listOf("Service message posted") }
        return Assessment(State.WARN, "Wobbly", bits.joinToString(" · "))
    }
    val via = best.transitLegs.firstOrNull()?.line ?: "train"
    return Assessment(State.OK, "Running", "Direct $via, ${best.durationMin} min${if (worst > 0) ", +$worst min" else ""}")
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
fun fmt(t: Instant?): String = t?.let(hhmm::format) ?: "--:--"

// ── Transitous (MOTIS) ─────────────────────────────────────

class FeedException(message: String, val verdict: String) : Exception(message)

private fun getJSON(url: String): String {
    val conn = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "weartrain/1.0 (train.akiel.dev)")
        }.also { it.connect() }
    } catch (e: IOException) {
        throw FeedException("The request never reached the server — API down or no network.", "Feed unreachable")
    }
    try {
        val code = conn.responseCode
        if (code !in 200..299) {
            val hint = if (code == 429) " Rate limited." else ""
            throw FeedException("Server responded $code.$hint", "Feed refused")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        throw FeedException("Connection dropped while reading the feed.", "Feed unreachable")
    } finally {
        conn.disconnect()
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private val stopIds = mutableMapOf<String, String>()

private fun stopId(name: String): String = stopIds.getOrPut(name) {
    val hits = JSONArray(getJSON("$MOTIS/v1/geocode?text=${enc(name)}"))
    val all = (0 until hits.length()).map { hits.getJSONObject(it) }
    val stop = all.firstOrNull { it.optString("type") == "STOP" } ?: all.firstOrNull()
        ?: throw FeedException("No stop matched \"$name\".", "No data")
    stop.getString("id")
}

private fun JSONObject.str(vararg keys: String) =
    keys.firstNotNullOfOrNull { k -> optString(k).takeIf { has(k) && !isNull(k) && it.isNotEmpty() } }

private fun JSONObject.time(vararg keys: String) = str(*keys)?.let(Instant::parse)

private fun JSONObject.objects(key: String) =
    optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

private fun mins(a: Instant?, b: Instant?) = if (a != null && b != null) Duration.between(b, a).toMinutes().toInt() else 0

private val cache = mutableMapOf<Direction, Pair<Long, List<Itinerary>>>()

// Shared by the app, tile and complication (same process), so a fetch by one
// is reused by the others instead of hitting the API again.
fun plan(dir: Direction, maxAgeSeconds: Long = 0): List<Itinerary> {
    val now = System.currentTimeMillis()
    synchronized(cache) { cache[dir]?.takeIf { now - it.first <= maxAgeSeconds * 1000 }?.let { return it.second } }
    return fetchPlan(dir).also { synchronized(cache) { cache[dir] = now to it } }
}

// MOTIS misparses nanosecond timestamps and returns trips from days ago.
private fun fetchPlan(dir: Direction, now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)): List<Itinerary> {
    val from = stopId(dir.from)
    val to = stopId(dir.to)
    val body = JSONObject(
        getJSON("$MOTIS/v1/plan?fromPlace=${enc(from)}&toPlace=${enc(to)}&time=${enc(now.toString())}&arriveBy=false&numItineraries=$ITINERARIES")
    )
    return body.objects("itineraries").map { it ->
        val rawLegs = it.objects("legs")
        val legs = rawLegs.map { l ->
            val transit = l.has("tripId") || l.str("routeShortName") != null || l.optString("mode", "WALK") != "WALK"
            val stops = (listOf(l.getJSONObject("from")) + l.objects("intermediateStops") + l.getJSONObject("to")).map { s ->
                Stop(
                    name = s.str("name") ?: "—",
                    time = s.time("departure", "arrival", "scheduledDeparture", "scheduledArrival"),
                    cancelled = s.optBoolean("cancelled"),
                )
            }
            Leg(
                transit = transit,
                line = l.str("routeShortName", "routeLongName") ?: if (transit) l.optString("mode") else "Walk",
                headsign = l.optString("headsign"),
                stops = stops,
                alerts = l.objects("alerts").map { a ->
                    listOfNotNull(a.str("headerText", "header"), a.str("descriptionText", "description")).joinToString(" — ")
                },
                cancelled = l.optBoolean("cancelled"),
            )
        }
        val start = it.time("startTime")
        val end = it.time("endTime")
        // The itinerary carries no scheduled start; the first leg does.
        val planned = rawLegs.firstOrNull()?.time("scheduledStartTime") ?: start
        Itinerary(
            start = start,
            end = end,
            durationMin = if (it.has("duration")) Math.round(it.getDouble("duration") / 60).toInt() else mins(end, start),
            delayMin = mins(start, planned),
            legs = legs,
        )
    }
}

// Watch faces and tiles redraw once a minute, at about :00. Switching exactly at
// departure can land a hair after that redraw and leave a departed train up for
// a whole extra minute, so a train counts as gone a few seconds early.
private const val GONE_EARLY_SECONDS = 10L

val Itinerary.goneAt: Instant? get() = start?.minusSeconds(GONE_EARLY_SECONDS)

fun stillToCome(list: List<Itinerary>, now: Instant) = list.filter { it.goneAt?.isAfter(now) == true }

// What to show from `from` until `until`, when `it` counts as gone. `since` is when
// the previous recommended train left, so progress arcs span the real gap between trains.
data class Slot(val from: Instant, val until: Instant, val it: Itinerary, val assessment: Assessment, val since: Instant)

fun upcoming(list: List<Itinerary>, now: Instant): List<Slot> {
    val notes = collectNotes(list)
    val slots = mutableListOf<Slot>()
    var from = now
    var left = stillToCome(list, now)
    while (left.isNotEmpty()) {
        val next = left[recommendedIndex(left)]
        val until = next.goneAt!!
        slots += Slot(from, until, next, assess(left, notes), slots.lastOrNull()?.it?.start ?: from)
        from = until
        left = stillToCome(left, until)
    }
    // The train before the first one isn't in the feed; assume the same headway as the next gap.
    val first = slots.firstOrNull() ?: return slots
    val headway = slots.getOrNull(1)?.let { Duration.between(first.it.start, it.it.start) } ?: Duration.ofMinutes(10)
    slots[0] = first.copy(since = first.it.start!!.minus(headway))
    return slots
}
