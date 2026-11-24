package io.github.deskstride.wear

import android.net.Uri
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.common.util.concurrent.ListenableFuture
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

/** Today's totals published by the phone (WearBridge.publishToday in the phone app). */
data class Today(val steps: Int, val distanceM: Int, val durationS: Long, val running: Boolean) {
    fun km(locale: Locale = Locale.getDefault()): String = String.format(locale, "%.2f", distanceM / 1000.0)

    companion object {
        val EMPTY = Today(0, 0, 0, false)

        /** Data from another day (the phone hasn't published since yesterday): nothing yet today. */
        fun of(date: String?, steps: Int, distanceM: Int, durationS: Long, running: Boolean, today: LocalDate = LocalDate.now()) =
            if (date == today.toString()) Today(steps, distanceM, durationS, running) else EMPTY
    }
}

/** "DeskStride" Tile: today's km, steps and minutes, with a "Walk" button that opens the remote and connects the treadmill. */
class TodayTile : TileService() {

    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { done ->
            // no host: the latest DataItem from any node (the phone is the only one)
            val uri = Uri.Builder().scheme("wear").path(TODAY_PATH).build()
            Wearable.getDataClient(this).getDataItems(uri).addOnCompleteListener { task ->
                val today = runCatching {
                    val items = task.result
                    try {
                        items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap }?.let { m ->
                            Today.of(m.getString("date"), m.getInt("steps"), m.getInt("dist"), m.getLong("dur"), m.getBoolean("run"))
                        }
                    } finally { items.release() }
                }.getOrNull() ?: Today.EMPTY
                done.set(tile(today))
            }
            "today-tile"
        }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { done ->
            done.set(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())
            "today-res"
        }

    private fun tile(t: Today): TileBuilders.Tile {
        val button = LayoutElementBuilders.Box.Builder()
            .addContent(text(getString(if (t.running) R.string.tile_open else R.string.tile_walk), 15f, ON_ACCENT, bold = true))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(ACCENT))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build()).build(),
                    )
                    .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(18f)).setEnd(dp(18f)).setTop(dp(8f)).setBottom(dp(8f)).build())
                    // during a walk just open the app: the treadmill is already connected
                    .setClickable(open(connect = !t.running, id = "walk"))
                    .build(),
            )
            .build()
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(getString(if (t.running) R.string.tile_in_progress else R.string.tile_today), 14f, if (t.running) ACCENT else TEXT_DIM))
            .addContent(text("${t.km()} km", 30f, TEXT, bold = true))
            .addContent(text(getString(R.string.tile_summary, NumberFormat.getIntegerInstance().format(t.steps), t.durationS / 60), 14f, TEXT_DIM))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(button)
            .build()
        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .addContent(column)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open(connect = false, id = "open")).build())
            .build()
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VERSION)
            // the phone triggers an update on every change (TodayListener); this covers the day rollover
            .setFreshnessIntervalMillis(30 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build(),
            )
            .build()

    private fun open(connect: Boolean, id: String) = ModifiersBuilders.Clickable.Builder()
        .setId(id)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(MainActivity.EXTRA_CONNECT, ActionBuilders.AndroidBooleanExtra.Builder().setValue(connect).build())
                    .build(),
            ).build(),
        )
        .build()

    companion object {
        const val TODAY_PATH = "/deskstride/today"
        private const val RES_VERSION = "1"
        // same colors as res/values/colors.xml
        private const val TEXT = 0xFFF2F5F8.toInt()
        private const val TEXT_DIM = 0xFF8A94A3.toInt()
        private const val ACCENT = 0xFF00D69A.toInt()
        private const val ON_ACCENT = 0xFF00281C.toInt()
    }
}

/** The phone published new totals: ask the system to redraw the Tile. */
class TodayListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        TileService.getUpdater(this).requestUpdate(TodayTile::class.java)
    }
}
