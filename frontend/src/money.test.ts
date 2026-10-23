import { expect, test } from "vitest";
import { formatCad } from "./money";

test.each([
  [0, "CAD 0.00"],
  [5, "CAD 0.05"],
  [99, "CAD 0.99"],
  [100, "CAD 1.00"],
  [10000, "CAD 100.00"],
  [99999999, "CAD 999999.99"],
  [-5, "CAD -0.05"],
  [-10000, "CAD -100.00"],
])("ac_1_14 formats integer cents %i as %s", (cents, expected) => {
  expect(formatCad(cents)).toBe(expected);
});

test.each([
  0.5,
  Number.NaN,
  Number.POSITIVE_INFINITY,
  Number.MAX_SAFE_INTEGER + 1,
])("ac_1_14 rejects non-safe integer cents %s", (cents) => {
  expect(() => formatCad(cents)).toThrow(RangeError);
});
