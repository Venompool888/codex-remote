import assert from "node:assert/strict";
import test from "node:test";
import { RequestBudget } from "../src/request-budget.js";
test("public request budget rejects excess and recovers after the window", () => {
  let now = 1;
  const budget = new RequestBudget(2, 100, () => now);
  assert.equal(budget.take(), true);
  assert.equal(budget.take(), true);
  assert.equal(budget.take(), false);
  now = 101;
  assert.equal(budget.take(), true);
});
