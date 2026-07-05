package io.heapy.kinetica

public interface Renderer {
    public val name: String
    public fun mount(node: Node): RenderHandle
    public fun update(handle: RenderHandle, node: Node)
    public fun dispose(handle: RenderHandle)
}

public interface RenderHandle {
    public val id: String
}

public class HeadlessRenderHandle(
    override val id: String,
    public var node: Node,
) : RenderHandle {
    public val semantics: SemanticsTree
        get() = node.semanticsTree()
}

public class HeadlessRenderer : Renderer {
    private var nextId = 0
    private val handles = mutableMapOf<String, HeadlessRenderHandle>()

    override val name: String = "headless"

    override fun mount(node: Node): RenderHandle {
        val handle = HeadlessRenderHandle("headless-${nextId++}", node)
        handles[handle.id] = handle
        return handle
    }

    override fun update(handle: RenderHandle, node: Node) {
        val headless = handles[handle.id] ?: error("Unknown render handle: ${handle.id}")
        headless.node = node
    }

    override fun dispose(handle: RenderHandle) {
        handles.remove(handle.id)
    }

    public fun handle(id: String): HeadlessRenderHandle? = handles[id]
}

