import { createHmac, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { type APIRequestContext, expect } from "@playwright/test";
export const backend = "http://localhost:8080";
export const control = "http://localhost:8090";
// Throwaway synthetic credential, shared with StripeSignatures.SECRET.
const secret = "whsec_test_only";
export async function fixture<T>(scenario: string, file: string): Promise<T> {
  return JSON.parse(
    await readFile(
      new URL(
        `../../src/test/resources/scenarios/${scenario}/${file}`,
        import.meta.url,
      ),
      "utf8",
    ),
  );
}
export async function deliver(request: APIRequestContext, body: string) {
  const clock = await request.get(`${control}/clock`);
  expect(clock.ok()).toBeTruthy();
  const timestamp = Number(await clock.text());
  const signature = createHmac("sha256", secret)
    .update(`${timestamp}.${body}`)
    .digest("hex");
  const response = await request.post(`${backend}/webhooks/stripe`, {
    data: body,
    headers: {
      "Content-Type": "application/json",
      "Stripe-Signature": `t=${timestamp},v1=${signature}`,
    },
  });
  expect(response.status(), await response.text()).toBe(200);
  const drained = await request.post(`${control}/drain`);
  expect(drained.status(), await drained.text()).toBe(200);
}
/** AC-2.16 demo: same B1 confirmation content, fresh event_id, real signed delivery. */
export async function replayB1Confirmation(request: APIRequestContext) {
  const event = await fixture<{ id: string }>(
    "sample-a-1-3",
    "events/003.json",
  );
  event.id = `evt_e2e_${randomUUID().replaceAll("-", "")}`;
  const response = await request.post(`${control}/prepare-b1-replay`);
  expect(response.status()).toBe(200);
  await deliver(request, JSON.stringify(event));
  return event.id;
}
