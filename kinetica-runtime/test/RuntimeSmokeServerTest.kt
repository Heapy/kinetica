package io.heapy.kinetica

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * Frame-era port of the RuntimeSmokeTest server/transport/diff/semantics sections. These
 * exercise Node values, transports, schemas, and semantics trees as plain data — no slot
 * DSL — so they port unchanged from the string-keyed era.
 */
class RuntimeSmokeServerTest {
    @Test
    fun serverRenderStreamEmitsDeferredSubtreesAsTheyBecomeReady() = runTest {
        val initial = HostNode(
            tag = "main",
            children = listOf(
                TextNode("Loading slow"),
                TextNode("Loading fast"),
                TextNode("Loading failing"),
            ),
        )

        val chunks = initial.toServerRenderStream(
            subtrees = listOf(
                ServerRenderDeferredSubtree(path = listOf(0), boundaryId = "slow") {
                    delay(50)
                    TextNode("Slow")
                },
                ServerRenderDeferredSubtree(path = listOf(1), boundaryId = "fast") {
                    TextNode("Fast")
                },
                ServerRenderDeferredSubtree(path = listOf(2), boundaryId = "failing") {
                    delay(25)
                    throw IllegalStateException("broken subtree")
                },
            ),
        )

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), chunks.map { it.sequence })
        assertEquals(initial, assertIs<ServerRenderChunk.Tree>(chunks[0]).node)
        assertEquals(listOf(1), assertIs<ServerRenderChunk.Patch>(chunks[1]).path)
        assertEquals("Fast", assertIs<TextNode>(assertIs<ServerRenderChunk.Patch>(chunks[1]).node).value)
        val error = assertIs<ServerRenderChunk.BoundaryError>(chunks[2])
        assertEquals("failing", error.boundaryId)
        assertEquals("Server render failed.", error.message)
        assertFalse("broken subtree" in error.message, "raw exception detail must not leak to the client")
        assertEquals(listOf(0), assertIs<ServerRenderChunk.Patch>(chunks[3]).path)
        assertEquals("Slow", assertIs<TextNode>(assertIs<ServerRenderChunk.Patch>(chunks[3]).node).value)
        assertIs<ServerRenderChunk.End>(chunks[4])
    }

    @Test
    fun serverRenderStreamRethrowsDeferredSubtreeCancellation() = runTest {
        val cancellation = assertFailsWith<CancellationException> {
            TextNode("Loading").toServerRenderStream(
                subtrees = listOf(
                    ServerRenderDeferredSubtree(path = listOf(0), boundaryId = "cancelled") {
                        throw CancellationException("stop streaming")
                    },
                ),
            )
        }

        assertEquals("stop streaming", cancellation.message)
    }

    @Test
    fun serverRenderChunksMaterializeTemplateNodesAtTreeAndPatchBoundaries() = runTest {
        val initialTemplate = serverBoundaryTemplateNode(text = "Initial")
        val patchTemplate = serverBoundaryTemplateNode(text = "Deferred", key = "deferred-row")

        val initialChunk = initialTemplate.toInitialServerChunk()
        assertEquals(initialTemplate.materialize(), initialChunk.node)
        assertFalse(initialChunk.node.containsTemplateNode())

        val stream = initialTemplate.toServerRenderStream()
        assertEquals(initialTemplate.materialize(), assertIs<ServerRenderChunk.Tree>(stream.first()).node)

        val deferredStream = initialTemplate.toServerRenderStream(
            subtrees = listOf(
                ServerRenderDeferredSubtree(path = listOf(0), boundaryId = "deferred") {
                    patchTemplate
                },
            ),
        )
        assertEquals(initialTemplate.materialize(), assertIs<ServerRenderChunk.Tree>(deferredStream[0]).node)
        assertEquals(patchTemplate.materialize(), assertIs<ServerRenderChunk.Patch>(deferredStream[1]).node)
        assertFalse(assertIs<ServerRenderChunk.Tree>(deferredStream[0]).node.containsTemplateNode())
        assertFalse(assertIs<ServerRenderChunk.Patch>(deferredStream[1]).node?.containsTemplateNode() ?: false)
    }

    @Test
    fun serverRenderDefaultsHydrationDiffsAndDeferredValidationAreExplicit() = runTest {
        val clientRef = ClientRef(
            componentId = "app.AddToCartButton",
            props = JsonObject.of("productId" to JsonPrimitive("sku-1")),
        )
        val mounted = HostNode(
            tag = "main",
            children = listOf(TextNode("Old details")),
        )
        val current = HostNode(
            tag = "main",
            children = listOf(
                TextNode("New details"),
                clientRef,
                TextNode("Recommendations pending"),
            ),
        )

        val hydration = current.hydrationPlan(mountedTree = mounted)
        assertEquals(
            listOf(ClientIsland("app.AddToCartButton", listOf(1), JsonObject.of("productId" to JsonPrimitive("sku-1")))),
            hydration.clientIslands,
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = listOf(0),
                    kind = NodeDiff.Kind.Replaced,
                    before = TextNode("Old details"),
                    after = TextNode("New details"),
                ),
                NodeDiff(path = listOf(1), kind = NodeDiff.Kind.Inserted, after = clientRef),
                NodeDiff(path = listOf(2), kind = NodeDiff.Kind.Inserted, after = TextNode("Recommendations pending")),
            ),
            hydration.patchesFromMountedTree,
        )

        val initial = current.toInitialServerChunk()
        assertEquals(1L, initial.sequence)
        assertEquals(current, initial.node)
        assertEquals(ClientComponentManifest(), initial.manifest)

        val stream = current.toServerRenderStream()
        assertEquals(listOf(1L, 2L), stream.map { chunk -> chunk.sequence })
        assertEquals(current, assertIs<ServerRenderChunk.Tree>(stream.first()).node)
        assertIs<ServerRenderChunk.End>(stream.last())
        assertEquals(stream, current.toServerRenderStream(subtrees = emptyList()))

        val rootSubtree = ServerRenderDeferredSubtree(path = emptyList()) {
            TextNode("Root patch")
        }
        assertEquals("root", rootSubtree.boundaryId)
        val nestedSubtree = ServerRenderDeferredSubtree(path = listOf(1, 2)) {
            TextNode("Nested patch")
        }
        assertEquals("boundary.1.2", nestedSubtree.boundaryId)
        assertFailsWith<IllegalArgumentException> {
            ServerRenderDeferredSubtree(path = listOf(-1)) {
                TextNode("Invalid")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ServerRenderDeferredSubtree(path = listOf(0), boundaryId = " ") {
                TextNode("Invalid")
            }
        }
    }

    @Test
    fun serverTransportRoundTripsNodesHydrationPlansChunksAndActions() {
        val transport = KineticaServerTransport()
        val tree = FragmentNode(
            children = listOf(
                HostNode(
                    tag = "section",
                    children = listOf(
                        TextNode("Product"),
                        ClientRef(
                            componentId = "app.AddToCartButton",
                            props = JsonObject(
                                mapOf("productId" to JsonPrimitive("sku-1")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val manifest = ClientComponentManifest(
            components = listOf(
                ClientComponentRegistration(
                    componentId = "app.AddToCartButton",
                    componentFqName = "app.client.AddToCartButton",
                    serializablePropsType = "app.ProductIdProps",
                ),
            ),
            actions = listOf(
                ServerActionRegistration(
                    actionId = "cart.add",
                    functionFqName = "app.server.addToCart",
                    invalidates = listOf("cart"),
                ),
            ),
        )

        val decodedNode = transport.decodeNode(transport.encodeNode(tree))
        assertEquals(tree, decodedNode)

        val hydration = decodedNode.hydrationPlan()
        assertEquals(
            listOf(ClientIsland("app.AddToCartButton", listOf(0, 1), JsonObject(mapOf("productId" to JsonPrimitive("sku-1"))))),
            hydration.clientIslands,
        )
        assertEquals(hydration, transport.decodeHydrationPlan(transport.encodeHydrationPlan(hydration)))

        val stream = decodedNode.toServerRenderStream(manifest)
        assertIs<ServerRenderChunk.Tree>(transport.decodeChunk(transport.encodeChunk(stream.first())))
        assertIs<ServerRenderChunk.End>(transport.decodeChunk(transport.encodeChunk(stream.last())))
        assertEquals(stream.first(), transport.decodeChecksummedChunk(transport.encodeChecksummedChunk(stream.first())))
        val corruptedChecksummedChunk = transport
            .encodeChecksummedChunk(stream.first())
            .replace("sku-1", "sku-2")
        assertFailsWith<IllegalArgumentException> {
            transport.decodeChecksummedChunk(corruptedChecksummedChunk)
        }

        val request = ServerActionRequest(
            actionId = "cart.add",
            payload = JsonObject(mapOf("productId" to JsonPrimitive("sku-1"))),
            token = CapabilityToken("capability-token"),
            csrfToken = CsrfToken("csrf-token"),
        )
        assertEquals(request, transport.decodeActionRequest(transport.encodeActionRequest(request)))

        val response: ServerActionResponse = ServerActionResponse.Success(
            payload = JsonObject(mapOf("ok" to JsonPrimitive(true))),
            invalidated = listOf("cart"),
        )
        assertEquals(response, transport.decodeActionResponse(transport.encodeActionResponse(response)))
    }

    @Test
    fun serverTransportEncodeChunkMaterializesTemplateNodesAtWireBoundary() {
        val transport = KineticaServerTransport()
        val node = serverBoundaryTemplateNode(text = "Chunk")
        val chunk = ServerRenderChunk.Tree(sequence = 7, node = node)

        val encoded = transport.encodeChunk(chunk)

        assertEquals(1, encoded.countOccurrences("static-skeleton-marker"))
        assertFalse("\"type\":\"template\"" in encoded, encoded)
        assertEquals(
            ServerRenderChunk.Tree(sequence = 7, node = node.materialize()),
            transport.decodeChunk(encoded),
        )
    }

    @Test
    fun serverTransportEncodeChecksummedChunkMaterializesTemplateNodesBeforeHashing() {
        val transport = KineticaServerTransport()
        val node = serverBoundaryTemplateNode(text = "Signed")
        val normalized = ServerRenderChunk.Tree(sequence = 8, node = node.materialize())

        val encoded = transport.encodeChecksummedChunk(ServerRenderChunk.Tree(sequence = 8, node = node))

        assertEquals(1, encoded.countOccurrences("static-skeleton-marker"))
        assertFalse("\"type\":\"template\"" in encoded, encoded)
        assertEquals(normalized, transport.decodeChecksummedChunk(encoded))
    }

    @Test
    fun serverTransportAndJournalModelsExposeStableDefaults() {
        val transport = KineticaServerTransport()
        val text = TextNode("Stable")

        val manifest = ClientComponentManifest()
        assertEquals(emptyList(), manifest.components)
        assertEquals(emptyList(), manifest.actions)
        assertEquals(null, ClientComponentRegistration("app.Client", "app.Client").serializablePropsType)
        assertEquals(emptyList(), ServerActionRegistration("cart.add", "app.addToCart").invalidates)
        assertEquals(null, ServerActionRegistration("cart.add", "app.addToCart").inputSchema)

        val schema = ServerActionPayloadSchema(kind = JsonValueKind.Object)
        assertFalse(schema.nullable)
        assertEquals(emptyList(), schema.fields)
        assertTrue(schema.allowUnknownFields)
        val field = ServerActionFieldSchema(name = "quantity", kind = JsonValueKind.Number)
        assertTrue(field.required)
        assertFalse(field.nullable)

        assertEquals(ServerRenderChunk.Tree(sequence = 1, node = text), transport.decodeChunk(transport.encodeChunk(ServerRenderChunk.Tree(1, text))))
        assertEquals(ServerRenderChunk.Patch(sequence = 2, path = listOf(0), node = null), transport.decodeChunk(transport.encodeChunk(ServerRenderChunk.Patch(2, listOf(0), null))))
        assertEquals(ServerRenderChunk.BoundaryError(sequence = 3, boundaryId = "root", message = "failed"), transport.decodeChunk(transport.encodeChunk(ServerRenderChunk.BoundaryError(3, "root", "failed"))))
        assertEquals(ServerRenderChunk.End(sequence = 4), transport.decodeChunk(transport.encodeChunk(ServerRenderChunk.End(4))))

        val hydration = HydrationPlan(initialTree = text, clientIslands = emptyList())
        assertEquals(emptyList(), hydration.patchesFromMountedTree)
        assertEquals(hydration, transport.decodeHydrationPlan(transport.encodeHydrationPlan(hydration)))

        val request = ServerActionRequest(
            actionId = "cart.add",
            payload = JsonNull,
            token = CapabilityToken("capability"),
        )
        assertEquals(null, request.csrfToken)
        assertEquals(request, transport.decodeActionRequest(transport.encodeActionRequest(request)))
        assertEquals(
            ServerActionResponse.Success(payload = JsonNull),
            transport.decodeActionResponse(transport.encodeActionResponse(ServerActionResponse.Success(JsonNull))),
        )
        assertEquals(
            ServerActionResponse.Failure(message = "try again"),
            transport.decodeActionResponse(transport.encodeActionResponse(ServerActionResponse.Failure("try again"))),
        )

        val checksummed = ChecksummedServerRenderChunk(
            chunk = ServerRenderChunk.End(sequence = 5),
            checksum = ChunkChecksum(algorithm = "manual", value = "hash"),
        )
        assertEquals("manual", checksummed.checksum.algorithm)
        assertEquals("hash", checksummed.checksum.value)
        assertEquals("csrf", CsrfToken("csrf").value)

        val entry = JournalEntry(sequence = 1, kind = JournalKind.Event, message = "clicked")
        assertEquals(emptyMap(), entry.attributes)
        val warning = RuntimeWarning(sequence = 2, code = "KINETICA_WARNING", message = "warn")
        assertEquals(emptyMap(), warning.attributes)
        val slotEntry = SlotSnapshotEntry(key = "slot", persistent = true, transient = false, value = "7")
        assertEquals(null, slotEntry.slotId)
        assertEquals(emptyList(), SlotSnapshot(sequence = 1).slots)

        val renderSnapshot = RenderSnapshot(
            sequence = 1,
            cause = "initial",
            tree = text,
            slots = SlotSnapshot(sequence = 1, slots = listOf(slotEntry)),
        )
        val exported = ExecutionJournal(entries = listOf(entry), renderSnapshots = listOf(renderSnapshot))
        assertEquals(exported, KineticaJson.decodeFromString<ExecutionJournal>(KineticaJson.encodeToString(exported)))
        assertEquals(emptyList(), ExecutionJournal(entries = emptyList()).renderSnapshots)

        val replay = replayJournal(exported)
        assertEquals(1L, replay.latest().sequence)
        assertEquals(listOf(entry), replay.latest().entries)
        assertEquals(renderSnapshot, replay.states().single().render)
    }

    @Test
    fun nodeDiffCoversRootLeafAndRecursiveChildChanges() {
        val inserted = TextNode("Inserted")
        assertEquals(emptyList(), diffNodes(before = null, after = null))
        assertEquals(
            listOf(NodeDiff(path = emptyList(), kind = NodeDiff.Kind.Inserted, after = inserted)),
            diffNodes(before = null, after = inserted),
        )

        val removed = TextNode("Removed")
        assertEquals(
            listOf(NodeDiff(path = emptyList(), kind = NodeDiff.Kind.Removed, before = removed)),
            diffNodes(before = removed, after = null),
        )

        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = TextNode("Text"),
                    after = ClientRef("app.Client"),
                ),
            ),
            diffNodes(before = TextNode("Text"), after = ClientRef("app.Client")),
        )
        assertEquals(emptyList(), diffNodes(TextNode("Same"), TextNode("Same")))
        assertEquals(emptyList(), diffNodes(FragmentNode(), FragmentNode()))
        assertEquals(emptyList(), diffNodes(HostNode(tag = "section"), HostNode(tag = "section")))
        assertEquals(emptyList(), diffNodes(ClientRef("app.Client"), ClientRef("app.Client")))
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = TextNode("Before"),
                    after = TextNode("After"),
                ),
            ),
            diffNodes(TextNode("Before"), TextNode("After")),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = ClientRef("app.Client", props = JsonObject(mapOf("version" to JsonPrimitive(1)))),
                    after = ClientRef("app.Client", props = JsonObject(mapOf("version" to JsonPrimitive(2)))),
                ),
            ),
            diffNodes(
                ClientRef("app.Client", props = JsonObject(mapOf("version" to JsonPrimitive(1)))),
                ClientRef("app.Client", props = JsonObject(mapOf("version" to JsonPrimitive(2)))),
            ),
        )

        val before = HostNode(
            tag = "ul",
            children = listOf(
                TextNode("A"),
                TextNode("B"),
                ClientRef("app.Item"),
            ),
        )
        val after = HostNode(
            tag = "ul",
            children = listOf(
                TextNode("A"),
                TextNode("C"),
                ClientRef("app.Item"),
                TextNode("D"),
            ),
        )
        assertEquals(
            listOf(
                NodeDiff(path = listOf(1), kind = NodeDiff.Kind.Replaced, before = TextNode("B"), after = TextNode("C")),
                NodeDiff(path = listOf(3), kind = NodeDiff.Kind.Inserted, after = TextNode("D")),
            ),
            diffNodes(before, after),
        )
        assertEquals(
            listOf(NodeDiff(path = listOf(0), kind = NodeDiff.Kind.Removed, before = TextNode("A"))),
            diffNodes(FragmentNode(children = listOf(TextNode("A"))), FragmentNode()),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = HostNode(
                        tag = "button",
                        props = mapOf("enabled" to "true"),
                        children = listOf(TextNode("Save")),
                        key = "primary",
                        semantics = Semantics(role = Role.Button, label = "Save"),
                    ),
                    after = HostNode(
                        tag = "button",
                        props = mapOf("enabled" to "false"),
                        children = listOf(TextNode("Save")),
                        key = "primary",
                        semantics = Semantics(role = Role.Button, label = "Save"),
                    ),
                ),
            ),
            diffNodes(
                before = HostNode(
                    tag = "button",
                    props = mapOf("enabled" to "true"),
                    children = listOf(TextNode("Save")),
                    key = "primary",
                    semantics = Semantics(role = Role.Button, label = "Save"),
                ),
                after = HostNode(
                    tag = "button",
                    props = mapOf("enabled" to "false"),
                    children = listOf(TextNode("Save")),
                    key = "primary",
                    semantics = Semantics(role = Role.Button, label = "Save"),
                ),
            ),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = FragmentNode(semantics = Semantics(label = "before")),
                    after = FragmentNode(semantics = Semantics(label = "after")),
                ),
            ),
            diffNodes(
                before = FragmentNode(semantics = Semantics(label = "before")),
                after = FragmentNode(semantics = Semantics(label = "after")),
            ),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = HostNode(tag = "button"),
                    after = HostNode(tag = "a"),
                ),
            ),
            diffNodes(before = HostNode(tag = "button"), after = HostNode(tag = "a")),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = HostNode(tag = "button", key = "primary"),
                    after = HostNode(tag = "button", key = "secondary"),
                ),
            ),
            diffNodes(
                before = HostNode(tag = "button", key = "primary"),
                after = HostNode(tag = "button", key = "secondary"),
            ),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = HostNode(tag = "button", semantics = Semantics(role = Role.Button, label = "Save")),
                    after = HostNode(tag = "button", semantics = Semantics(role = Role.Button, label = "Submit")),
                ),
            ),
            diffNodes(
                before = HostNode(tag = "button", semantics = Semantics(role = Role.Button, label = "Save")),
                after = HostNode(tag = "button", semantics = Semantics(role = Role.Button, label = "Submit")),
            ),
        )

        val explicitDefault = NodeDiff(path = listOf(9), kind = NodeDiff.Kind.Inserted)
        assertEquals(listOf(9), explicitDefault.path)
        assertEquals(NodeDiff.Kind.Inserted, explicitDefault.kind)
        assertEquals(null, explicitDefault.before)
        assertEquals(null, explicitDefault.after)
    }

    @Test
    fun templateNodesMaterializeForHtmlSemanticsAndDiffs() {
        val definition = TemplateDefinition(
            id = "runtime-template",
            skeleton = HostNode(
                tag = "span",
                props = propsOf("class", ""),
                children = listOf(TextNode("", semantics = Semantics(role = Role.Text))),
            ),
            holes = listOf(
                TemplateHole(path = "", kind = TemplateHoleKinds.Prop, propName = "class"),
                TemplateHole(path = "0", kind = TemplateHoleKinds.Text),
            ),
        )
        val hello = templateNode(definition, values = listOf("cold", "Hello"), key = "greeting")
        val world = templateNode(definition, values = listOf("cold", "World"), key = "greeting")

        assertEquals(
            HostNode(
                tag = "span",
                props = propsOf("class", "cold"),
                children = listOf(TextNode("Hello", semantics = Semantics(role = Role.Text))),
                key = "greeting",
            ),
            hello.materialize(),
        )
        assertEquals("""<span class="cold" data-kinetica-key="greeting">Hello</span>""", hello.toSafeHtml())
        assertEquals(null, hello.semanticsTree().byRole(Role.Text).single().semantics.label)
        assertEquals(
            listOf(
                NodeDiff(
                    path = listOf(0),
                    kind = NodeDiff.Kind.Replaced,
                    before = TextNode("Hello", semantics = Semantics(role = Role.Text)),
                    after = TextNode("World", semantics = Semantics(role = Role.Text)),
                ),
            ),
            diffNodes(hello, world),
        )
        assertEquals(
            listOf(
                NodeDiff(
                    path = emptyList(),
                    kind = NodeDiff.Kind.Replaced,
                    before = hello.materialize(),
                    after = templateNode(definition, values = listOf("warm", "Hello"), key = "greeting").materialize(),
                ),
            ),
            diffNodes(hello, templateNode(definition, values = listOf("warm", "Hello"), key = "greeting")),
        )

        val nestedDefinition = TemplateDefinition(
            id = "nested-template",
            skeleton = HostNode(
                tag = "strong",
                props = propsOf("data-static", "yes"),
                children = listOf(TextNode("", semantics = null)),
            ),
            holes = listOf(TemplateHole(path = "0", kind = TemplateHoleKinds.Text)),
        )
        val complexDefinition = TemplateDefinition(
            id = "complex-template",
            skeleton = HostNode(
                tag = "article",
                props = propsOf("class", "fallback", "data-action", "stale"),
                children = listOf(
                    FragmentNode(children = listOf(TextNode("pending", semantics = null))),
                    HostNode(
                        tag = "button",
                        props = propsOf("data-action", "stale"),
                        children = listOf(TextNode("Click", semantics = null)),
                        key = "child",
                    ),
                    TemplateNode(nestedDefinition, values = listOf("Nested")),
                ),
                key = "skeleton-key",
            ),
            holes = listOf(
                TemplateHole(path = "", kind = TemplateHoleKinds.Prop, propName = "class"),
                TemplateHole(path = "", kind = TemplateHoleKinds.EventProp, propName = "event:onClick"),
                TemplateHole(path = "", kind = TemplateHoleKinds.Prop),
                TemplateHole(path = "0.0", kind = TemplateHoleKinds.Text),
                TemplateHole(path = "1", kind = TemplateHoleKinds.Prop, propName = "data-action"),
                TemplateHole(path = "1.0", kind = TemplateHoleKinds.Text),
            ),
        )
        val complex = TemplateNode(
            definition = complexDefinition,
            values = listOf(null, "event-7", "ignored", "Body", null),
        ).materialize()
        assertEquals("article", complex.tag)
        assertEquals("skeleton-key", complex.key)
        assertFalse("class" in complex.props)
        assertEquals("event-7", complex.props["event:onClick"])
        assertEquals("Body", assertIs<TextNode>(assertIs<FragmentNode>(complex.children[0]).children.single()).value)
        val button = assertIs<HostNode>(complex.children[1])
        assertFalse("data-action" in button.props)
        assertEquals("", assertIs<TextNode>(button.children.single()).value)
        val nested = assertIs<HostNode>(complex.children[2])
        assertEquals("strong", nested.tag)
        assertEquals("Nested", assertIs<TextNode>(nested.children.single()).value)
    }

    @Test
    fun diffNodesNormalizesTemplateNodesBeforeComparingContainers() {
        val before = serverBoundaryTemplateNode(text = "Before")
        val after = before.materialize().copy(
            children = listOf(
                assertIs<HostNode>(before.materialize().children[0]).copy(
                    children = listOf(TextNode("After", semantics = null)),
                ),
                before.materialize().children[1],
            ),
        )

        assertEquals(
            listOf(
                NodeDiff(
                    path = listOf(0, 0),
                    kind = NodeDiff.Kind.Replaced,
                    before = TextNode("Before", semantics = null),
                    after = TextNode("After", semantics = null),
                ),
            ),
            diffNodes(before, after),
        )
    }

    @Test
    fun hydrationPlanMaterializesTemplateNodesInInitialTree() {
        val node = serverBoundaryTemplateNode(text = "Hydrate")

        val hydration = node.hydrationPlan()

        assertEquals(node.materialize(), hydration.initialTree)
        assertFalse(hydration.initialTree.containsTemplateNode())
    }

    @Test
    fun safeHtmlEscapesTextAttributesKeysAndClientRefs() {
        val html = HostNode(
            tag = "section",
            props = linkedMapOf(
                "title" to "\"<Cart & checkout>'",
                "event:onClick" to "event-0",
                "frame:translateX" to "frame-0",
                "onclick" to "alert(1)",
                "href" to "javascript:alert(1)",
                "srcdoc" to "<script>alert(1)</script>",
                "formaction" to " data:text/html,<script>alert(1)</script>",
                "cite" to "https://example.test/cart",
            ),
            children = listOf(
                TextNode("<script>alert('x')</script> & total"),
                ClientRef(
                    componentId = "app.AddToCart\"Button",
                    props = JsonObject(mapOf("productId" to JsonPrimitive("<sku&1>"))),
                ),
            ),
            key = "<row&1>",
        ).toSafeHtml()

        assertTrue(html.startsWith("<section "))
        assertTrue("title=\"&quot;&lt;Cart &amp; checkout&gt;&#39;\"" in html)
        assertTrue("data-kinetica-key=\"&lt;row&amp;1&gt;\"" in html)
        assertTrue("&lt;script&gt;alert('x')&lt;/script&gt; &amp; total" in html)
        assertTrue("data-kinetica-client-ref=\"app.AddToCart&quot;Button\"" in html)
        assertTrue("data-kinetica-props=\"" in html)
        assertTrue("&lt;sku&amp;1&gt;" in html)
        assertFalse("event:onClick" in html)
        assertFalse("frame:translateX" in html)
        assertFalse("onclick" in html)
        assertFalse("href=" in html)
        assertFalse("srcdoc=" in html)
        assertFalse("formaction=" in html)
        assertTrue("cite=\"https://example.test/cart\"" in html)
        assertFalse("<script>" in html)

        val unsafeHtml = HostNode(
            tag = "9bad<tag",
            props = linkedMapOf(
                "" to "empty",
                "9bad" to "bad",
                "data bad" to "space",
                "aria-label" to "Kept",
            ),
            children = listOf(TextNode("Safe")),
        ).toSafeHtml()

        assertTrue(unsafeHtml.startsWith("<div data-kinetica-tag=\"9bad&lt;tag\" aria-label=\"Kept\">"))
        assertTrue(unsafeHtml.endsWith(">Safe</div>"))
        assertFalse("=\"empty\"" in unsafeHtml)
        assertFalse("9bad=\"bad\"" in unsafeHtml)
        assertFalse("data bad=\"space\"" in unsafeHtml)

        // Well-formed but executable tag names must be neutralized to a div —
        // text inside a real <script> would execute even though it's entity-escaped on the way out.
        listOf("script", "iframe", "object", "embed", "svg", "style", "template").forEach { dangerousTag ->
            val neutralized = HostNode(
                tag = dangerousTag,
                children = listOf(TextNode("payload")),
            ).toSafeHtml()
            assertEquals(
                "<div data-kinetica-tag=\"$dangerousTag\">payload</div>",
                neutralized,
                "tag '$dangerousTag' should be neutralized to div",
            )
        }

        assertEquals(
            "<div data-kinetica-tag=\"SCRIPT\">x</div>",
            HostNode(
                tag = "SCRIPT",
                children = listOf(TextNode("x")),
            ).toSafeHtml(),
            "dangerous tag denylisting must be case-insensitive",
        )
        assertEquals(
            "<select><option>One</option></select>",
            HostNode(
                tag = "select",
                children = listOf(
                    HostNode(
                        tag = "option",
                        children = listOf(TextNode("One")),
                    ),
                ),
            ).toSafeHtml(),
            "legitimate HTML tags should render verbatim",
        )
        assertEquals(
            "<column><row>payload</row></column>",
            HostNode(
                tag = "column",
                children = listOf(
                    HostNode(
                        tag = "row",
                        children = listOf(TextNode("payload")),
                    ),
                ),
            ).toSafeHtml(),
            "layout DSL tags should render verbatim in SSR snapshots",
        )

        assertTrue(isPublicHtmlAttribute("aria-label", "Kept"))
        assertFalse(isPublicHtmlAttribute("event:onClick", "event-0"))
        assertFalse(isPublicHtmlAttribute("frame:translateX", "frame-0"))
        assertFalse(isPublicHtmlAttribute("onclick", "alert(1)"))
        assertFalse(isSafeHtmlAttributeValue("srcdoc", "plain text"))
        assertTrue(isSafeHtmlAttributeValue("href", ""))
        assertTrue(isSafeHtmlAttributeValue("href", "#cart"))
        assertTrue(isSafeHtmlAttributeValue("href", "/cart"))
        assertTrue(isSafeHtmlAttributeValue("href", "./cart"))
        assertTrue(isSafeHtmlAttributeValue("href", "../cart"))
        assertTrue(isSafeHtmlAttributeValue("href", "?sku=runtime"))
        assertTrue(isSafeHtmlAttributeValue("href", "cart/runtime-license"))
        assertTrue(isSafeHtmlAttributeValue("href", "cart/runtime-license:http"))
        assertTrue(isSafeHtmlAttributeValue("href", "mailto:team@example.test"))
        assertTrue(isSafeHtmlAttributeValue("href", "TEL:+123"))
        assertFalse(isSafeHtmlAttributeValue("href", " javascript:alert(1)"))
    }

    @Test
    fun serverActionSchemaAcceptsEnumAndMapInputs() {
        // A top-level enum input serializes as a JSON string and must validate.
        val enumSchema = serverActionPayloadSchema(SmokeCartStatus.serializer())
        assertEquals(JsonValueKind.String, enumSchema.kind)
        assertEquals(emptyList(), enumSchema.validate(JsonPrimitive("Pending")))

        // A map input serializes as a JSON object with arbitrary keys; the map descriptor's
        // synthetic key/value elements must NOT be derived as required fields.
        val mapSchema = serverActionPayloadSchema(MapSerializer(String.serializer(), Int.serializer()))
        assertEquals(JsonValueKind.Object, mapSchema.kind)
        assertEquals(
            emptyList(),
            mapSchema.validate(
                JsonObject(mapOf("steps" to JsonPrimitive(3), "lives" to JsonPrimitive(5))),
            ),
        )
    }

    @Test
    fun serverActionSchemasValidateKindsFieldsNullsUnknownsAndSerializers() {
        val schema = ServerActionPayloadSchema(
            kind = JsonValueKind.Object,
            fields = listOf(
                ServerActionFieldSchema("title", JsonValueKind.String),
                ServerActionFieldSchema("count", JsonValueKind.Number),
                ServerActionFieldSchema("flag", JsonValueKind.Boolean, required = false),
                ServerActionFieldSchema("maybe", JsonValueKind.Null, required = false, nullable = true),
            ),
            allowUnknownFields = false,
        )

        assertEquals(
            emptyList(),
            schema.validate(
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("Cart"),
                        "count" to JsonPrimitive(2),
                        "flag" to JsonPrimitive(true),
                        "maybe" to JsonNull,
                    ),
                ),
            ),
        )
        assertEquals(listOf("expected object payload"), schema.validate(JsonNull))
        assertEquals(listOf("expected object payload"), schema.validate(JsonPrimitive("not-object")))
        assertEquals(
            listOf(
                "field 'title' expected string",
                "field 'count' expected number",
                "field 'flag' expected boolean",
                "field 'maybe' expected null",
                "unknown field 'extra'",
            ),
            schema.validate(
                JsonObject(
                    mapOf(
                        "title" to JsonNull,
                        "count" to JsonPrimitive("two"),
                        "flag" to JsonPrimitive("true"),
                        "maybe" to JsonPrimitive("not-null"),
                        "extra" to JsonPrimitive(1),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("missing required field 'title'"),
            schema.validate(JsonObject(mapOf("count" to JsonPrimitive(1)))),
        )

        assertEquals(emptyList(), ServerActionPayloadSchema(JsonValueKind.String, nullable = true).validate(JsonNull))
        assertEquals(emptyList(), ServerActionPayloadSchema(JsonValueKind.Null).validate(JsonNull))
        assertEquals(listOf("expected null payload"), ServerActionPayloadSchema(JsonValueKind.Null).validate(JsonPrimitive("x")))
        assertEquals(emptyList(), ServerActionPayloadSchema(JsonValueKind.Array).validate(JsonArray(emptyList())))
        assertEquals(listOf("expected array payload"), ServerActionPayloadSchema(JsonValueKind.Array).validate(JsonObject(emptyMap())))
        assertEquals(emptyList(), ServerActionPayloadSchema(JsonValueKind.Boolean).validate(JsonPrimitive(false)))
        assertEquals(listOf("expected boolean payload"), ServerActionPayloadSchema(JsonValueKind.Boolean).validate(JsonPrimitive("false")))
        assertEquals(emptyList(), ServerActionPayloadSchema(JsonValueKind.Number).validate(JsonPrimitive(1.5)))
        assertEquals(listOf("expected number payload"), ServerActionPayloadSchema(JsonValueKind.Number).validate(JsonPrimitive("1.5")))
        assertEquals(emptyList(), ServerActionPayloadSchema(JsonValueKind.String).validate(JsonPrimitive("value")))
        assertEquals(listOf("expected string payload"), ServerActionPayloadSchema(JsonValueKind.String).validate(JsonPrimitive(3)))

        assertEquals(JsonValueKind.Boolean, serverActionPayloadSchema(Boolean.serializer()).kind)
        assertEquals(JsonValueKind.Number, serverActionPayloadSchema(Byte.serializer()).kind)
        assertEquals(JsonValueKind.Number, serverActionPayloadSchema(Short.serializer()).kind)
        assertEquals(JsonValueKind.Number, serverActionPayloadSchema(Int.serializer()).kind)
        assertEquals(JsonValueKind.Number, serverActionPayloadSchema(Long.serializer()).kind)
        assertEquals(JsonValueKind.Number, serverActionPayloadSchema(Float.serializer()).kind)
        assertEquals(JsonValueKind.Number, serverActionPayloadSchema(Double.serializer()).kind)
        assertEquals(JsonValueKind.String, serverActionPayloadSchema(Char.serializer()).kind)
        assertEquals(JsonValueKind.String, serverActionPayloadSchema(SmokeCartStatus.serializer()).kind)
        assertEquals(JsonValueKind.Array, serverActionPayloadSchema(ListSerializer(String.serializer())).kind)
        assertEquals(
            listOf(
                ServerActionFieldSchema("productId", JsonValueKind.String),
                ServerActionFieldSchema("quantity", JsonValueKind.Number, required = false),
                ServerActionFieldSchema("note", JsonValueKind.String, required = false, nullable = true),
            ),
            serverActionPayloadSchema(SmokeOptionalCartPatch.serializer()).fields,
        )
        assertEquals(
            listOf(
                ServerActionFieldSchema("productId", JsonValueKind.String),
                ServerActionFieldSchema("status", JsonValueKind.String),
            ),
            serverActionPayloadSchema(SmokeCartStatusPatch.serializer()).fields,
        )
    }

    @Test
    fun serverActionDispatcherInvokesTypedStubsAndVerifiesCapability() = runTest {
        var stringHandlerCalls = 0
        var dtoHandlerCalls = 0
        var enumHandlerCalls = 0
        val explicitSchema = ServerActionPayloadSchema(
            kind = JsonValueKind.Object,
            fields = emptyList(),
            allowUnknownFields = true,
        )
        val dispatcher = KineticaServerActionDispatcher(
            stubs = listOf(
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.add",
                        functionFqName = "app.server.addToCart",
                        invalidates = listOf("cart"),
                    ),
                    inputSerializer = String.serializer(),
                    outputSerializer = String.serializer(),
                    handler = { productId ->
                        stringHandlerCalls += 1
                        "added:$productId"
                    },
                ),
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.setQuantity",
                        functionFqName = "app.server.setQuantity",
                        invalidates = listOf("cart"),
                    ),
                    inputSerializer = SmokeCartQuantityDraft.serializer(),
                    outputSerializer = String.serializer(),
                    handler = { draft ->
                        dtoHandlerCalls += 1
                        "${draft.productId}:${draft.quantity}"
                    },
                ),
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.setStatus",
                        functionFqName = "app.server.setStatus",
                        invalidates = listOf("cart"),
                    ),
                    inputSerializer = SmokeCartStatusPatch.serializer(),
                    outputSerializer = String.serializer(),
                    handler = { patch ->
                        enumHandlerCalls += 1
                        "${patch.productId}:${patch.status.name}"
                    },
                ),
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.explicitSchema",
                        functionFqName = "app.server.explicitSchema",
                        inputSchema = explicitSchema,
                    ),
                    inputSerializer = SmokeCartQuantityDraft.serializer(),
                    outputSerializer = String.serializer(),
                    handler = { draft ->
                        "explicit:${draft.productId}:${draft.quantity}"
                    },
                ),
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.crash",
                        functionFqName = "app.server.crash",
                    ),
                    inputSerializer = String.serializer(),
                    outputSerializer = String.serializer(),
                    handler = {
                        throw IllegalStateException("database password=secret")
                    },
                ),
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.reject",
                        functionFqName = "app.server.reject",
                    ),
                    inputSerializer = String.serializer(),
                    outputSerializer = String.serializer(),
                    handler = {
                        throw ServerActionRejection("quantity must be in 1..999.")
                    },
                ),
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.cancel",
                        functionFqName = "app.server.cancel",
                    ),
                    inputSerializer = String.serializer(),
                    outputSerializer = String.serializer(),
                    handler = {
                        throw CancellationException("cancelled")
                    },
                ),
            ),
            verifyCapabilityToken = { token -> token.value == "valid" },
            verifyCsrfToken = { token -> token?.value == "csrf" },
        )

        val success = dispatcher.dispatch(
            ServerActionRequest(
                actionId = "cart.add",
                payload = JsonPrimitive("sku-1"),
                token = CapabilityToken("valid"),
                csrfToken = CsrfToken("csrf"),
            ),
        )

        assertEquals(
            ServerActionResponse.Success(
                payload = JsonPrimitive("added:sku-1"),
                invalidated = listOf("cart"),
            ),
            success,
        )
        assertEquals(1, stringHandlerCalls)
        assertEquals(JsonValueKind.String, dispatcher.actions.single { it.actionId == "cart.add" }.inputSchema?.kind)

        assertEquals(
            ServerActionResponse.Failure("Invalid server action payload: expected string payload"),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.add",
                    payload = JsonObject(mapOf("productId" to JsonPrimitive("sku-1"))),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        assertEquals(1, stringHandlerCalls)

        assertEquals(
            ServerActionResponse.Failure("Invalid server action payload: missing required field 'productId'"),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.setQuantity",
                    payload = JsonObject(mapOf("quantity" to JsonPrimitive(2))),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        assertEquals(0, dtoHandlerCalls)

        assertEquals(
            ServerActionResponse.Success(
                payload = JsonPrimitive("sku-1:2"),
                invalidated = listOf("cart"),
            ),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.setQuantity",
                    payload = JsonObject(
                        mapOf(
                            "productId" to JsonPrimitive("sku-1"),
                            "quantity" to JsonPrimitive(2),
                        ),
                    ),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        assertEquals(1, dtoHandlerCalls)
        assertEquals(
            ServerActionResponse.Success(
                payload = JsonPrimitive("sku-1:Done"),
                invalidated = listOf("cart"),
            ),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.setStatus",
                    payload = JsonObject(
                        mapOf(
                            "productId" to JsonPrimitive("sku-1"),
                            "status" to JsonPrimitive("Done"),
                        ),
                    ),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        assertEquals(1, enumHandlerCalls)
        assertEquals(
            ServerActionResponse.Success(
                payload = JsonPrimitive("explicit:sku-2:4"),
                invalidated = emptyList(),
            ),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.explicitSchema",
                    payload = JsonObject(
                        mapOf(
                            "productId" to JsonPrimitive("sku-2"),
                            "quantity" to JsonPrimitive(4),
                        ),
                    ),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        assertEquals(
            explicitSchema,
            dispatcher.actions.single { it.actionId == "cart.explicitSchema" }.inputSchema,
        )
        assertEquals(
            ServerActionResponse.Failure("Server action failed."),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.crash",
                    payload = JsonPrimitive("sku-1"),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        // A ServerActionRejection surfaces its client-safe message verbatim — handlers use it to
        // reject bad input (e.g. out-of-range quantities) with a specific Failure.
        assertEquals(
            ServerActionResponse.Failure("quantity must be in 1..999."),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.reject",
                    payload = JsonPrimitive("sku-1"),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        val cancellation = assertFailsWith<CancellationException> {
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.cancel",
                    payload = JsonPrimitive("sku-1"),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            )
        }
        assertEquals("cancelled", cancellation.message)
        assertEquals(
            ServerActionResponse.Failure("Invalid capability token."),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.add",
                    payload = JsonPrimitive("sku-1"),
                    token = CapabilityToken("invalid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
        assertEquals(
            ServerActionResponse.Failure("Invalid CSRF token."),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.add",
                    payload = JsonPrimitive("sku-1"),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("wrong"),
                ),
            ),
        )
        assertEquals(
            ServerActionResponse.Failure("Unknown server action."),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.remove",
                    payload = JsonPrimitive("sku-1"),
                    token = CapabilityToken("valid"),
                    csrfToken = CsrfToken("csrf"),
                ),
            ),
        )
    }

    @Test
    fun serverActionDispatcherReportsTypeInvalidPayloadAsClientError() = runTest {
        val dispatcher = KineticaServerActionDispatcher(
            stubs = listOf(
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "count.set",
                        functionFqName = "app.server.setCount",
                    ),
                    inputSerializer = Int.serializer(),
                    outputSerializer = Int.serializer(),
                    handler = { input -> input },
                ),
            ),
            verifyCapabilityToken = { true },
            verifyCsrfToken = { true },
        )
        // 3.5 passes the Number-kind schema check but cannot decode into Int.
        val response = dispatcher.dispatch(
            ServerActionRequest(
                actionId = "count.set",
                payload = JsonPrimitive(3.5),
                token = CapabilityToken("valid"),
                csrfToken = CsrfToken("valid"),
            ),
        )

        assertEquals(
            ServerActionResponse.Failure("Invalid server action payload."),
            response,
        )
    }

    @Test
    fun serverActionDispatcherConvertsInitBlockFailureToClientError() = runTest {
        val dispatcher = KineticaServerActionDispatcher(
            stubs = listOf(
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "guarded.set",
                        functionFqName = "app.server.guardedSet",
                    ),
                    inputSerializer = InitGuardedInput.serializer(),
                    outputSerializer = Int.serializer(),
                    handler = { input -> input.n },
                ),
            ),
            verifyCapabilityToken = { true },
            verifyCsrfToken = { true },
        )
        // {"n":-5} is a shape-valid object (n is a Number) but violates the init require, so
        // decode throws IllegalArgumentException (NOT a SerializationException).
        val response = dispatcher.dispatch(
            ServerActionRequest(
                actionId = "guarded.set",
                payload = JsonObject(mapOf("n" to JsonPrimitive(-5))),
                token = CapabilityToken("valid"),
                csrfToken = CsrfToken("valid"),
            ),
        )

        assertEquals(ServerActionResponse.Failure("Invalid server action payload."), response)
    }

    @Test
    fun serverActionDispatcherFailsClosedWithoutExplicitVerifiers() = runTest {
        // A dispatcher constructed without verifiers must reject every action rather than silently
        // accept unauthenticated ones — the safe default for a public API.
        val dispatcher = KineticaServerActionDispatcher(
            stubs = listOf(
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.add",
                        functionFqName = "app.server.addToCart",
                    ),
                    inputSerializer = String.serializer(),
                    outputSerializer = String.serializer(),
                    handler = { _ -> "should not run" },
                ),
            ),
        )
        assertEquals(
            ServerActionResponse.Failure("Invalid capability token."),
            dispatcher.dispatch(
                ServerActionRequest(
                    actionId = "cart.add",
                    payload = JsonPrimitive("sku-1"),
                    token = CapabilityToken("anything"),
                    csrfToken = CsrfToken("anything"),
                ),
            ),
        )
    }

    @Test
    fun dispatchHttpMapsRequestBodyToHttpStatusCodes() = runTest {
        val transport = KineticaServerTransport()
        val dispatcher = KineticaServerActionDispatcher(
            stubs = listOf(
                serverActionStub(
                    registration = ServerActionRegistration(
                        actionId = "cart.add",
                        functionFqName = "app.server.addToCart",
                    ),
                    inputSerializer = String.serializer(),
                    outputSerializer = String.serializer(),
                    handler = { input -> "added $input" },
                ),
            ),
            verifyCapabilityToken = { true },
            verifyCsrfToken = { true },
        )

        // Oversized body (the caller's bounded read returned null) -> 413.
        val tooLarge = dispatcher.dispatchHttp(transport, body = null)
        assertEquals(413, tooLarge.status)
        assertEquals(
            ServerActionResponse.Failure("Request body too large."),
            transport.decodeActionResponse(tooLarge.body),
        )

        // Undecodable envelope -> 400 (a client error, never the 500 catch-all) with a generic message.
        val malformed = dispatcher.dispatchHttp(transport, body = "not a valid envelope {")
        assertEquals(400, malformed.status)
        assertEquals(
            ServerActionResponse.Failure("Malformed server action request."),
            transport.decodeActionResponse(malformed.body),
        )

        // A decodable, authorized request is dispatched -> 200 with the handler's success payload.
        val encoded = transport.encodeActionRequest(
            ServerActionRequest(
                actionId = "cart.add",
                payload = JsonPrimitive("sku-1"),
                token = CapabilityToken("valid"),
                csrfToken = CsrfToken("valid"),
            ),
        )
        val ok = dispatcher.dispatchHttp(transport, body = encoded)
        assertEquals(200, ok.status)
        assertIs<ServerActionResponse.Success>(transport.decodeActionResponse(ok.body))

        // A dispatched rejection (unknown action) is still HTTP 200 — the failure travels in the body.
        val unknown = transport.encodeActionRequest(
            ServerActionRequest(
                actionId = "cart.nope",
                payload = JsonPrimitive("x"),
                token = CapabilityToken("valid"),
                csrfToken = CsrfToken("valid"),
            ),
        )
        val unknownResponse = dispatcher.dispatchHttp(transport, body = unknown)
        assertEquals(200, unknownResponse.status)
        assertEquals(
            ServerActionResponse.Failure("Unknown server action."),
            transport.decodeActionResponse(unknownResponse.body),
        )
    }

    @Test
    fun semanticsTreeFocusManagerAndHeadlessRendererUseNodeAsData() {
        val node = FragmentNode(
            children = listOf(
                HostNode(
                    tag = "toolbar",
                    semantics = Semantics(role = Role.Navigation, testTag = "toolbar"),
                    children = listOf(
                        HostNode(
                            tag = "button",
                            semantics = Semantics(
                                role = Role.Button,
                                label = "Second",
                                focusable = true,
                                traversalIndex = 2,
                                testTag = "second",
                            ),
                            children = listOf(TextNode("Second")),
                        ),
                        HostNode(
                            tag = "button",
                            semantics = Semantics(
                                role = Role.Button,
                                label = "First",
                                focusable = true,
                                traversalIndex = 1,
                                testTag = "first",
                            ),
                            children = listOf(TextNode("First")),
                        ),
                    ),
                ),
            ),
        )

        val semantics = node.semanticsTree()
        assertEquals(listOf("First", "Second"), semantics.focusOrder().map { it.semantics.label })
        assertEquals(listOf(0, 1), semantics.byTestTag("first")?.path)
        assertEquals(listOf("Second", "First"), semantics.byRole(Role.Button).map { it.semantics.label })
        assertEquals(listOf(listOf(0, 1), listOf(0, 1, 0)), semantics.byLabel("First").map { it.path })
        assertEquals("First", (node.nodeAt(listOf(0, 1, 0)) as TextNode).value)
        assertEquals(null, node.nodeAt(listOf(99)))
        assertEquals(null, node.nodeAt(listOf(0, 0, 0, 0)))
        assertEquals(null, TextNode("leaf").nodeAt(listOf(0)))
        assertEquals(null, ClientRef(componentId = "client", props = JsonObject(emptyMap())).nodeAt(listOf(0)))

        val terminalSemantics = FragmentNode(
            children = listOf(
                TextNode("Label", semantics = Semantics(role = Role.Text, label = "Label")),
                TextNode("Derived label", semantics = Semantics(role = Role.Text)),
                ClientRef(
                    componentId = "client",
                    props = JsonObject(emptyMap()),
                    semantics = Semantics(testTag = "island"),
                ),
            ),
        ).semanticsTree()
        assertEquals(listOf("Label", "Derived label"), terminalSemantics.byRole(Role.Text).map { it.text })
        assertEquals(emptyList(), terminalSemantics.byLabel("Derived label").map { it.path })
        assertEquals(listOf(2), terminalSemantics.byTestTag("island")?.path)

        val focus = FocusManager(semantics)
        assertEquals("First", focus.moveNext()?.semantics?.label)
        assertEquals("Second", focus.moveNext()?.semantics?.label)
        assertEquals("First", focus.moveNext()?.semantics?.label)
        assertTrue(focus.requestFocusByTestTag("second"))
        assertEquals("Second", focus.focused?.semantics?.label)

        val renderer = HeadlessRenderer()
        val handle = renderer.mount(node) as HeadlessRenderHandle
        assertEquals("headless", renderer.name)
        assertNotNull(handle.semantics.byTestTag("toolbar"))
        renderer.update(handle, TextNode("Replaced"))
        assertEquals("Replaced", (renderer.handle(handle.id)?.node as TextNode).value)
        renderer.dispose(handle)
        assertEquals(null, renderer.handle(handle.id))
    }

    @Test
    fun textNodeDefaultSemanticsDerivesLabel() {
        val semantics = TextNode("x").semanticsTree()

        assertEquals("x", semantics.byRole(Role.Text).single().semantics.label)
        assertEquals(listOf(emptyList<Int>()), semantics.byLabel("x").map { it.path })
    }

    @Test
    fun explicitTextSemanticsDoesNotDeriveLabelInSemanticsTree() {
        val semantics = TextNode("Save", semantics = Semantics(role = Role.Text)).semanticsTree()

        assertEquals(listOf("Save"), semantics.byRole(Role.Text).map { it.text })
        assertEquals(listOf(null), semantics.byRole(Role.Text).map { it.semantics.label })
        assertEquals(emptyList(), semantics.byLabel("Save").map { it.path })
    }

    @Test
    fun focusManagerHandlesDirectRequestsUpdatesEmptyTreesAndReverseTraversal() {
        val focus = FocusManager()

        assertEquals(null, focus.focusedPath)
        assertEquals(null, focus.focused)
        assertEquals(null, focus.moveNext())
        assertEquals(null, focus.focusedPath)

        val tree = SemanticsTree(
            listOf(
                SemanticsNode(
                    path = listOf(0),
                    semantics = Semantics(
                        role = Role.Button,
                        label = "Late",
                        focusable = true,
                        traversalIndex = 2,
                        testTag = "late",
                    ),
                    hostTag = "button",
                ),
                SemanticsNode(
                    path = listOf(1),
                    semantics = Semantics(
                        role = Role.Text,
                        label = "Static",
                        focusable = false,
                        testTag = "static",
                    ),
                    text = "Static",
                ),
                SemanticsNode(
                    path = listOf(2),
                    semantics = Semantics(
                        role = Role.Button,
                        label = "Early",
                        focusable = true,
                        traversalIndex = 1,
                        testTag = "early",
                    ),
                    hostTag = "button",
                ),
                SemanticsNode(
                    path = listOf(3),
                    semantics = Semantics(
                        role = Role.Button,
                        label = "No index",
                        focusable = true,
                        testTag = "no-index",
                    ),
                    hostTag = "button",
                ),
            ),
        )

        focus.update(tree)
        assertTrue(focus.requestFocus(listOf(0)))
        assertEquals(listOf(0), focus.focusedPath)
        assertEquals("Late", focus.focused?.semantics?.label)
        focus.update(tree)
        assertEquals(listOf(0), focus.focusedPath)

        assertFalse(focus.requestFocus(listOf(1)))
        assertFalse(focus.requestFocus(listOf(99)))
        assertFalse(focus.requestFocusByTestTag("static"))
        assertFalse(focus.requestFocusByTestTag("missing"))

        assertTrue(focus.requestFocusByTestTag("early"))
        assertEquals(listOf(3), focus.movePrevious()?.path)
        assertEquals(listOf(0), focus.movePrevious()?.path)

        val reverseFromNoFocus = FocusManager(tree)
        assertEquals(listOf(3), reverseFromNoFocus.movePrevious()?.path)
        reverseFromNoFocus.update(
            SemanticsTree(
                listOf(
                    SemanticsNode(
                        path = listOf(3),
                        semantics = Semantics(
                            role = Role.Button,
                            label = "No index",
                            focusable = false,
                            testTag = "no-index",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(null, reverseFromNoFocus.focusedPath)
        assertEquals(null, reverseFromNoFocus.focused)
        assertEquals(null, reverseFromNoFocus.moveNext())
    }

    @Test
    fun asLeavingCoversNullSemanticsFragmentsAndClientRefs() {
        val node = FragmentNode(
            semantics = null,
            children = listOf(
                HostNode(
                    tag = "button",
                    props = mapOf("event:onClick" to "event-1", "id" to "close"),
                    semantics = null,
                    children = listOf(
                        TextNode("Close", semantics = null),
                        ClientRef("app.Client"),
                    ),
                ),
            ),
        )

        val leaving = assertIs<FragmentNode>(node.asLeaving())
        val host = assertIs<HostNode>(leaving.children.single())
        val text = assertIs<TextNode>(host.children[0])
        val client = assertIs<ClientRef>(host.children[1])

        assertEquals(null, leaving.semantics)
        assertEquals(mapOf("id" to "close"), host.props)
        assertEquals(true, host.semantics?.leaving)
        assertEquals(true, text.semantics?.leaving)
        assertEquals(true, client.semantics?.leaving)
    }
}

private fun serverBoundaryTemplateNode(
    text: String,
    key: String = "wire-row",
): TemplateNode =
    templateNode(
        definition = TemplateDefinition(
            id = "server-boundary-template",
            skeleton = HostNode(
                tag = "article",
                props = propsOf("class", "pending", "data-sentinel", "skeleton"),
                children = listOf(
                    HostNode(
                        tag = "span",
                        children = listOf(TextNode("", semantics = null)),
                    ),
                    TextNode("static-skeleton-marker", semantics = null),
                ),
            ),
            holes = listOf(
                TemplateHole(path = "", kind = TemplateHoleKinds.Prop, propName = "class"),
                TemplateHole(path = "0.0", kind = TemplateHoleKinds.Text),
            ),
        ),
        values = listOf("ready", text),
        key = key,
    )

private fun Node.containsTemplateNode(): Boolean =
    when (this) {
        is FragmentNode -> children.any { child -> child.containsTemplateNode() }
        is HostNode -> children.any { child -> child.containsTemplateNode() }
        is TemplateNode -> true
        is TextNode -> false
        is ClientRef -> false
    }

private fun String.countOccurrences(needle: String): Int =
    if (needle.isEmpty()) 0 else split(needle).size - 1

@Serializable
private data class SmokeCartQuantityDraft(
    val productId: String,
    val quantity: Int,
)

@Serializable
private data class SmokeOptionalCartPatch(
    val productId: String,
    val quantity: Int = 1,
    val note: String? = null,
)

@Serializable
private enum class SmokeCartStatus {
    Pending,
    Done,
}

@Serializable
private data class SmokeCartStatusPatch(
    val productId: String,
    val status: SmokeCartStatus,
)

@Serializable
private data class InitGuardedInput(val n: Int) {
    init {
        require(n in 0..100) { "n out of range" }
    }
}
