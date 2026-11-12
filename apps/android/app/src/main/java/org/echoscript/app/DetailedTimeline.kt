package org.echoscript.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Composable
internal fun DetailedTimeline(detail: JSONObject, positionMs: Long, startMs: Long?, endMs: Long?,
    selectBand: Boolean, ready: Boolean, seek: (Long) -> Unit, selectTime: (Long, Long) -> Unit,
    selectFrequencyBand: (Double, Double) -> Unit) {
    val settings = detail.getJSONObject("settings")
    val rate = settings.getInt("sampleRate")
    val hop = settings.getInt("hopSize")
    val lowHz = detail.getDouble("lowHz"); val highHz = detail.getDouble("highHz")
    val scale = settings.getString("scale")
    val fromFrame = detail.getLong("startFrame"); val toFrame = detail.getLong("endFrame")
    val span = (toFrame - fromFrame).toDouble().coerceAtLeast(1.0)
    val firstCenter = detail.getDouble("firstCenterFrame")
    val floorDb = settings.getInt("floorDb").toDouble()
    val color = MaterialTheme.colorScheme.primary
    val data = remember(detail) {
        val peaks = detail.getJSONArray("peaks")
        val waveform = FloatArray(peaks.length()) { peaks.getDouble(it).toFloat() }
        val columns = detail.getJSONArray("columns")
        val step = max(1, ceil(columns.length() / 256.0).toInt())
        val grid = (0 until columns.length() step step).map { x ->
            FloatArray(64) { y ->
                var strongest = floorDb
                for (col in x until min(x + step, columns.length())) {
                    val values = columns.getJSONArray(col)
                    for (bin in y * 2 until y * 2 + 2) strongest = max(strongest, values.getDouble(bin))
                }
                ((strongest - floorDb) / -floorDb).toFloat().coerceIn(0f, 1f)
            }
        }
        Triple(waveform, grid, step)
    }
    var originX by remember(detail) { mutableFloatStateOf(0f) }
    var originY by remember(detail) { mutableFloatStateOf(0f) }
    var lastY by remember(detail) { mutableFloatStateOf(0f) }
    val seekNow by rememberUpdatedState(seek)
    val selectTimeNow by rememberUpdatedState(selectTime)
    val selectFrequencyNow by rememberUpdatedState(selectFrequencyBand)
    Canvas(Modifier.fillMaxWidth().height(220.dp).testTag("detailed-spectrum")
        .semantics { contentDescription = "Detailed waveform and spectrum; precise time and frequency fields are available below" }
        .pointerInput(detail, ready) {
            detectTapGestures { point ->
                if (ready) {
                    val frame = fromFrame + (point.x / size.width).coerceIn(0f, 1f) * span
                    seekNow((frame * 1000 / rate).toLong())
                }
            }
        }
        .pointerInput(detail, ready, selectBand) {
            detectDragGestures(orientationLock = null,
                onDragStart = { down, _, _ -> originX = down.position.x; originY = down.position.y; lastY = down.position.y },
                onDrag = { change, _ ->
                    if (ready) {
                        change.consume(); lastY = change.position.y
                        val a = (fromFrame + min(originX, change.position.x).div(size.width).coerceIn(0f, 1f) * span) * 1000 / rate
                        val b = (fromFrame + max(originX, change.position.x).div(size.width).coerceIn(0f, 1f) * span) * 1000 / rate
                        selectTimeNow(a.toLong(), b.toLong())
                    }
                }, onDragEnd = { _ ->
                    val waveHeight = size.height * .35f
                    if (ready && selectBand && originY >= waveHeight && lastY >= waveHeight && kotlin.math.abs(originY - lastY) >= 6) {
                        fun frequency(y: Float): Double {
                            val fraction = ((size.height - y) / (size.height - waveHeight)).coerceIn(0f, 1f).toDouble()
                            return if (scale == "log") lowHz * (highHz / lowHz).pow(fraction) else highHz * fraction
                        }
                        val a = frequency(originY); val b = frequency(lastY)
                        val bandLow = max(20.0, min(a, b))
                        val bandHigh = min(20000.0, min(highHz - .001, max(a, b)))
                        if (bandHigh > bandLow) selectFrequencyNow(bandLow, bandHigh)
                    }
                })
        }) {
        val waveHeight = size.height * .35f
        drawRect(Color(0xFF13243A), Offset(0f, waveHeight), Size(size.width, size.height - waveHeight))
        for (i in data.first.indices) {
            val x = size.width * i / data.first.size; val y = waveHeight * data.first[i] / 2
            drawLine(color, Offset(x, waveHeight / 2 - y), Offset(x, waveHeight / 2 + y))
        }
        val h = (size.height - waveHeight) / 64
        for ((column, values) in data.second.withIndex()) {
            val first = column * data.third
            val last = min(first + data.third, detail.getJSONArray("columns").length())
            val leftFrame = firstCenter + first.toDouble() * hop - hop / 2.0
            val rightFrame = firstCenter + (last - 1).toDouble() * hop + hop / 2.0
            val left = ((leftFrame - fromFrame) / span * size.width).toFloat().coerceIn(0f, size.width)
            val right = ((rightFrame - fromFrame) / span * size.width).toFloat().coerceIn(0f, size.width)
            for (y in values.indices) drawRect(lerp(Color(0xFF13243A), color, values[y]),
                Offset(left, size.height - h * (y + 1)), Size((right - left).coerceAtLeast(0f) + 1f, h + 1f))
        }
        if (startMs != null && endMs != null && endMs > startMs) {
            val a = ((startMs * rate / 1000.0 - fromFrame) / span * size.width).toFloat().coerceIn(0f, size.width)
            val b = ((endMs * rate / 1000.0 - fromFrame) / span * size.width).toFloat().coerceIn(0f, size.width)
            if (b > a) drawRect(color.copy(alpha = .2f), Offset(a, 0f), Size(b - a, size.height))
        }
        val cursor = ((positionMs * rate / 1000.0 - fromFrame) / span * size.width).toFloat()
        if (cursor in 0f..size.width) drawLine(Color.White, Offset(cursor, 0f), Offset(cursor, size.height), 2f)
    }
}
