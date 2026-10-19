package dev.akiel.weartrain

import androidx.compose.ui.graphics.toArgb
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicString
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.background
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.clip
import androidx.wear.protolayout.modifiers.padding
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.asLayoutConstraint
import androidx.wear.protolayout.types.asLayoutString
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

private const val RESOURCES = "1"
private const val Black = 0xFF000000.toInt()

class TrainTile : TileService() {

    override fun onTileRequest(req: RequestBuilders.TileRequest) = SuspendToFutureAdapter.launchFuture {
        val dir = Direction.forNow()
        val now = Instant.now()
        val timeline = TimelineBuilders.Timeline.Builder()
        try {
            val slots = upcoming(withContext(Dispatchers.IO) { plan(dir, maxAgeSeconds = 60) }, now)
            if (slots.isEmpty()) timeline.addTimelineEntry(entry(message(req, dir, State.BAD, "No route", "Nothing leaving soon")))
            slots.forEachIndexed { i, sl ->
                val later = slots.drop(i + 1).take(2).map { it.it }
                timeline.addTimelineEntry(entry(departure(req, dir, sl, later), sl.from, sl.until))
            }
            // Entry without validity: what the tile falls back to once all trains above have left.
            if (slots.isNotEmpty()) timeline.addTimelineEntry(entry(message(req, dir, State.UNKNOWN, "Refreshing", "Tap to reload")))
        } catch (e: FeedException) {
            timeline.addTimelineEntry(entry(message(req, dir, State.UNKNOWN, e.verdict, "Tap to retry")))
        }
        Tile.Builder()
            .setResourcesVersion(RESOURCES)
            .setTileTimeline(timeline.build())
            .setFreshnessIntervalMillis(5 * 60 * 1000)
            .build()
    }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest) =
        SuspendToFutureAdapter.launchFuture { Resources.Builder().setVersion(RESOURCES).build() }

    private fun entry(root: LayoutElement, from: Instant? = null, to: Instant? = null) =
        TimelineBuilders.TimelineEntry.Builder()
            .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(root).build())
            .apply {
                if (from != null && to != null) setValidity(
                    TimelineBuilders.TimeInterval.Builder().setStartMillis(from.toEpochMilli()).setEndMillis(to.toEpochMilli()).build()
                )
            }
            .build()

    private val openApp = clickable(
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName("dev.akiel.weartrain")
                    .setClassName(MainActivity::class.java.name)
                    .build()
            )
            .build()
    )

    private fun MaterialScope.title(dir: Direction, lines: String = "S3") =
        text("$lines · ${dir.label.substringAfter(" · ")}".layoutString, typography = Typography.LABEL_MEDIUM)

    private fun departure(req: RequestBuilders.TileRequest, dir: Direction, sl: Slot, later: List<Itinerary>) =
        materialScope(this, req.deviceConfiguration) {
            val it = sl.it
            val start = it.start!!
            val minsNow = Duration.between(Instant.now(), start).toMinutes() + 1
            val arrive = " · arr ${fmt(it.end)}"
            val countdown = DynamicString.constant("in ")
                .concat(
                    DynamicInstant.platformTimeWithSecondsPrecision()
                        .durationUntil(DynamicInstant.withSecondsPrecision(start))
                        .toIntSeconds().div(60).plus(1).format()
                )
                .concat(DynamicString.constant(" min$arrive"))
                .asLayoutString("in $minsNow min$arrive", "in 00 min$arrive".asLayoutConstraint())
            val status = sl.assessment.verdict + if (it.delayMin != 0) " · %+d′".format(it.delayMin) else ""
            primaryLayout(
                onClick = openApp,
                titleSlot = { title(dir, it.transitLegs.joinToString("+") { l -> l.line }.ifEmpty { "Walk" }) },
                mainSlot = {
                    LayoutElementBuilders.Column.Builder()
                        .setWidth(expand())
                        .addContent(
                            text(
                                status.layoutString,
                                typography = Typography.LABEL_MEDIUM,
                                color = Black.argb,
                                modifier = LayoutModifier.background(sl.assessment.state.color().toArgb().argb)
                                    .clip(shapes.full)
                                    .padding(horizontal = 12f, vertical = 3f),
                            )
                        )
                        .addContent(text(fmt(start).layoutString, typography = Typography.NUMERAL_LARGE))
                        .addContent(text(countdown, typography = Typography.BODY_MEDIUM, color = colorScheme.onSurfaceVariant))
                        .build()
                },
                bottomSlot = later.takeIf { l -> l.isNotEmpty() }?.let { l ->
                    {
                        text(
                            ("then " + l.joinToString(" · ") { t -> fmt(t.start) }).layoutString,
                            typography = Typography.BODY_SMALL,
                            color = colorScheme.onSurfaceVariant,
                            modifier = LayoutModifier.padding(bottom = 16f),
                        )
                    }
                },
            )
        }

    private fun message(req: RequestBuilders.TileRequest, dir: Direction, state: State, verdict: String, sub: String) =
        materialScope(this, req.deviceConfiguration) {
            primaryLayout(
                onClick = clickable(),
                titleSlot = { title(dir) },
                mainSlot = {
                    LayoutElementBuilders.Column.Builder()
                        .addContent(text(verdict.layoutString, typography = Typography.TITLE_LARGE, color = state.color().toArgb().argb))
                        .addContent(text(sub.layoutString, typography = Typography.BODY_SMALL, color = colorScheme.onSurfaceVariant))
                        .build()
                },
            )
        }
}
