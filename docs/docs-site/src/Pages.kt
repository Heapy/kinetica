package docs.site

data class DocPage(
    val slug: String,
    val title: String,
    val section: String,
)

/** Order defines the sidebar. Content lives in resources/docs/<slug>.md. */
val DocPages: List<DocPage> = listOf(
    DocPage("index", "Kinetica", "Start"),
    DocPage("getting-started", "Getting started", "Start"),

    DocPage("game-of-life", "Game of Life comparison", "Examples"),

    DocPage("state", "State & reactivity", "Core"),
    DocPage("ui-dsl", "UI DSL & semantics", "Core"),
    DocPage("lists-and-keys", "Lists & keys", "Core"),
    DocPage("effects", "Effects & lifecycle", "Core"),
    DocPage("resources", "Resources & boundaries", "Core"),

    DocPage("router", "Router & navigation", "Batteries"),
    DocPage("forms", "Forms", "Batteries"),
    DocPage("motion", "Motion", "Batteries"),
    DocPage("data", "Data & offline", "Batteries"),
    DocPage("persist", "Persistence", "Batteries"),
    DocPage("markdown", "Markdown", "Batteries"),

    DocPage("server-components", "Server components", "Server"),

    DocPage("browser-renderer", "Browser renderer", "Under the hood"),
    DocPage("performance", "Performance", "Under the hood"),
    DocPage("compiler-plugin", "Compiler plugin", "Under the hood"),
    DocPage("testing", "Testing", "Under the hood"),
)

fun pageBySlug(slug: String): DocPage? = DocPages.firstOrNull { it.slug == slug }

fun loadPageSource(slug: String): String? =
    DocPage::class.java.getResourceAsStream("/docs/$slug.md")
        ?.readAllBytes()
        ?.decodeToString()
