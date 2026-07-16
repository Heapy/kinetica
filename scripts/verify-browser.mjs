const baseUrl = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
const serverComponentsUrl = process.env.KINETICA_SERVER_COMPONENTS_URL;
const gameOfLifeOnly = process.argv.includes("--game-of-life-only");
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE;
const playwrightImport = process.env.PLAYWRIGHT_IMPORT ?? "playwright";
const { chromium } = await import(playwrightImport);

const browser = await chromium.launch({
  headless: true,
  executablePath,
});

try {
  if (!gameOfLifeOnly) {
    await verifyBrowserTests();
    await verifyCounter();
  }
  await verifyGameOfLife();
  if (!gameOfLifeOnly) {
    await verifyTodo();
    if (serverComponentsUrl) {
      await verifyServerComponents();
    }
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

async function verifyGameOfLife() {
  const implementations = ["kinetica", "react", "compose-html", "vanilla"];
  for (const implementation of implementations) {
    await verifyGameOfLifeImplementation(implementation);
  }
}

async function verifyGameOfLifeImplementation(implementation) {
  const name = `browser-game-of-life-${implementation}`;
  const page = await newPage(name);
  await page.goto(
    `${baseUrl}/build/tasks/_game-of-life_dist/${implementation}/index.html`,
    { waitUntil: "networkidle" },
  );
  await page.locator('[data-testid="life-grid"]').waitFor({ timeout: 5_000 });
  await page.getByText("72 × 48 universe", { exact: true }).waitFor({ timeout: 5_000 });

  const cellCount = await page.locator(".life-cell").count();
  if (cellCount !== 72 * 48) {
    throw new Error(`Game of Life rendered ${cellCount} cells instead of 3456`);
  }
  const scrollPosition = await page.locator(".board-scroll").evaluate((viewport) => ({
    actual: viewport.scrollLeft,
    centered: (viewport.scrollWidth - viewport.clientWidth) / 2,
  }));
  if (Math.abs(scrollPosition.actual - scrollPosition.centered) > 2) {
    throw new Error(`Game of Life board was not centered: ${JSON.stringify(scrollPosition)}`);
  }

  await page.locator('[data-testid="preset-glider"]').click();
  await waitForText(page, "population-value", "5");
  await waitForText(page, "generation-value", "0");

  await page.locator('[data-testid="step"]').click();
  await waitForText(page, "generation-value", "1");

  const editableCell = page.locator('[data-testid="cell-0-0"]');
  if (await editableCell.getAttribute("aria-pressed") !== "false") {
    throw new Error("Expected the corner cell to start dead");
  }
  await editableCell.click();
  await page.waitForFunction(
    () => document.querySelector('[data-testid="cell-0-0"]')?.getAttribute("aria-pressed") === "true",
    undefined,
    { timeout: 5_000 },
  );
  if (await editableCell.getAttribute("aria-pressed") !== "true") {
    throw new Error("Clicking a dead cell did not make it alive");
  }

  await page.locator('[data-testid="preset-beacon"]').click();
  await page.locator('[data-testid="speed-fast"]').click();
  await page.locator('[data-testid="toggle-running"]').click();
  await page.waitForFunction(
    () => Number(document.querySelector('[data-testid="generation-value"]')?.textContent ?? "0") >= 2,
    undefined,
    { timeout: 5_000 },
  );
  await page.locator('[data-testid="toggle-running"]').click();
  await page.getByText("Paused", { exact: true }).waitFor({ timeout: 5_000 });

  assertNoPageErrors(page, name);
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

async function waitForText(page, testTag, expected) {
  await page.waitForFunction(
    ({ testTag, expected }) => document.querySelector(`[data-testid="${testTag}"]`)?.textContent === expected,
    { testTag, expected },
    { timeout: 5_000 },
  );
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
