// Unit tests for the table sort comparator. Run with `npm test`.
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { compareBy } from "./compareBy.ts";

const rows = [{ v: 2 }, { v: null }, { v: -1.5 }, { v: 10 }];
const vals = (r) => r.map((x) => x.v);

describe("compareBy", () => {
  it("sorts numbers numerically, not as text", () => {
    assert.deepEqual(vals([...rows].sort(compareBy((r) => r.v, "asc"))), [-1.5, 2, 10, null]);
    assert.deepEqual(vals([...rows].sort(compareBy((r) => r.v, "desc"))), [10, 2, -1.5, null]);
  });
  it("keeps nulls last in both directions", () => {
    for (const dir of ["asc", "desc"]) assert.equal([...rows].sort(compareBy((r) => r.v, dir)).at(-1).v, null);
  });
  it("sorts text and ISO dates lexically", () => {
    const d = [{ t: "2026-10-02T00:00:00Z" }, { t: "2026-09-30T00:00:00Z" }, { t: "2026-10-05T00:00:00Z" }];
    assert.deepEqual([...d].sort(compareBy((r) => r.t, "desc")).map((r) => r.t.slice(0, 10)), ["2026-10-05", "2026-10-02", "2026-09-30"]);
    assert.deepEqual(["SHOP.TO", "AAPL", "NVDA"].map((t) => ({ t })).sort(compareBy((r) => r.t, "asc")).map((r) => r.t), ["AAPL", "NVDA", "SHOP.TO"]);
  });
});
