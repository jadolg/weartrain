package dev.akiel.weartrain

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val Ok = Color(0xFF4ADE80)
private val Warn = Color(0xFFF59E0B)
private val Bad = Color(0xFFF87171)
private val Soft = Color(0xFF9CA3AF)
private val S3 = Color(0xFFA855C7)
private val Alt = Color(0xFFD97706)

fun State.color() = when (this) {
    State.OK -> Ok
    State.WARN -> Warn
    State.BAD -> Bad
    State.UNKNOWN -> Soft
}

data class Board(
    val list: List<Itinerary> = emptyList(),
    val notes: List<String> = emptyList(),
    val assessment: Assessment? = null,
    val updated: String = "",
    val fetchedAt: Long = 0,
)

class MainActivity : ComponentActivity() {
    private val boards = mutableStateMapOf<Direction, Board>()

    private fun load(dir: Direction, force: Boolean) {
        val prev = boards[dir] ?: Board()
        val now = System.currentTimeMillis()
        if (!force && now - prev.fetchedAt < MIN_REFRESH_SECONDS * 1000) return
        boards[dir] = prev.copy(fetchedAt = now)
        lifecycleScope.launch {
            boards[dir] = try {
                val list = withContext(Dispatchers.IO) { plan(dir) }
                val notes = collectNotes(list)
                if (dir == Direction.forNow()) refreshGlance()
                Board(list, notes, assess(list, notes), "Live · " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), now)
            } catch (e: FeedException) {
                prev.copy(assessment = Assessment(State.UNKNOWN, e.verdict, e.message ?: ""), updated = "Last attempt failed", fetchedAt = now)
            }
        }
    }

    // The tile and complication pick up the list just fetched from the shared cache.
    private fun refreshGlance() {
        TileService.getUpdater(this).requestUpdate(TrainTile::class.java)
        ComplicationDataSourceUpdateRequester.create(this, ComponentName(this, TrainComplication::class.java)).requestUpdateAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppScaffold {
                    val nav = rememberSwipeDismissableNavController()
                    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            Home(boards, ::load) { dir, i -> nav.navigate("route/${dir.name}/$i") }
                        }
                        composable("route/{dir}/{i}") { entry ->
                            val dir = Direction.valueOf(entry.arguments?.getString("dir")!!)
                            val i = entry.arguments?.getString("i")!!.toInt()
                            boards[dir]?.let { b -> b.list.getOrNull(i)?.let { Route(it, b.notes) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Home(boards: Map<Direction, Board>, load: (Direction, Boolean) -> Unit, open: (Direction, Int) -> Unit) {
    val pager = rememberPagerState(initialPage = Direction.forNow().ordinal) { Direction.entries.size }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pager.currentPage) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                load(Direction.entries[pager.currentPage], false)
                delay(AUTO_REFRESH_SECONDS * 1000)
            }
        }
    }
    HorizontalPagerScaffold(pagerState = pager) {
        HorizontalPager(state = pager) { page ->
            val dir = Direction.entries[page]
            BoardPage(dir, boards[dir], { load(dir, true) }) { open(dir, it) }
        }
    }
}

@Composable
fun BoardPage(dir: Direction, board: Board?, refresh: () -> Unit, open: (Int) -> Unit) {
    val listState = rememberScalingLazyListState()
    // Re-evaluate exactly when the next train counts as gone, not when the next fetch lands.
    val now by produceState(Instant.now(), board) {
        while (true) {
            val next = board?.list?.mapNotNull { it.goneAt }?.filter { it.isAfter(value) }?.minOrNull() ?: break
            delay(Duration.between(Instant.now(), next).toMillis().coerceAtLeast(0) + 50)
            value = Instant.now()
        }
    }
    val all = board?.list.orEmpty()
    val list = stillToCome(all, now)
    val a = if (board == null || board.assessment?.state == State.UNKNOWN || list.isEmpty()) board?.assessment else assess(list, board.notes)
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { Eyebrow(dir.title) }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        a?.verdict ?: "Loading…",
                        color = (a?.state ?: State.UNKNOWN).color(),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                    )
                    if (a != null) Text(a.sub, color = Soft, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
            board?.notes?.take(2)?.forEach { note ->
                item { Text(note, color = Bad, fontSize = 12.sp, maxLines = 3, textAlign = TextAlign.Center) }
            }
            val picked = recommendedIndex(list)
            itemsIndexed(list) { i, it -> Departure(it, recommended = i == picked) { open(all.indexOf(it)) } }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(board?.updated.orEmpty(), color = Soft, fontSize = 11.sp)
                    Button(onClick = refresh) { Text("Refresh") }
                }
            }
        }
    }
}

