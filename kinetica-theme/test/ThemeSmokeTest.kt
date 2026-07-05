package io.heapy.kinetica.theme

import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.LayoutDirection
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.row
import io.heapy.kinetica.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThemeSmokeTest {
    @Test
    fun breakpointsClassifyViewportWidths() {
        val breakpoints = Breakpoints(compact = 0, medium = 600, expanded = 840)

        assertEquals(BreakpointClass.Compact, breakpoints.classify(320))
        assertEquals(BreakpointClass.Medium, breakpoints.classify(600))
        assertEquals(BreakpointClass.Expanded, breakpoints.classify(1_024))
        assertFailsWith<IllegalArgumentException> {
            breakpoints.classify(-1)
        }
    }

    @Test
    fun breakpointValidationDefaultsAndLtrHelpersAreExplicit() {
        val defaults = Breakpoints()

        assertEquals(0, defaults.compact)
        assertEquals(600, defaults.medium)
        assertEquals(840, defaults.expanded)
        assertEquals("#f4f4f5", LightTheme.colors.surface)
        assertEquals("#18181b", LightTheme.colors.text)
        assertEquals("#0f766e", LightTheme.colors.accent)
        assertEquals(false, LightTheme.isRtl)
        assertEquals("leading", LightTheme.directional("leading", "trailing"))

        val node = KineticaRuntime().render {
            text("${layoutDirection()}:${isRtl()}")
        }.tree
        assertEquals("Ltr:false", assertIs<TextNode>(node).value)

        assertFailsWith<IllegalArgumentException> {
            Breakpoints(compact = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            Breakpoints(compact = 100, medium = 100)
        }
        assertFailsWith<IllegalArgumentException> {
            Breakpoints(compact = 100, medium = 200, expanded = 200)
        }
    }

    @Test
    fun providedThemeExposesDirectionAndTokensThroughContext() {
        val rtlDark = DarkTheme.copy(direction = LayoutDirection.Rtl)
        val node = KineticaRuntime().render {
            provideTheme(rtlDark) {
                val current = theme()
                text("${current.colors.background}:${layoutDirection()}:${current.directional("left", "right")}")
            }
        }.tree

        assertEquals("#09090b:Rtl:right", assertIs<TextNode>(node).value)
        assertTrue(rtlDark.isRtl)
    }

    @Test
    fun providedThemeMakesRowsDirectionAware() {
        val rtlDark = DarkTheme.copy(direction = LayoutDirection.Rtl)
        val node = KineticaRuntime().render {
            provideTheme(rtlDark) {
                row {
                    text("RTL")
                }
            }
        }.tree

        assertEquals("Rtl", assertIs<HostNode>(node).props["direction"])
    }

    @Test
    fun themeClassifiesWithItsOwnBreakpointTokens() {
        val theme = LightTheme.copy(
            breakpoints = Breakpoints(compact = 0, medium = 500, expanded = 900),
        )

        assertEquals(BreakpointClass.Medium, theme.breakpointFor(720))
    }
}
