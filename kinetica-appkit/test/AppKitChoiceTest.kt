@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.appkit

import io.heapy.kinetica.*
import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class AppKitChoiceTest {
    @Test fun controlledChoiceUsesStableValuesPreservesItsViewAndReleasesItsAction() {
        NSApplication.sharedApplication()
        val window = NSWindow(NSMakeRect(0.0, 0.0, 400.0, 200.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false)
        val selected = store("light")
        val options = store(listOf(ChoiceOption("light", "Light"), ChoiceOption("dark", "Dark")))
        val app = renderAppKitApp(window.contentView!!) { ChoiceProbe(selected, options) }
        fun find(view: NSView): AppKitChoice? = view as? AppKitChoice ?: view.subviews.filterIsInstance<NSView>().firstNotNullOfOrNull(::find)
        val choice = requireNotNull(find(window.contentView!!))
        try {
            assertEquals(NSAccessibilityPopUpButtonRole, choice.accessibilityRole())
            choice.selectItemAtIndex(1)
            choice.choiceChanged(choice)
            // Native control events are delivered after AppKit completes its action.
            val deadline = NSDate.dateWithTimeIntervalSinceNow(2.0)
            while (selected.value != "dark" && deadline.timeIntervalSinceNow > 0) {
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            }
            assertEquals("dark", selected.value)
            options.value = listOf(ChoiceOption("dark", "Night"), ChoiceOption("light", "Day"))
            app.renderUntilSettled()
            assertEquals(choice, find(window.contentView!!))
            assertEquals("Night", choice.titleOfSelectedItem)
            selected.value = "light"
            app.renderUntilSettled()
            assertEquals("Day", choice.titleOfSelectedItem)
        } finally { app.dispose(); window.close() }
        assertNull(choice.target)
        assertNull(choice.action)
    }
}

@UiComponent
private fun ComponentScope.ChoiceProbe(selected: MutableCell<String>, options: Cell<List<ChoiceOption>>) {
    appKitChoice(selected.value, options.value, onChange = { selected.value = it }, semantics = Semantics(label = "Theme"))
}
