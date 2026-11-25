package com.ootruffle.modupdatechecker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Shape
import net.minecraft.client.gui.ScaledResolution
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ootruffle.modupdatechecker.Config
import com.ootruffle.modupdatechecker.ModStatus
import com.ootruffle.modupdatechecker.OneClient
import com.ootruffle.modupdatechecker.PrismIntegration
import com.ootruffle.modupdatechecker.ModStatus.State
import com.ootruffle.modupdatechecker.UpdateChecker
import com.ootruffle.modupdatechecker.install.Installer
import kotlinx.coroutines.delay
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import org.polyfrost.compose.minecraft.legacy.InputConstants
import org.polyfrost.compose.minecraft.screen.ComposeScreen
import org.polyfrost.compose.text.Font

/** Java entry point. */
object UpdatesScreen {
    @JvmStatic
    fun create(parent: GuiScreen?): GuiScreen = UpdatesComposeScreen(parent)
}

// ---- look (OneConfig's Polyfrosted Dark) ----

private val Inter by lazy {
    FontFamily(
        Font("assets/polycompose/fonts/inter/Inter-Regular.ttf", FontWeight.Normal),
        Font("assets/polycompose/fonts/inter/Inter-Medium.ttf", FontWeight.Medium),
        Font("assets/polycompose/fonts/inter/Inter-SemiBold.ttf", FontWeight.SemiBold),
        Font("assets/polycompose/fonts/inter/Inter-Bold.ttf", FontWeight.Bold),
    )
}

// The current theme's tokens (OneConfig's active theme and accent, or Polyfrosted Dark).
private val PageBackground: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.page
private val CardBackground: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.card
private val CardHover: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.cardHover
private val ChipBackground: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.chip
private val ChipHover: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.chipHover
private val Border: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.border
private val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.text
private val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.textSecondary
private val Accent: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.accent
private val AccentText: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.accentText
private val AccentLight: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.accentReadable
private val Green: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.green
private val Yellow: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.yellow
private val Red: Color @Composable @ReadOnlyComposable get() = LocalTheme.current.red

private val PanelShape: Shape @Composable @ReadOnlyComposable get() = LocalTheme.current.panelShape
private val CardShape: Shape @Composable @ReadOnlyComposable get() = LocalTheme.current.cardShape
private val ButtonShape: Shape @Composable @ReadOnlyComposable get() = LocalTheme.current.buttonShape

/** Screen pixels per dp for each step of Minecraft's GUI scale. */
private const val PX_PER_GUI_SCALE = 0.6f

private val PanelWidth = 820.dp
private val PanelHeight = 520.dp

private enum class Filter(val label: String) { UPDATES("Updates"), ALL("All"), UP_TO_DATE("Up to date"), NOT_FOUND("Not found") }

private fun Filter.matches(m: ModStatus): Boolean = when (this) {
    Filter.ALL -> true
    Filter.UPDATES -> m.state == State.UPDATE_AVAILABLE || m.state == State.DOWNLOADING || m.state == State.DOWNLOADED
    Filter.UP_TO_DATE -> m.state == State.UP_TO_DATE || m.state == State.NEWER
    Filter.NOT_FOUND -> m.state == State.NOT_FOUND || m.state == State.ERROR
}

@Composable
private fun Text(
    text: String,
    color: Color = TextPrimary,
    size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = Inter),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Button(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            !enabled -> ChipBackground.copy(alpha = 0.4f)
            primary && hovered -> Accent.copy(alpha = 0.85f)
            primary -> Accent
            hovered -> ChipHover
            else -> ChipBackground
        }
    )
    Box(
        Modifier.heightIn(min = 30.dp)
            .clip(ButtonShape)
            .background(background)
            .border(1.dp, Border, ButtonShape)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) (if (primary) AccentText else TextPrimary) else TextSecondary, size = 13.sp, weight = FontWeight.Medium)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (selected) Accent else if (hovered) Accent.copy(alpha = 0.35f) else ChipBackground)
    Box(
        Modifier.heightIn(min = 30.dp)
            .clip(ButtonShape)
            .background(background)
            .border(1.dp, Border, ButtonShape)
            .clickable(interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) AccentText else TextPrimary, size = 13.sp)
    }
}

@Composable
private fun Pill(label: String, color: Color) {
    Box(
        Modifier.clip(ButtonShape).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 4.dp),
    ) { Text(label, color = color, size = 12.sp, weight = FontWeight.Medium) }
}

/** Opens [url] in the system browser. */
private fun openUrl(url: String) {
    Thread({
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        } catch (e: Throwable) {
            val os = System.getProperty("os.name", "").lowercase()
            runCatching {
                when {
                    os.contains("win") -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
                    os.contains("mac") -> ProcessBuilder("open", url).start()
                    else -> ProcessBuilder("xdg-open", url).start()
                }
            }
        }
    }, "ModUpdateChecker-Browser").apply { isDaemon = true }.start()
}

/** The updates list, drawn with PolyCompose. */
class UpdatesComposeScreen(private val parent: GuiScreen?) : ComposeScreen(ComposeScreen.RenderMode.CONTINUOUS) {

