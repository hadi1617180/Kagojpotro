package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalGlassPalette

private const val FOLDER_SVG_MIN_X = 195f
private const val FOLDER_SVG_MIN_Y = 385f
private const val FOLDER_SVG_WIDTH = 1810f
private const val FOLDER_SVG_HEIGHT = 1430f
const val FOLDER_SVG_ASPECT_RATIO = FOLDER_SVG_WIDTH / FOLDER_SVG_HEIGHT

private const val PATH_BACK_TAB_CLS1 =
    "M1676.63,574.2h-591.5c-25.51,0-50.12-9.39-69.15-26.36l-141.77-126.48c-19.03-16.97-43.65-26.36-69.15-26.36H279.6c-43.03,0-77.9,34.87-77.9,77.9v1199.97c0,8.85,1.48,17.36,4.19,25.29l127.1,-463.41v-531.73c0-17.26,13.99-31.24,31.24-31.24h1246.04c17.26,0,31.24,13.99,31.24,31.24v4.93c15.73,1.62,28,14.91,28,31.07v14.64h85.01v-101.57c0-43.03-34.87-77.9-77.9-77.9Z"

private const val PATH_BACK_PAPER_CLS4 =
    "M411.68,947.86l-50.68,184.8v-393.63c0-17.26,13.99-31.24,31.24-31.24h1246.04c1.09,0,2.18,0.06,3.24,0.17v-4.93c0-17.26-13.99-31.24-31.24-31.24H364.24c-17.26,0-31.24,13.99-31.24,31.24v531.73l78.68-286.89Z"

private const val PATH_FRONT_PAPER_CLS2 =
    "M411.68,947.86c9.28-33.84,40.04-57.3,75.13-57.3h665.04c18.54,0,36.74-4.96,52.72-14.37l183.59-108.15c15.98-9.41,34.18-14.37,52.72-14.37h228.65v-14.64c0-17.26-13.99-31.24-31.24-31.24H392.24c-17.26,0-31.24,13.99-31.24,31.24v393.63l50.68-184.8Z"

private const val PATH_FRONT_FLAP_CLS3 =
    "M1995.41,852.43l-248.31,893.4c-9.72,34.97-41.56,59.17-77.86,59.17H282.56c-53.33,0-92.03-50.75-77.93-102.18l207.05-754.96c9.28-33.84,40.04-57.3,75.13-57.3h665.04c18.54,0,36.74-4.96,52.72-14.37l183.59-108.15c15.98-9.41,34.18-14.37,52.72-14.37h479.47c51.52,0,88.85,49.12,75.06,98.76Z"

/**
 * Renders the 4-layer `Folder_d5cf3bc3.svg` artwork dynamically shaded with the active theme's
 * accent color (`glass.accent`):
 * - `.cls-1` (back folder body & top-left tab): deeper accent shade
 * - `.cls-4` (back inner paper sheet): soft silver-white with subtle accent tint
 * - `.cls-2` (front inner paper sheet): crisp white paper
 * - `.cls-3` (angled front folder flap): vibrant accent gradient
 */
