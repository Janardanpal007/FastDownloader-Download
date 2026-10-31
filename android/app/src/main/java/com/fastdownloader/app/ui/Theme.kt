package com.fastdownloader.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fastdownloader.app.R
import androidx.compose.material3.Icon

/**
 * FastDownloader look, same as the desktop app and website: sharp edges, thin grid lines
 * between areas, monospace uppercase labels, bold display headings, solid primary buttons.
 */
@Immutable
data class Palette(
    val bg: Color, val panel: Color, val panel2: Color, val border: Color, val lineStrong: Color,
    val text: Color, val muted: Color, val solidBg: Color, val solidFg: Color, val accent: Color,
    val accentSoft: Color, val ok: Color, val warn: Color, val err: Color, val errSoft: Color, val barBg: Color,
)

val LightPalette = Palette(
    bg = Color(0xFFF6F7F9), panel = Color(0xFFFFFFFF), panel2 = Color(0xFFEEF0F3), border = Color(0xFFDCDFE5),
    lineStrong = Color(0xFFB9BEC8), text = Color(0xFF0E1116), muted = Color(0xFF626B7C), solidBg = Color(0xFF0E1116),
    solidFg = Color.White, accent = Color(0xFF1D7FE6), accentSoft = Color(0xFFE1EEFC), ok = Color(0xFF138A43),
    warn = Color(0xFFB86A04), err = Color(0xFFC62828), errSoft = Color(0xFFFBE4E4), barBg = Color(0xFFE3E6EB),
)

val DarkPalette = Palette(
    bg = Color(0xFF0B0D12), panel = Color(0xFF0F1218), panel2 = Color(0xFF161A22), border = Color(0x17FFFFFF),
    lineStrong = Color(0x33FFFFFF), text = Color(0xFFF3F5F9), muted = Color(0xFF8D95A7), solidBg = Color(0xFFF3F5F9),
    solidFg = Color(0xFF0B0D12), accent = Color(0xFF4AA8FF), accentSoft = Color(0x1F4AA8FF), ok = Color(0xFF34D399),
    warn = Color(0xFFFBBF24), err = Color(0xFFF87171), errSoft = Color(0x1AF87171), barBg = Color(0x14FFFFFF),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Display = FontFamily(variable(R.font.space_grotesk, 500), variable(R.font.space_grotesk, 700))
val Mono = FontFamily(variable(R.font.jetbrains_mono, 500), variable(R.font.jetbrains_mono, 700))
val Body = FontFamily(variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600))

object Type {
    val label = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.12.em)
    val small = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    val body = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val strong = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp)
    val title = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.02).em)
    val heading = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = (-0.01).em)
}

@Composable
fun FastTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val scheme: ColorScheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.solidBg, onPrimary = p.solidFg, secondary = p.accent, background = p.bg, onBackground = p.text,
        surface = p.panel, onSurface = p.text, surfaceVariant = p.panel2, onSurfaceVariant = p.muted,
        surfaceContainer = p.panel, surfaceContainerHigh = p.panel, surfaceContainerLow = p.panel,
        surfaceContainerHighest = p.panel2, outline = p.lineStrong, outlineVariant = p.border, error = p.err,
    )
    val sharp = RoundedCornerShape(0.dp)
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(sharp, sharp, sharp, sharp, sharp),
            typography = MaterialTheme.typography.copy(bodyLarge = Type.body, bodyMedium = Type.body, labelLarge = Type.label),
            content = content,
        )
    }
}

val colors @Composable get() = LocalPalette.current

// ---------- small building blocks used on every screen ----------

/** Monospace uppercase label, like the section labels on the website. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = colors.muted) =
    Text(text.uppercase(), style = Type.label, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

@Composable
fun VDivider(height: Dp) = Box(Modifier.width(1.dp).height(height).background(colors.border))

/** Solid primary button (black in light mode, white in dark mode). */
@Composable
fun SolidButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = colors
    Row(
        modifier
            .height(48.dp)
            .background(if (enabled) c.solidBg else c.panel2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) c.solidFg else c.muted, modifier = Modifier.size(18.dp))
            Box(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = Type.label.copy(fontSize = 12.sp), color = if (enabled) c.solidFg else c.muted)
    }
}

/** Outlined secondary button. */
@Composable
fun LineButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = colors.text, onClick: () -> Unit) {
    val c = colors
    Box(
        modifier
            .height(40.dp)
            .border(BorderStroke(1.dp, c.lineStrong))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = Type.label, color = if (enabled) color else c.muted)
    }
}

/** Square icon button with a thin outline. */
@Composable
fun IconBox(icon: ImageVector, description: String, modifier: Modifier = Modifier, tint: Color = colors.text, onClick: () -> Unit) {
    Box(
        modifier.size(40.dp).border(1.dp, colors.border).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(18.dp)) }
}

/** Selectable chip: outlined, filled when selected. */
@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = colors
    Box(
        modifier
            .height(34.dp)
            .background(if (selected) c.solidBg else Color.Transparent)
            .border(1.dp, if (selected) c.solidBg else c.border)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), style = Type.label, color = if (selected) c.solidFg else c.text, maxLines = 1) }
}

/** A titled block with a mono label above its content. */
@Composable
fun Section(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Label(label, Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp))
        Column(Modifier.fillMaxWidth().border(1.dp, colors.border).background(colors.panel)) { content() }
    }
}

/**
 * Space for the navigation bar. Some phones (e.g. Realme's own swipe gestures) draw a gesture handle
 * but report no navigation bar, so the bottom gesture area counts too.
 */
@Composable
fun Modifier.bottomBarsPadding(): Modifier =
    windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom)))