    private var filter by mutableStateOf(if (UpdateChecker.updateCount() > 0 || UpdateChecker.running()) Filter.UPDATES else Filter.ALL)
    private val expanded = mutableStateMapOf<String, Boolean>()
    private var frame by mutableStateOf(0)

    private fun close() {
        Minecraft.getMinecraft().displayGuiScreen(parent)
    }

    override fun handleKeyPressed(key: Int, modifiers: Int): Boolean {
        if (key == InputConstants.KEY_ESCAPE) {
            close()
            return true
        }
        return false
    }

    override fun doesGuiPauseGame(): Boolean = true

    /** Blurs the world behind when OneConfig is installed and its background blur is on. */
    override fun blurAlpha(): Float =
        if (OneConfigPrefs.installed && OneConfigPrefs.bool("enableBackgroundBlur", true)) 1f else 0f

    private var theme by mutableStateOf(Themes.current())
    private var themeKey = Themes.key()

    @Composable
    override fun compose() {
        // The data lives in plain Java fields written by background threads: read it a few times a second.
        // Every row reads this state too, so rows redraw even though their ModStatus object stays the same.
        // OneConfig's theme is checked as well, so changing it applies while the screen is open.
        LaunchedEffect(Unit) {
            while (true) {
                delay(200)
                frame++
                val key = Themes.key()
                if (key != themeKey) {
                    themeKey = key
                    theme = Themes.current()
                }
            }
        }
        val tick = frame
        CompositionLocalProvider(LocalTheme provides theme) {
            val dim = if (blurAlpha() > 0f) Color(0x55000000) else Color(0x99000000)
            BoxWithConstraints(Modifier.fillMaxSize().background(dim), contentAlignment = Alignment.Center) {
                // Follow Minecraft's GUI scale (Auto included), shrinking only if the panel wouldn't fit.
                val mc = Minecraft.getMinecraft()
                val guiScale = ScaledResolution(mc).scaleFactor
                // Compose's pixels per real screen pixel (PolyCompose may render oversampled).
                val composePx = constraints.maxWidth.toFloat() / mc.displayWidth.coerceAtLeast(1)
                val wanted = guiScale * PX_PER_GUI_SCALE * composePx
                val fit = minOf(
                    constraints.maxWidth * 0.95f / PanelWidth.value,
                    constraints.maxHeight * 0.95f / PanelHeight.value,
                )
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(minOf(wanted, fit), density.fontScale)) {
                    Panel(tick)
                }
            }
        }
    }

    @Composable
    private fun Panel(tick: Int) {
        val mods = UpdateChecker.mods()
        val running = UpdateChecker.running()
        val counts = Filter.entries.associateWith { f -> mods.count { f.matches(it) } }
        val downloadable = mods.count { it.wantsUpdate() && it.canDownload() }
        val shown = mods.filter { filter.matches(it) }
            .sortedWith(compareBy<ModStatus> { if (it.wantsUpdate()) 0 else if (it.state == State.UPDATE_AVAILABLE) 1 else 2 }.thenBy { it.name.lowercase() })

        Column(
            Modifier.size(PanelWidth, PanelHeight)
                .clip(PanelShape)
                .background(PageBackground)
                .border(1.dp, Border, PanelShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Mod Updates", size = 22.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(UpdateChecker.summary(), color = TextSecondary, size = 13.sp)
                }
                Button(if (running) "Checking..." else "Check again", enabled = !running) {
                    UpdateChecker.clearCache()
                    UpdateChecker.checkAsync()
                }
                Button("Update all" + if (downloadable > 0) " ($downloadable)" else "", primary = true, enabled = downloadable > 0) {
                    Installer.downloadAll()
                }
                Button("Close") { close() }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in Filter.entries) {
                    Chip("${f.label} (${counts[f] ?: 0})", filter == f) { filter = f }
                }
            }

            if (OneClient.shouldNotify() || OneClient.removalRequested()) {
                OneClientBanner()
            } else if (PrismIntegration.offer() || PrismIntegration.requested()) {
                PrismBanner()
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                running && mods.isEmpty() -> "Looking at your mods..."
                                running -> "Still checking..."
                                filter == Filter.UPDATES -> "Everything is up to date."
                                else -> "Nothing here."
                            },
                            color = TextSecondary, size = 15.sp,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.jar.toString() }) { ModRow(it, tick) }
                    }
                }
            }

            Text(
                if (Installer.hasPending()) "Downloaded updates are installed when you close the game."
                else "Mods are matched on Modrinth by file hash; the rest are looked up in their GitHub releases.",
                color = if (Installer.hasPending()) Yellow else TextSecondary, size = 12.sp,
            )
        }
    }

    /** OneClient updates mods itself: suggests removing this mod (only shown under OneClient). */
    @Composable
    private fun OneClientBanner() {
        @Suppress("UNUSED_VARIABLE") val refresh = frame
        val removing = OneClient.removalRequested()
        Row(
            Modifier.fillMaxWidth()
                .clip(CardShape)
                .background(Yellow.copy(alpha = 0.10f))
                .border(1.dp, Yellow.copy(alpha = 0.45f), CardShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (removing) "Mod Update Checker will be removed when you close the game" else "OneClient already updates your mods",
                    size = 14.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (removing) "OneClient keeps your mods up to date from now on."
                    else "This mod isn't needed here. Remove it to avoid two updaters changing the same mods.",
                    color = TextSecondary, size = 12.sp, maxLines = 2,
                )
            }
            if (removing) {
                Button("Undo") { OneClient.undoRemoval() }
            } else {
                Button("Keep it") { OneClient.dismiss() }
                Button("Remove", primary = true) { OneClient.removeThisMod() }
            }
        }
    }

    /** The opt-in to add the pre-launch check to this Prism instance (only shown under Prism). */
    @Composable
    private fun PrismBanner() {
        @Suppress("UNUSED_VARIABLE") val refresh = frame
        val requested = PrismIntegration.requested()
        Row(
            Modifier.fillMaxWidth()
                .clip(CardShape)
                .background(Accent.copy(alpha = 0.12f))
                .border(1.dp, Accent.copy(alpha = 0.45f), CardShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (requested) "Prism will restart when you close the game" else "Check for updates before the game starts?",
                    size = 14.sp, weight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (requested) "Then this instance gets the pre-launch update window. Prism reopens by itself."
                    else "Adds the pre-launch update window to this Prism instance. Prism closes and reopens once, when you close the game.",
                    color = TextSecondary, size = 12.sp, maxLines = 2,
                )
            }
            if (requested) {
                Button("Cancel") { PrismIntegration.cancel() }
            } else {
                Button("Not now") { PrismIntegration.dismiss() }
                Button("Set up", primary = true) { PrismIntegration.request() }
            }
        }
    }

    @Composable
    private fun ModRow(mod: ModStatus, @Suppress("UNUSED_PARAMETER") tick: Int) {
        @Suppress("UNUSED_VARIABLE") val refresh = frame
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val open = expanded[mod.jar.toString()] == true
        val ignored = mod.ignored()
        Column(
            Modifier.fillMaxWidth()
                .clip(CardShape)
                .background(if (hovered) CardHover else CardBackground)
                .border(1.dp, Border, CardShape)
                .clickable(interaction, indication = null, enabled = mod.changelog != null) {
                    expanded[mod.jar.toString()] = !open
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(mod.name, size = 15.sp, weight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    val where = when (mod.source) {
                        ModStatus.Source.MODRINTH -> "Modrinth"
                        ModStatus.Source.GITHUB -> "GitHub · ${mod.githubRepo}"
                        ModStatus.Source.NONE -> mod.jar.fileName.toString()
                    }
                    Text(mod.error ?: where, color = if (mod.error != null) Red else TextSecondary, size = 12.sp)
                }
                Column(Modifier.widthIn(max = 170.dp), horizontalAlignment = Alignment.End) {
                    val latest = mod.latestVersion
                    if (latest != null && mod.state != State.UP_TO_DATE && mod.state != State.NEWER) {
                        Text(mod.version, color = TextSecondary, size = 12.sp)
                        Text("→ $latest", color = if (ignored) TextSecondary else Green, size = 13.sp, weight = FontWeight.Medium)
                    } else {
                        Text(mod.version, color = TextSecondary, size = 13.sp)
                    }
                }
                Box(Modifier.widthIn(min = 84.dp), contentAlignment = Alignment.Center) {
                    when (mod.state) {
                        State.CHECKING -> Pill("Checking", TextSecondary)
                        State.UP_TO_DATE -> Pill("Up to date", Green)
                        State.NEWER -> Pill("Newer than latest", Green)
                        State.UPDATE_AVAILABLE -> Pill(if (ignored) "Ignored" else "Update", if (ignored) TextSecondary else Yellow)
                        State.NOT_FOUND -> Pill("Not found", TextSecondary)
                        State.ERROR -> Pill("Error", Red)
                        State.DOWNLOADING -> Pill("${(mod.progress * 100).toInt()}%", AccentLight)
                        State.DOWNLOADED -> Pill("Restart to install", AccentLight)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (mod.changelog != null) {
                        Button(if (open) "Hide" else "Changes") { expanded[mod.jar.toString()] = !open }
                    }
                    mod.pageUrl?.let { url -> Button("Open") { openUrl(url) } }
                    if (mod.state == State.UPDATE_AVAILABLE) {
                        Button(if (ignored) "Unignore" else "Ignore") {
                            val config = Config.get()
                            if (ignored) config.ignoredVersions.remove(mod.id) else config.ignoredVersions[mod.id] = mod.latestVersion
                            Config.save()
                        }
                        if (mod.canDownload() && !ignored) {
                            Button("Update", primary = true) { Installer.downloadAsync(mod) }
                        }
                    }
                }
            }
            if (open && mod.changelog != null) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().clip(ButtonShape).background(if (LocalTheme.current.light) Color(0x14000000) else Color(0x40000000)).padding(12.dp)) {
                    Text(mod.changelog!!, color = TextPrimary, size = 12.sp, maxLines = 30)
                }
            }
        }
    }
}
