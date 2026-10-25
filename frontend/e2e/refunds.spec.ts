import { expect, test } from "@playwright/test";
import type { Overview, PaymentDetail } from "../src/api";
import { backend, fixture } from "./helpers";

const scenario = process.env.LEDGERLAB_E2E_SCENARIO;

test(
  scenario === "refund-retry"
    ? "ac_4_12 committed refund survives lost response and same-key retry"
    : `ac_3_15 concurrent refunds ${scenario?.endsWith("5000") ? "5000" : "6000"} show money and outcomes`,
  async ({ page, request }) => {
    const truth = await fixture<{ aliases: { P1: string } }>(
      "sample-a-1-3",
      "truth.json",
    );
    const payment = truth.aliases.P1;
    await page.goto(`/#/payments/${payment}`);
    await expect(
      page.getByRole("table", { name: "退款列表（0 条）" }),
    ).toBeVisible();
    const submitted: {
      key: string | undefined;
      amount: number;
      merchant_refund_ref: string;
    }[] = [];
    page.on("request", (r) => {
      if (
        r.method() === "POST" &&
        (r.url().endsWith("/api/refunds") ||
          r.url().endsWith("/__e2e__/refund-response-loss"))
      ) {
        const body = r.postDataJSON();
        submitted.push({
          key: r.headers()["idempotency-key"],
          amount: body.amount,
          merchant_refund_ref: body.merchant_refund_ref,
        });
      }
    });
    if (scenario === "refund-retry") {
      await page.getByRole("button", { name: "模拟响应丢失后重试" }).click();
      const logs = page.getByRole("list", { name: "故障重试日志" });
      await expect(logs.getByRole("listitem")).toHaveCount(2);
      await expect(logs).toContainText("第一次失败");
      await expect(logs).toContainText("第二次返回原结果（同一退款号）");
      await expect(
        page
          .getByRole("table", { name: "退款列表（1 条）" })
          .locator("tbody tr"),
      ).toHaveCount(1);
      expect(submitted).toHaveLength(2);
      expect(submitted[0]).toEqual(submitted[1]);
      expect(submitted[0].amount).toBe(2000);
      const p: PaymentDetail = await (
        await request.get(`${backend}/api/payments/${payment}`)
      ).json();
      expect(p.refunds).toHaveLength(1);
      expect(p.refundable_remaining).toBe(8000);
      expect(
        p.postings.filter((x) => x.entryType === "REFUND_ACCEPTED"),
      ).toHaveLength(2);
      await expect(logs).toContainText(p.refunds[0].refund_id);
      const postings = page.getByRole("table", {
        name: "账本 posting（11 条）",
      });
      await expect(
        postings.locator("tbody tr").filter({ hasText: "REFUND_ACCEPTED" }),
      ).toHaveCount(2);
      await expect(postings).toContainText("CAD 20.00");
      await page
        .getByRole("link", { name: p.refunds[0].refund_id, exact: true })
        .click();
      await expect(
        page.getByRole("heading", { name: "退款详情" }),
      ).toBeVisible();
      await expect(
        page.getByRole("table", { name: "退款状态历史" }),
      ).toContainText("API");
      await expect(
        page.getByRole("table", { name: "退款状态历史" }),
      ).toContainText("QUEUE");
      await expect(
        page.getByRole("table", { name: "出站命令", exact: true }),
      ).toContainText("PENDING");
      await expect(
        page.getByRole("table", { name: "账本 posting（2 条）" }),
      ).toContainText("2103");
      await page.goto("/#/");
      await expect(page.locator(".balances")).toContainText(
        "商户可用CAD 76.80",
      );
      await expect(page.locator(".balances")).toContainText(
        "退款待付CAD 20.00",
      );
    } else {
      const five = scenario === "refund-concurrent-5000";
      await page
        .getByLabel("每笔并发退款金额（CAD 元）")
        .fill(five ? "50.00" : "60.00");
      await page.getByRole("button", { name: "同时发出两笔退款" }).click();
      const logs = page.getByRole("list", { name: "并发请求日志" });
      await expect(logs.getByRole("listitem")).toHaveCount(2);
      expect(submitted).toHaveLength(2);
      expect(submitted[0].key).toBeTruthy();
      expect(submitted[0].key).not.toBe(submitted[1].key);
      expect(submitted[0].merchant_refund_ref).not.toBe(
        submitted[1].merchant_refund_ref,
      );
      if (five) {
        await expect(
          logs.getByRole("listitem").filter({ hasText: "201" }),
        ).toHaveCount(2);
        await expect(
          page.getByRole("table", { name: "退款列表（2 条）" }),
        ).toContainText("等待资金 · 排队第 1 位");
      } else {
        await expect(logs).toContainText("201");
        await expect(logs).toContainText(
          "409 REFUND_EXCEEDS_REMAINING：超过可退余额",
        );
        await expect(
          page.getByRole("table", { name: "退款列表（1 条）" }),
        ).toBeVisible();
      }
      const overview: Overview = await (
        await request.get(`${backend}/api/overview`)
      ).json();
      expect(overview.merchantAvailable).toBe(five ? -320 : 3680);
      expect(overview.merchant_debt).toBe(five ? 320 : 0);
      expect(overview.awaiting_funds_count).toBe(five ? 1 : 0);
      expect(overview.awaiting_funds_amount).toBe(five ? 5000 : 0);
      await page.goto("/#/");
      await expect(page.locator(".balances")).toContainText(
        five ? "商户欠款CAD 3.20" : "商户可用CAD 36.80",
      );
      await expect(page.locator(".balances")).not.toContainText("CAD -");
      await expect(page.locator(".balances")).toContainText(
        five ? "等待资金合计CAD 50.00" : "等待资金合计CAD 0.00",
      );
      await page.goto(`/#/payments/${payment}`);
      if (five) {
        const form = page.getByRole("form", { name: /^取消退款/ });
        await form.getByRole("button", { name: "取消（需填写原因）" }).click();
        await expect(form.getByRole("alert")).toHaveText("取消必须填写原因");
        await form.getByLabel("取消原因").fill("顾客撤回申请");
        await form.getByRole("button", { name: "取消（需填写原因）" }).click();
        await expect(
          page.getByRole("table", { name: "退款列表（2 条）" }),
        ).toContainText("已取消");
        await expect(page.getByText("剩余可退：")).toContainText("CAD 50.00");
      } else {
        // Real normal-form submission, with its first committed response dropped at the browser boundary.
        const form = page.getByRole("form", { name: "发起退款", exact: true });
        await form
          .getByLabel("退款金额（CAD 元）", { exact: true })
          .fill("100.5");
        await expect(form.getByRole("alert")).toContainText(
          "100.5 请写为 100.50",
        );
        await form
          .getByRole("button", { name: "提交退款", exact: true })
          .click();
        expect(submitted).toHaveLength(2);
        await form
          .getByLabel("退款金额（CAD 元）", { exact: true })
          .fill("20.00");
        await page.route(
          "**/api/refunds",
          async (route) => {
            const committed = await route.fetch();
            expect(committed.status()).toBe(201);
            await route.abort("connectionreset");
          },
          { times: 1 },
        );
        await form
          .getByRole("button", { name: "提交退款", exact: true })
          .click();
        await expect(
          form.getByRole("list", { name: "退款提交日志" }),
        ).toContainText("提交失败");
        await form.getByRole("button", { name: "用原键重试退款" }).click();
        await expect(
          page.getByRole("table", { name: "退款列表（2 条）" }),
        ).toBeVisible();
        expect(submitted).toHaveLength(4);
        expect(submitted[2]).toEqual(submitted[3]);
        expect(submitted[2].amount).toBe(2000);
      }
    }
  },
);