@Composable
fun Eyebrow(text: String, tag: String? = null) {
    Text(
        text.uppercase() + (tag?.let { " — ${it.uppercase()}" } ?: ""),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = if (tag != null) Bad else Soft,
        textAlign = TextAlign.Center,
    )
}

@Composable
fun Departure(it: Itinerary, recommended: Boolean, onClick: () -> Unit) {
    val badge = when {
        it.cancelled -> "cancelled"
        it.delayMin > 0 -> "+${it.delayMin}′"
        it.delayMin < 0 -> "${it.delayMin}′"
        else -> "on time"
    }
    val badgeColor = when {
        it.cancelled || it.delayMin >= BAD_DELAY_MIN -> Bad
        it.delayMin > 0 -> Warn
        it.delayMin < 0 -> Ok
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (recommended) Modifier.border(2.dp, Ok, CardDefaults.shape) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(it.start), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Text(badge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = badgeColor)
        }
        Text("arrive ${fmt(it.end)} · ${it.durationMin} min", fontSize = 13.sp)
        Text(
            shapeOf(it),
            fontSize = 12.sp,
            color = if (deviation(it) != null) Bad else Soft,
            fontWeight = if (deviation(it) != null) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
fun Route(it: Itinerary, notes: List<String>) {
    val listState = rememberScalingLazyListState()
    val blob = notes.joinToString(" ").lowercase().replace(Regex("\\s+"), "")
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { Eyebrow("${fmt(it.start)} route", deviation(it)) }
            it.legs.forEach { leg -> item { LegView(leg, blob) } }
        }
    }
}

@Composable
fun LegView(leg: Leg, blob: String) {
    val color = when {
        !leg.transit -> Soft
        leg.expected -> S3
        else -> Alt
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                leg.line,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.background(color, RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
            if (leg.headsign.isNotEmpty()) Text("  → ${leg.headsign}", color = Soft, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        leg.stops.forEachIndexed { i, s ->
            val end = i == 0 || i == leg.stops.lastIndex
            val hit = s.cancelled || (blob.isNotEmpty() && blob.contains(s.name.lowercase().replace(Regex("\\s+"), "")))
            StopRow(s, color, first = i == 0, last = i == leg.stops.lastIndex, end = end, hit = hit, dashed = !leg.transit)
        }
    }
}

@Composable
fun StopRow(s: Stop, color: Color, first: Boolean, last: Boolean, end: Boolean, hit: Boolean, dashed: Boolean) {
    val dot = if (hit) Bad else color
    Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(18.dp).fillMaxHeight()) {
            val x = size.width / 2
            val y = size.height / 2
            val r = 5.dp.toPx()
            val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null
            drawLine(color, Offset(x, if (first) y else 0f), Offset(x, if (last) y else size.height), 4.dp.toPx(), pathEffect = dash)
            if (end || hit) drawCircle(dot, r + 1.5.dp.toPx(), Offset(x, y))
            else {
                drawCircle(Color.Black, r, Offset(x, y))
                drawCircle(dot, r, Offset(x, y), style = Stroke(3.dp.toPx()))
            }
        }
        Text(
            s.name,
            color = if (hit) Bad else if (end) Color.White else Soft,
            fontWeight = if (end || hit) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 4.dp, top = 3.dp, bottom = 3.dp),
        )
        if (s.time != null) Text(fmt(s.time), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Soft)
    }
}
