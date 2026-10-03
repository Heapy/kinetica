package io.heapy.kinetica.application

/** Primary is Command on macOS and Control on other desktop platforms. */
public enum class KeyModifier { PRIMARY, SHIFT, ALT, CONTROL }

/** A logical key, independent of native key codes. Named keys: Enter, Escape, Tab, Backspace. */
public data class KeyShortcut(
    val key: String,
    val modifiers: Set<KeyModifier> = setOf(KeyModifier.PRIMARY),
) {
    init {
        require(key.length == 1 || key in setOf("Enter", "Escape", "Tab", "Backspace")) { "Unsupported shortcut key: $key" }
    }
}

/** Null means that no application-owned window is currently focused. */
public data class CommandContext(val windowId: String?)

public data class CommandState(val enabled: Boolean = true, val checked: Boolean = false, val title: String? = null)

/** Menus, shortcuts and UI buttons invoke the same action and recheck its availability. */
public class ApplicationCommand(
    public val id: String,
    public val title: String,
    public val shortcuts: List<KeyShortcut> = emptyList(),
    public val windowRequired: Boolean = false,
    public val state: (CommandContext) -> CommandState = { CommandState() },
    public val action: (CommandContext) -> Unit,
) {
    init { require(id.isNotBlank()); require(title.isNotBlank()) }
}

public class ApplicationCommands(commands: List<ApplicationCommand> = emptyList()) {
    private val entries = linkedMapOf<String, ApplicationCommand>()
    init { commands.forEach(::register) }
    public val all: List<ApplicationCommand> get() = entries.values.toList()

    public fun register(command: ApplicationCommand) {
        require(command.id !in entries) { "Duplicate application command: ${command.id}" }
        val shortcuts = command.shortcuts.map { it.key.lowercase() to it.modifiers }
        require(shortcuts.distinct().size == shortcuts.size) { "Duplicate shortcut in ${command.id}" }
        require(entries.values.none { other -> other.shortcuts.any { (it.key.lowercase() to it.modifiers) in shortcuts } }) {
            "Shortcut already registered for ${command.id}"
        }
        entries[command.id] = command
    }

    public fun command(id: String): ApplicationCommand = entries.getValue(id)

    public fun state(id: String, context: CommandContext): CommandState {
        val command = command(id)
        if (command.windowRequired && context.windowId == null) return CommandState(enabled = false)
        return command.state(context)
    }

    /** A stale enabled menu item cannot execute a command after its window has closed. */
    public fun execute(id: String, context: CommandContext): Boolean {
        if (!state(id, context).enabled) return false
        command(id).action(context)
        return true
    }
}

public data class ApplicationMenu(val title: String, val items: List<MenuItem>) {
    init { require(title.isNotBlank()) }
}

public sealed interface MenuItem {
    public data class Command(val id: String, val hidden: Boolean = false) : MenuItem
    public data object Separator : MenuItem
    public data class Submenu(val menu: ApplicationMenu) : MenuItem
}
