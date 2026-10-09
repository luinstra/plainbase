import { readFileSync } from "node:fs";
import path from "node:path";
import { test, expect } from "./smoke-fixtures";

const enabledPage = "01970000-0000-7000-8000-000000000901";
const disabledPage = "01970000-0000-7000-8000-000000000902";
const enabledId = "01970000-0000-7000-8000-000000000911";
const disabledId = "01970000-0000-7000-8000-000000000912";
const disabledComment = "01970000-0000-7000-8000-000000000922";

test("disabled page keeps ordinary content and rails without discussion requests", async ({ page, request }) => {
  const calls: string[] = [];
  page.on("request", (call) => { if (/\/api\/v1\/.*discussions/.test(call.url())) calls.push(call.url()); });
  // Positive control: an enabled workspace really refreshes on this event, after its initial read.
  await page.goto(`/p/docs/${enabledPage}`);
  await expect(page.locator(".pb-margin-discussions")).toBeVisible();
  await expect(page.getByRole("button", { name: "Start a discussion" })).toBeVisible();
  const refreshed = page.waitForResponse((response) => response.url().includes(`/pages/${enabledPage}/discussions?root=docs`));
  await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
  expect((await refreshed).ok()).toBe(true);
  calls.length = 0;
  await page.goto(`/p/extra/${disabledPage}`);
  await expect(page.locator("article")).toContainText("Ordinary extra content.");
  await expect(page.locator("[data-pb-toc]")).toBeVisible();
  await expect(page.locator("[data-pb-rail-meta]")).toContainText("discussion-config.md");
  await expect(page.getByRole("link", { name: "Edit this page" })).toBeVisible();
  await expect(page.locator(".pb-margin-discussions")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Start a discussion" })).toHaveCount(0);
  await page.evaluate(async () => {
    document.dispatchEvent(new Event("visibilitychange"));
    // Cross a browser task boundary and complete a real request before inspecting the negative result.
    await new Promise<void>((resolve) => {
      const channel = new MessageChannel();
      channel.port1.onmessage = () => { channel.port1.close(); channel.port2.close(); resolve(); };
      channel.port2.postMessage(null);
    });
    await fetch("/healthz");
  });
  expect(calls).toEqual([]);
  const tree = await (await request.get("/api/v1/tree")).json();
  expect(tree.roots.find((entry: { root: string }) => entry.root === "extra").discussionsEnabled).toBe(false);
  expect(tree.roots.find((entry: { root: string }) => entry.root === "docs").discussionsEnabled).toBeUndefined();
  await page.goto(`/discussions/docs/${enabledId}`);
  await expect(page.getByText("Preserved docs comment.", { exact: true })).toBeVisible();
});

test("chooser omits disabled root and direct discussion URLs show only notice and navigation", async ({ page }) => {
  await page.goto("/discussions");
  await expect(page.locator(".pb-discussion-list").getByRole("link", { name: "docs", exact: true })).toBeVisible();
  await expect(page.locator(".pb-discussion-list").getByRole("link", { name: "extra", exact: true })).toHaveCount(0);
  for (const url of ["/discussions/extra", `/discussions/extra/${disabledId}`]) {
    await page.goto(url);
    await expect(page.getByText("Discussions are disabled for this root", { exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "All roots" })).toBeVisible();
    await expect(page.getByText("Preserved extra comment.", { exact: true })).toHaveCount(0);
    await expect(page.getByLabel("Match state")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reply", exact: true })).toHaveCount(0);
  }
});

test("real REST wiring denies all known-root writes and preserves seeded disabled files", async ({ request, smokeServer }) => {
  const directory = path.join(smokeServer.extraDir!, ".plainbase", "discussions", disabledId);
  const markerBefore = readFileSync(path.join(directory, "discussion.md"));
  const commentBefore = readFileSync(path.join(directory, `${disabledComment}.md`));
  const detail = await request.get(`/api/v1/discussions/${disabledId}?root=extra`);
  expect(detail.status()).toBe(200);
  expect(await detail.json()).toEqual({ discussion: null, comments: [], next: null, discussions_available: false, reason: "disabled_by_config" });
  const source = await (await request.get(`/api/v1/pages/${disabledPage}/html?root=extra`)).json();
  const quote = { kind: "quote", content_hash: source.content_hash, selected_text: "Ordinary" };
  const preview = await request.post(`/api/v1/pages/${disabledPage}/discussions/anchor-preview?root=extra`, { data: quote });
  expect(preview.status()).toBe(403);
  expect((await preview.json()).error.code).toBe("discussions_disabled");
  const writes: Array<[string, unknown]> = [
    [`/pages/${disabledPage}/discussions`, { anchor: { kind: "page", content_hash: source.content_hash }, body: "Start" }],
    [`/discussions/${disabledId}/comments`, { body: "Reply" }],
    [`/discussions/${disabledId}/comments/${disabledComment}/edit`, { body: "Edit" }],
    [`/discussions/${disabledId}/comments/${disabledComment}/retract`, {}],
    [`/discussions/${disabledId}/resolve`, {}],
    [`/discussions/${disabledId}/reopen`, {}],
    [`/discussions/${disabledId}/reattach`, { anchor: quote }],
    [`/discussions/${disabledId}/comments/${disabledComment}/purge`, {}],
  ];
  for (const [url, data] of writes) {
    const response = await request.post(`/api/v1${url}?root=extra`, { data });
    expect(response.status(), url).toBe(403);
    expect((await response.json()).error.code, url).toBe("discussions_disabled");
  }
  expect((await request.get(`/api/v1/discussions/${disabledId}`)).status()).toBe(404);
  expect(readFileSync(path.join(directory, "discussion.md"))).toEqual(markerBefore);
  expect(readFileSync(path.join(directory, `${disabledComment}.md`))).toEqual(commentBefore);
});
