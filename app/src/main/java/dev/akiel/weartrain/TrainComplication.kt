package dev.akiel.weartrain

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

class TrainComplication : SuspendingTimelineComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val now = Instant.now()
        val sample = Itinerary(
            now.plusSeconds(240), now.plusSeconds(1140), 15, 0,
            listOf(Leg(transit = true, line = "S3", stops = emptyList())),
        )
        return build(type, Direction.ToHome, sample, Assessment(State.OK, "Running", ""), now.minusSeconds(360), live = false)
    }

    // One timeline entry per recommended departure, so the face moves on to the
    // next train the moment one leaves without waiting for the next fetch.
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val type = request.complicationType
        val dir = Direction.forNow()
        val now = Instant.now()
        val list = try {
            withContext(Dispatchers.IO) { plan(dir, maxAgeSeconds = 60) }
        } catch (e: FeedException) {
            return noData(type, dir, e.verdict)?.let { ComplicationDataTimeline(it, emptyList()) }
        }
        val slots = upcoming(list, now)
        val entries = slots.mapNotNull { sl ->
            build(type, dir, sl.it, sl.assessment, sl.since)?.let { TimelineEntry(TimeInterval(sl.from, sl.until), it) }
        }
        // Shown only once every entry has expired, i.e. the fetched trains have all left.
        val default = noData(type, dir, if (slots.isEmpty()) "No route" else "Refreshing") ?: return null
        return ComplicationDataTimeline(default, entries)
    }

    private fun shortVerdict(v: String) = when (v) {
        "Route changed" -> "Changed"
        "Disrupted" -> "Disrupt"
        else -> v
    }

    private fun text(s: String) = PlainComplicationText.Builder(s).build()

    private fun tap() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun icon() = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_train_mono)).build()

    private fun build(type: ComplicationType, dir: Direction, it: Itinerary, a: Assessment, since: Instant, live: Boolean = true): ComplicationData? {
        val time = fmt(it.start)
        val title = if (a.state == State.OK) it.transitLegs.joinToString("+") { l -> l.line }.ifEmpty { "Walk" } else shortVerdict(a.verdict)
        val desc = text("${dir.label}: ${a.verdict}, next $time")
        val delay = it.delayMin.takeIf { d -> d != 0 }?.let { d -> "%+d min".format(d) } ?: "on time"
        // Arc spans the gap since the previous train and empties at departure.
        val total = Duration.between(since, it.start).seconds.toFloat().coerceAtLeast(60f)
        val secondsLeft = DynamicInstant.platformTimeWithSecondsPrecision()
            .durationUntil(DynamicInstant.withSecondsPrecision(it.start!!))
            .toIntSeconds().asFloat()
        val staticLeft = Duration.between(Instant.now(), it.start).seconds.toFloat().coerceIn(0f, total)
        val ramp = a.state.color().toArgb().let { c -> ColorRamp(intArrayOf(c, c), false) }
        return when (type) {
            ComplicationType.GOAL_PROGRESS -> {
                val builder = if (live) GoalProgressComplicationData.Builder(secondsLeft, staticLeft, total, desc)
                else GoalProgressComplicationData.Builder(staticLeft, total, desc)
                builder
                    .setText(text(time))
                    .setTitle(text(title))
                    .setMonochromaticImage(icon())
                    .setColorRamp(ramp)
                    .setTapAction(tap())
                    .build()
            }
            ComplicationType.RANGED_VALUE -> {
                val builder = if (live) RangedValueComplicationData.Builder(secondsLeft, staticLeft, 0f, total, desc)
                else RangedValueComplicationData.Builder(staticLeft, 0f, total, desc)
                builder
                    .setText(text(time))
                    .setTitle(text(title))
                    .setColorRamp(ramp)
                    .setTapAction(tap())
                    .build()
            }
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(time), desc)
                .setTitle(text(title))
                .setTapAction(tap())
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("$time  ${shapeOf(it)} · ${a.verdict} · $delay"), desc)
                .setTitle(text(dir.title))
                .setMonochromaticImage(icon())
                .setTapAction(tap())
                .build()
            else -> null
        }
    }

    private fun noData(type: ComplicationType, dir: Direction, why: String): ComplicationData? {
        val desc = text("${dir.label}: $why")
        return when (type) {
            ComplicationType.GOAL_PROGRESS -> GoalProgressComplicationData.Builder(0f, 1f, desc)
                .setText(text("--:--"))
                .setMonochromaticImage(icon())
                .setTapAction(tap())
                .build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(0f, 0f, 1f, desc)
                .setText(text("--:--"))
                .setTitle(text("No data"))
                .setTapAction(tap())
                .build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text("--:--"), desc)
                .setTitle(text("No data"))
                .setTapAction(tap())
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("--:--  $why"), desc)
                .setTitle(text(dir.title))
                .setMonochromaticImage(icon())
                .setTapAction(tap())
                .build()
            else -> null
        }
    }
}
