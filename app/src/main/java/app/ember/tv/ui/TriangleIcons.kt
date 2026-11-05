package app.ember.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Solid triangles for Previous and Next. Text ◀ ▶ can render as coloured emoji on some TVs. */
internal object TriangleIcons {
    val Left: ImageVector by lazy { triangle(left = true) }
    val Right: ImageVector by lazy { triangle(left = false) }

    private fun triangle(left: Boolean): ImageVector = ImageVector.Builder(
        name = if (left) "TriangleLeft" else "TriangleRight",
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            if (left) { moveTo(16f, 5f); lineTo(16f, 19f); lineTo(6f, 12f) }
            else { moveTo(8f, 5f); lineTo(8f, 19f); lineTo(18f, 12f) }
            close()
        }
    }.build()
}
