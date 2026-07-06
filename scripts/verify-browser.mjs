const baseUrl = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
const serverComponentsUrl = process.env.KINETICA_SERVER_COMPONENTS_URL;
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE;
const playwrightImport = process.env.PLAYWRIGHT_IMPORT ?? "playwright";
const { chromium } = await import(playwrightImport);

const browser = await chromium.launch({
  headless: true,
  executablePath,
});

try {
  await verifyBrowserTests();
  await verifyCounter();
  await verifyTodo();
  if (serverComponentsUrl) {
    await verifyServerComponents();
  }
  console.log("Browser verification passed");
} finally {
  await browser.close();
}

async function verifyBrowserTests() {
  const expectedBrowserSelfTests = 15;
  const page = await newPage("browser-tests");
  await page.goto(`${baseUrl}/samples/browser-tests/web/index.html`, { waitUntil: "domcontentloaded" });
  await page.waitForFunction(
    (expected) => {
      const statuses = [...document.querySelectorAll("#results [data-status]")].map((node) =>
        node.getAttribute("data-status"),
      );
      return statuses.includes("fail") ||
        (statuses.length === expected && statuses.every((status) => status !== "pending"));
    },
    expectedBrowserSelfTests,
    { timeout: 5_000 },
  );
  const statuses = await page.$$eval("#results [data-status]", (nodes) =>
    nodes.map((node) => ({
      status: node.getAttribute("data-status"),
      text: node.textContent,
    })),
  );
  if (statuses.length !== expectedBrowserSelfTests || statuses.some((entry) => entry.status !== "pass")) {
    throw new Error(`Browser self-tests failed: ${JSON.stringify(statuses)}`);
  }
  assertNoPageErrors(page, "browser-tests");
  await page.close();
}

async function verifyCounter() {
  const page = await newPage("browser-counter");
  await page.goto(`${baseUrl}/samples/browser-counter/web/index.html`, { waitUntil: "networkidle" });
  await page.getByText("Count: 0").waitFor({ timeout: 5_000 });
  await page.locator('[data-testid="increment"]').click();
  await page.getByText("Count: 1").waitFor({ timeout: 5_000 });
  await page.locator('[data-testid="reset"]').click();
  await page.getByText("Count: 0").waitFor({ timeout: 5_000 });
  assertNoPageErrors(page, "browser-counter");
  await page.close();
}

async function verifyTodo() {
  const page = await newPage("browser-todo");
  await page.goto(`${baseUrl}/samples/browser-todo/web/index.html`, { waitUntil: "networkidle" });
  await page.locator('[data-testid="remove-todo-1"]').click();
  await page.locator('[data-testid="new-todo"]').fill("Avoid duplicate ids");
  await page.locator('[data-testid="add"]').click();
  await page.getByText("Avoid duplicate ids").waitFor({ timeout: 5_000 });
  await page.locator('[data-testid="new-todo"]').fill("Ship browser renderer");
  await page.locator('[data-testid="add"]').click();
  await page.getByText("Ship browser renderer").waitFor({ timeout: 5_000 });
  await page.locator('[data-testid="toggle-todo-4"]').click();
  await page.locator('[data-testid="filter-Done"]').click();
  await page.getByText("Ship browser renderer").waitFor({ timeout: 5_000 });
  assertNoPageErrors(page, "browser-todo");
  await page.close();
}

async function verifyServerComponents() {
  const page = await newPage("server-components");
  await page.goto(serverComponentsUrl, { waitUntil: "networkidle" });
  await page.getByText("Server-rendered product page").waitFor({ timeout: 5_000 });
  await page.getByText("Kinetica runtime license").waitFor({ timeout: 5_000 });
  await page.locator('[data-testid="add-to-cart"]').waitFor({ timeout: 5_000 });
  await page.getByText("Hydration plan loaded: 1 client island").waitFor({ timeout: 5_000 });
  await page.waitForFunction(() =>
    document.querySelector("#recommendations")?.textContent?.includes("Deferred recommendations loaded on the server"),
  );
  await page.locator('[data-testid="quantity-increase"]').click();
  await page.locator('[data-testid="add-to-cart"]').click();
  await page.getByText(/Added 2 of runtime-license\. Cart count: \d+/).waitFor({ timeout: 5_000 });
  assertNoPageErrors(page, "server-components");
  await page.close();
}

async function newPage(name) {
  const page = await browser.newPage();
  const consoleMessages = [];
  const pageErrors = [];
  page.on("console", (message) => {
    if (message.type() === "error") {
      consoleMessages.push(`${message.type()}: ${message.text()}`);
    }
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.consoleMessages = consoleMessages;
  page.pageErrors = pageErrors;
  return page;
}

function assertNoPageErrors(page, name) {
  if (page.consoleMessages.length > 0 || page.pageErrors.length > 0) {
    throw new Error(
      `${name} browser errors: ${JSON.stringify({
        consoleMessages: page.consoleMessages,
        pageErrors: page.pageErrors,
      })}`,
    );
  }
}
