@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.appkit

import kotlinx.cinterop.ObjCAction
import platform.AppKit.NSPopUpButton
import platform.Foundation.NSMakeRect
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject

internal class AppKitChoice(private var changed: ((String) -> Unit)?) : NSPopUpButton(NSMakeRect(0.0, 0.0, 160.0, 28.0), false) {
    private var options = emptyList<ChoiceOption>()

    init { target = this; action = NSSelectorFromString("choiceChanged:") }

    fun update(value: String, next: List<ChoiceOption>) {
        require(next.isNotEmpty() && next.map { it.value }.distinct().size == next.size)
        val selected = next.indexOfFirst { it.value == value }
        require(selected >= 0)
        if (options != next) {
            removeAllItems()
            next.forEach { addItemWithTitle(it.label) }
            options = next
        }
        if (indexOfSelectedItem.toInt() != selected) selectItemAtIndex(selected.toLong())
    }

    @ObjCAction fun choiceChanged(sender: NSObject?) {
        options.getOrNull(indexOfSelectedItem.toInt())?.let { changed?.invoke(it.value) }
    }

    fun dispose() { changed = null; target = null; action = null }
}
