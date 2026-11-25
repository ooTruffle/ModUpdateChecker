package com.ootruffle.modupdatechecker.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import org.polyfrost.compose.render.PolyColor
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads OneConfig's UI preferences (theme, accent, opacity, blur) when OneConfig is installed.
 * Everything is looked up reflectively, so this mod never links against OneConfig: without it (or
 * if a field moves), each read returns its default.
 */
internal object OneConfigPrefs {
    private val holders = listOf(
        "org.polyfrost.oneconfig.internal.ThemeConfig",
        "org.polyfrost.oneconfig.internal.OneConfigConfig",
    )

    private val cache = ConcurrentHashMap<String, Optional<Field>>()

    val installed: Boolean by lazy {
        holders.any { runCatching { Class.forName(it) }.isSuccess }
    }

    private fun field(name: String): Field? =
        cache.computeIfAbsent(name) { Optional.ofNullable(lookup(it)) }.orElse(null)

    private fun lookup(name: String): Field? {
        for (holder in holders) {
            val cls = try { Class.forName(holder) } catch (_: Throwable) { continue }
            val f = try { cls.getField(name) } catch (_: Throwable) { continue }
            if (Modifier.isStatic(f.modifiers)) return f
        }
        return null
    }

    fun bool(name: String, default: Boolean): Boolean =
        try { field(name)?.getBoolean(null) ?: default } catch (_: Throwable) { default }

    fun float(name: String, default: Float): Float =
        try { field(name)?.getFloat(null) ?: default } catch (_: Throwable) { default }

    fun value(name: String): Any? =
        try { field(name)?.get(null) } catch (_: Throwable) { null }
}

/** The screen's colours and shapes, matching OneConfig's built-in themes. */
internal class Theme(
    val name: String,
    val light: Boolean,
    val page: Color,
    val card: Color,
    val cardHover: Color,
    val chip: Color,
    val chipHover: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentText: Color,
    val green: Color,
    val yellow: Color,
    val red: Color,
    val panelShape: Shape,
    val cardShape: Shape,
    val buttonShape: Shape,
) {
    /** The accent, lightened on dark themes so small text on a dark page stays readable. */
    val accentReadable: Color get() = if (light) accent else lerp(accent, Color.White, 0.45f)
}

internal object Themes {
    /** OneConfig's default accent. */
    private val DEFAULT_ACCENT = Color(0xFF2B4BFF)

    private fun rounded(r: Int) = RoundedCornerShape(r.dp)
    private val Square = RoundedCornerShape(0.dp)

    private fun dark(name: String, glass: Boolean, accent: Color, pageOpacity: Float) = Theme(
        name, light = false,
        page = if (glass) Color(0xFF11171C).copy(alpha = pageOpacity) else Color(0xFF11171C),
        card = Color(0x59232D32),
        cardHover = Color(0x80232D32),
        chip = Color(0xB2232D32),
        chipHover = Color(0xFF2E3A41),
        border = Color(0x1AFFFFFF),
        text = Color(0xFFD5DBFF),
        textSecondary = Color(0xFF8A8F9C),
        accent = accent,
        accentText = Color.White,
        green = Color(0xFF3DDC84),
        yellow = Color(0xFFFFC94A),
        red = Color(0xFFFF6B6B),
        panelShape = if (glass) rounded(20) else Square,
        cardShape = if (glass) rounded(12) else Square,
        buttonShape = if (glass) rounded(8) else Square,
    )

    private fun light(name: String, glass: Boolean, accent: Color, pageOpacity: Float) = Theme(
        name, light = true,
        page = if (glass) Color(0xFFD0D7F3).copy(alpha = pageOpacity) else Color(0xFFD0D7F3),
        card = Color(0x59EBF2FF),
        cardHover = Color(0x99EBF2FF),
        chip = Color(0xB2EBF2FF),
        chipHover = Color(0xFFEBF2FF),
        border = Color(0x4DA3A3A3),
        text = Color.Black,
        textSecondary = Color(0xFF4E5661),
        accent = accent,
        accentText = Color(0xFFF2F4FF),
        green = Color(0xFF14874A),
        yellow = Color(0xFF9A6A00),
        red = Color(0xFFC62828),
        panelShape = if (glass) rounded(20) else Square,
        cardShape = if (glass) rounded(12) else Square,
        buttonShape = if (glass) rounded(8) else Square,
    )

    /** OneConfig's accent (the start of its gradient, if it has one), or its default. */
    private fun accent(): Color {
        val color = OneConfigPrefs.value("accentColor") as? PolyColor ?: return DEFAULT_ACCENT
        return Color(color.argb).copy(alpha = 1f)
    }

    /** OneConfig's active theme, or Polyfrosted Dark without OneConfig. */
    fun current(): Theme {
        val accent = accent()
        // OneConfig's page transparency, kept readable (this screen is mostly text).
        val opacity = (OneConfigPrefs.float("pageOpacity", 94f) / 100f).coerceIn(0.85f, 1f)
        val name = OneConfigPrefs.value("activeTheme") as? String ?: "Polyfrosted Dark"
        val lightTheme = name.contains("light", true)
        val minecraft = name.contains("minecraft", true)
        return when {
            minecraft && lightTheme -> light("Minecraft Light", false, accent, opacity)
            minecraft -> dark("Minecraft Dark", false, accent, opacity)
            lightTheme -> light("Polyfrosted Light", true, accent, opacity)
            else -> dark("Polyfrosted Dark", true, accent, opacity)
        }
    }

    /** A key that changes whenever [current] would return something different. */
    fun key(): String =
        "${OneConfigPrefs.value("activeTheme")}|${(OneConfigPrefs.value("accentColor") as? PolyColor)?.argb}|${OneConfigPrefs.float("pageOpacity", 94f)}"
}

internal val LocalTheme = staticCompositionLocalOf { Themes.current() }
