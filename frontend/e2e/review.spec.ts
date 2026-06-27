import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { AUTH_PORT } from "../playwright.config";

/**
 * P4 review-queue E2E against the REAL server in ENFORCED builtin mode (the ci-runs-auth-off-blind lesson).
 * This spec runs in the "auth" Playwright project, bound to the seeded builtin server (playwright.config.ts);
 * `smoke-server.mjs` mints a bootstrap + agent token into `.smoke-data-<AUTH_PORT>/seed.json` BEFORE serving.
 *
 * One end-to-end flow (the bootstrap token is single-use, so the whole flow shares one login):
 *  1. consume the bootstrap → the first ADMIN, logged into the browser context.
 *  2. as the agent (Bearer pb_…) propose an EDIT.
 *  3. as the reviewer (browser) open /review, see the diff, Approve → the proposal applies (APPLIED).
 *  4. drift a second proposal's page out-of-band → its detail shows the banner and approve is disabled.
 */

const seedPath = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", `.smoke-data-${AUTH_PORT}`, "seed.json");

function seed(): { setupToken: string; agentToken: string } {
  return JSON.parse(readFileSync(seedPath, "utf8"));
}

test("agent proposes → reviewer approves → applied; a drifted proposal blocks approval", async ({ page, request }) => {
  const { setupToken, agentToken } = seed();
  const agentAuth = { Authorization: `Bearer ${agentToken}` };
  const stamp = Date.now();

  // 1. Create the first admin (single-use bootstrap) — page.request shares the browser context's cookies, so
  // the browser is now logged in. The 201 carries the CSRF token for our out-of-band admin PUT below.
  const consume = await page.request.post("/api/v1/setup/consume", {
    data: { token: setupToken, username: "smoke-admin", password: "smoke-pass-1234" },
  });
  expect(consume.status()).toBe(201);
  const { csrf_token: csrf } = (await consume.json()) as { csrf_token: string };

  // 2. As the agent: read a fixture page, then propose an edit against its current hash.
  const readA = await request.get("/api/v1/pages/by-path/guides/deploy-guide", { headers: agentAuth });
  expect(readA.ok()).toBe(true);
  const pageA = (await readA.json()) as { id: string; content_hash: string; markdown: string };
  const markerA = `proposed-by-agent-${stamp}`;
  const proposeA = await request.post("/api/v1/changes", {
    headers: { ...agentAuth, "content-type": "application/json" },
    data: { operation: "edit", page_id: pageA.id, base_hash: pageA.content_hash, proposed_content: `${pageA.markdown}\n\n${markerA}\n`, rationale: "e2e: tighten the guide" },
  });
  expect(proposeA.status()).toBe(201);
  const changeA = ((await proposeA.json()) as { id: string }).id;

  // 3. As the reviewer (browser): the queue lists the pending change; open it, see the diff, approve.
  await page.goto("/review");
  await expect(page.locator("[data-pb-review-nav]")).toBeVisible(); // the session-gated chrome link
  await expect(page.locator(`[data-pb-review-row]`).first()).toBeVisible();

  await page.goto(`/review/${changeA}`);
  await expect(page.locator("[data-pb-diff]")).toBeVisible();
  await expect(page.locator("[data-pb-review-rationale]")).toContainText("tighten the guide");
  const approve = page.locator("[data-pb-review-approve]");
  await expect(approve).toBeEnabled();
  await approve.click();
  // The apply lands: the detail refetches to the APPLIED state.
  await expect(page.locator('[data-pb-review-status="APPLIED"]')).toBeVisible();

  // 4. Drift scenario: propose against a SECOND page, then mutate that page out-of-band so the PENDING
  // proposal's base goes stale → the detail must show the banner and disable approval.
  const readB = await request.get("/api/v1/pages/by-path/guides/editor", { headers: agentAuth });
  expect(readB.ok()).toBe(true);
  const pageB = (await readB.json()) as { id: string; content_hash: string; markdown: string };
  const proposeB = await request.post("/api/v1/changes", {
    headers: { ...agentAuth, "content-type": "application/json" },
    data: { operation: "edit", page_id: pageB.id, base_hash: pageB.content_hash, proposed_content: `${pageB.markdown}\n\nproposal-b-${stamp}\n`, rationale: "e2e: edit the editor doc" },
  });
  expect(proposeB.status()).toBe(201);
  const changeB = ((await proposeB.json()) as { id: string }).id;

  // Out-of-band admin edit (cookie + CSRF) drifts page B from under the proposal.
  const drift = await page.request.put(`/api/v1/pages/${pageB.id}`, {
    headers: { "content-type": "text/markdown", "if-match": `"${pageB.content_hash}"`, "X-CSRF-Token": csrf },
    data: `${pageB.markdown}\n\ndrifted-out-of-band-${stamp}\n`,
  });
  expect(drift.ok()).toBe(true);

  await page.goto(`/review/${changeB}`);
  await expect(page.locator("[data-pb-review-drift-banner]")).toBeVisible();
  await expect(page.locator("[data-pb-review-approve]")).toBeDisabled();
});
