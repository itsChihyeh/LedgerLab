import { renderToStaticMarkup } from "react-dom/server";
import { expect, test } from "vitest";
import { MerchantBalance } from "./components";
import { parseCad } from "./money";
import { AmountInput } from "./refunds";

test("ac_3_15 CAD input converts 20.00 to integer 2000 cents", () => {
  expect(parseCad("20.00")).toBe(2000);
  expect(parseCad("0.01")).toBe(1);
  expect(parseCad("999999.99")).toBe(99999999);
  const html = renderToStaticMarkup(
    <AmountInput label="金额" value="20.00" onChange={() => {}} />,
  );
  expect(html).toContain('value="20.00"');
  expect(html).not.toContain('role="alert"');
});
test.each(["100.5", "0.00", "-20.00", "1e2", "NaN", "20.001", "1000000.00"])(
  "ac_3_15 invalid CAD input %s shows validation",
  (value) => {
    expect(() => parseCad(value)).toThrow();
    const html = renderToStaticMarkup(
      <AmountInput label="金额" value={value} onChange={() => {}} />,
    );
    expect(html).toContain('role="alert"');
    if (value === "100.5") expect(html).toContain("100.5 请写为 100.50");
  },
);
test("ac_3_15 negative available is displayed as merchant debt", () => {
  const html = renderToStaticMarkup(<MerchantBalance available={-320} />);
  expect(html).toContain("商户欠款");
  expect(html).toContain("CAD 3.20");
  expect(html).not.toContain("商户可用");
  expect(html).not.toContain("CAD -");
});