@Composable
fun AccentShadedFolderSvgGraphic(
    modifier: Modifier = Modifier,
    accentColor: Color = LocalGlassPalette.current.accent,
    highlightBorder: Boolean = false
) {
    val backTabPath = remember { PathParser().parsePathString(PATH_BACK_TAB_CLS1).toPath() }
    val backPaperPath = remember { PathParser().parsePathString(PATH_BACK_PAPER_CLS4).toPath() }
    val frontPaperPath = remember { PathParser().parsePathString(PATH_FRONT_PAPER_CLS2).toPath() }
    val frontFlapPath = remember { PathParser().parsePathString(PATH_FRONT_FLAP_CLS3).toPath() }

    // Shaded tones derived from the user's selected accent color
    val backTabColor = remember(accentColor) {
        lerp(accentColor, Color.Black, 0.28f)
    }
    val backPaperColor = remember(accentColor) {
        lerp(Color(0xFFE4E9F0), accentColor, 0.12f)
    }
    val frontPaperColor = Color(0xFFFFFFFF)
    val frontFlapTopColor = remember(accentColor) {
        lerp(accentColor, Color.White, 0.10f)
    }
    val frontFlapBottomColor = remember(accentColor) {
        lerp(accentColor, Color.Black, 0.12f)
    }
    val strokeHighlightColor = remember(accentColor) {
        lerp(accentColor, Color.White, 0.38f).copy(alpha = if (highlightBorder) 0.95f else 0.35f)
    }

    Canvas(modifier = modifier) {
        val scaleX = size.width / FOLDER_SVG_WIDTH
        val scaleY = size.height / FOLDER_SVG_HEIGHT

        withTransform({
            scale(scaleX = scaleX, scaleY = scaleY, pivot = Offset.Zero)
            translate(left = -FOLDER_SVG_MIN_X, top = -FOLDER_SVG_MIN_Y)
        }) {
            // 1. Back Folder Body & Top-Left Tab (.cls-1)
            drawPath(
                path = backTabPath,
                color = backTabColor
            )

            // 2. Back Inner Paper Sheet (.cls-4)
            drawPath(
                path = backPaperPath,
                color = backPaperColor
            )

            // 3. Front White Paper Sheet (.cls-2)
            drawPath(
                path = frontPaperPath,
                color = frontPaperColor
            )

            // 4. Angled Front Folder Flap (.cls-3) with smooth vertical accent shading
            drawPath(
                path = frontFlapPath,
                brush = Brush.verticalGradient(
                    colors = listOf(frontFlapTopColor, accentColor, frontFlapBottomColor),
                    startY = 750f,
                    endY = 1805f
                )
            )

            // Subtle crisp rim on the front flap
            drawPath(
                path = frontFlapPath,
                color = strokeHighlightColor,
                style = Stroke(width = if (highlightBorder) 26f else 12f)
            )
        }
    }
}

/**
 * Grid View Folder Item using `Folder_d5cf3bc3.svg` shaded with the active accent color,
 * placing the right-aligned Folder Name (larger bold title) and Last-Modified Date
 * (smaller compact subtitle) cleanly inside the open right-hand pocket of the front folder flap.
 */
@Composable
fun AccentShadedFolderGridContent(
    folderName: String,
    formattedDate: String,
    modifier: Modifier = Modifier,
    highlightBorder: Boolean = false
) {
    val glass = LocalGlassPalette.current
    val isLightFlap = glass.accent.red * 0.2126f + glass.accent.green * 0.7152f + glass.accent.blue * 0.0722f > 0.52f
    val onFlapColor = if (isLightFlap) Color(0xFF1C201E) else Color.White

    val textShadow = remember(isLightFlap) {
        Shadow(
            color = if (isLightFlap) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.42f),
            offset = Offset(0f, 1.5f),
            blurRadius = 3.5f
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(FOLDER_SVG_ASPECT_RATIO)
    ) {
        val topPocketPadding = maxHeight * 0.36f
        val bottomPocketPadding = maxHeight * 0.10f
        val startPocketPadding = maxWidth * 0.10f
        val endPocketPadding = maxWidth * 0.19f

        AccentShadedFolderSvgGraphic(
            accentColor = glass.accent,
            highlightBorder = highlightBorder,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = startPocketPadding,
                    end = endPocketPadding,
                    top = topPocketPadding,
                    bottom = bottomPocketPadding
                )
        ) {
            Text(
                text = folderName,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 14.5.sp,
                    lineHeight = 16.sp,
                    shadow = textShadow
                ),
                color = onFlapColor,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = formattedDate,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.5.sp,
                    lineHeight = 11.sp,
                    shadow = textShadow
                ),
                color = onFlapColor.copy(alpha = 0.85f),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
