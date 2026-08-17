package seo.site

/** Stand-in for a CMS or database: the content the server renders and search engines index. */
data class Article(
    val slug: String,
    val title: String,
    val navLabel: String,
    val description: String,
    val published: String,
    val tags: List<String>,
    val body: List<String>,
    val helpful: Int,
)

val Articles: List<Article> = listOf(
    Article(
        slug = "server-rendering",
        title = "Server rendering is the whole SEO story",
        navLabel = "Server rendering",
        description = "Why the first HTML response decides how a page is indexed, and what a crawler " +
            "actually does with JavaScript.",
        published = "2026-08-01",
        tags = listOf("seo", "ssr"),
        body = listOf(
            "A crawler asks for a URL and gets bytes back. Whatever is in those bytes is what gets " +
                "parsed, ranked and shown as a snippet. Everything a client-side framework adds later " +
                "is a second, optional pass that only some crawlers ever run.",
            "Googlebot does render JavaScript, but on a deferred queue: the render can lag the crawl by " +
                "hours or days, and it is dropped entirely when the page errors, times out, or hides its " +
                "content behind an interaction. Bing, most social-media unfurlers and the answer engines " +
                "reading pages for LLMs are far less generous — many read the initial HTML and nothing else.",
            "So the rule is short: put the content in the first response. Kinetica does that by making " +
                "the UI a value — the same component tree that runs in the browser renders to a Node tree " +
                "on the JVM, and that tree serialises straight to HTML.",
        ),
        helpful = 128,
    ),
    Article(
        slug = "islands-not-user-agents",
        title = "Islands, not user-agent sniffing",
        navLabel = "Islands",
        description = "Serving separate HTML to bots is a deprecated workaround. Serve everyone the " +
            "same server-rendered page and hydrate the interactive parts.",
        published = "2026-08-06",
        tags = listOf("seo", "hydration", "architecture"),
        body = listOf(
            "Dynamic rendering — detecting a bot by user agent and handing it a pre-rendered page — was " +
                "only ever a workaround, and Google has recommended against it for years. It doubles the " +
                "code paths, drifts out of sync, and looks exactly like cloaking when it goes wrong.",
            "The replacement is boring and better: render the same HTML for everybody, then hydrate. " +
                "Interactive widgets become islands — small mount points in an otherwise static document. " +
                "The page is complete before a single byte of the bundle arrives.",
            "This page is that architecture. The article you are reading is HTML from the JVM. The " +
                "feedback widget below is a Kinetica app the browser mounts into a container that already " +
                "carries its server-rendered markup.",
        ),
        helpful = 74,
    ),
    Article(
        slug = "the-boring-checklist",
        title = "The boring SEO checklist that still matters",
        navLabel = "Checklist",
        description = "Titles, descriptions, canonicals, structured data, status codes, sitemaps — the " +
            "unglamorous half of technical SEO.",
        published = "2026-08-12",
        tags = listOf("seo", "checklist"),
        body = listOf(
            "Server rendering gets the content in. The rest is metadata discipline: one <h1> per page, a " +
                "unique <title> and meta description, a canonical URL, Open Graph tags for link previews, " +
                "and JSON-LD so the page can be understood as a thing rather than a blob of text.",
            "Status codes are content too. A missing page must answer 404, not 200 with an apology, or the " +
                "empty page competes with the real ones in the index. Redirects must be 301 when permanent.",
            "Finally: robots.txt, a sitemap listing canonical URLs with last-modified dates, and cache " +
                "headers that let a revalidating crawler get a cheap 304. All of it is on this server, " +
                "in about two hundred lines of Kotlin.",
        ),
        helpful = 41,
    ),
)

fun articleBySlug(slug: String): Article? =
    Articles.firstOrNull { article -> article.slug == slug }
