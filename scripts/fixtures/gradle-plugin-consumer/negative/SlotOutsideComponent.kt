package fixture

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.state

// Copied into src/commonMain by scripts/verify-gradle-plugin.mjs for the negative pass: `state`
// outside a @UiComponent must be rejected by the plugin's FIR checker
// (SLOT_CALL_OUTSIDE_COMPONENT). A build that accepts this file has lost the checkers, which is
// the failure mode the raw -Xplugin wiring hides.
fun ComponentScope.notAComponent(): Int {
    val count by state { 0 }
    return count
}
