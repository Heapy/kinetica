package io.heapy.kinetica.theme

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Context
import io.heapy.kinetica.LayoutDirection
import io.heapy.kinetica.context
import io.heapy.kinetica.provide
import io.heapy.kinetica.provideLayoutDirection
import io.heapy.kinetica.read
import kotlinx.serialization.Serializable

@Serializable
public data class ColorTokens(
    val background: String,
    val surface: String,
    val text: String,
    val accent: String,
)

@Serializable
public data class Breakpoints(
    val compact: Int = 0,
    val medium: Int = 600,
    val expanded: Int = 840,
) {
    init {
        require(compact >= 0) { "compact breakpoint must be non-negative." }
        require(medium > compact) { "medium breakpoint must be greater than compact." }
        require(expanded > medium) { "expanded breakpoint must be greater than medium." }
    }
}

@Serializable
public enum class BreakpointClass {
    Compact,
    Medium,
    Expanded,
}

@Serializable
public data class Theme(
    val colors: ColorTokens,
    val breakpoints: Breakpoints = Breakpoints(),
    val direction: LayoutDirection = LayoutDirection.Ltr,
)

public val LightTheme: Theme = Theme(
    colors = ColorTokens(
        background = "#ffffff",
        surface = "#f4f4f5",
        text = "#18181b",
        accent = "#0f766e",
    ),
)

public val DarkTheme: Theme = Theme(
    colors = ColorTokens(
        background = "#09090b",
        surface = "#18181b",
        text = "#fafafa",
        accent = "#2dd4bf",
    ),
)

public val ThemeContext: Context<Theme> = context(LightTheme, name = "Theme")

public fun ComponentScope.theme(): Theme = read(ThemeContext)

public fun ComponentScope.provideTheme(theme: Theme, content: ComponentScope.() -> Unit) {
    provideLayoutDirection(theme.direction) {
        provide(ThemeContext, theme, content)
    }
}

public fun Breakpoints.classify(width: Int): BreakpointClass {
    require(width >= 0) { "width must be non-negative." }
    return when {
        width >= expanded -> BreakpointClass.Expanded
        width >= medium -> BreakpointClass.Medium
        else -> BreakpointClass.Compact
    }
}

public fun Theme.breakpointFor(width: Int): BreakpointClass =
    breakpoints.classify(width)

public val Theme.isRtl: Boolean
    get() = direction == LayoutDirection.Rtl

public fun <T> Theme.directional(
    ltr: T,
    rtl: T,
): T =
    if (isRtl) rtl else ltr

public fun ComponentScope.layoutDirection(): LayoutDirection =
    theme().direction

public fun ComponentScope.isRtl(): Boolean =
    theme().isRtl
