import { test } from "node:test";
import assert from "node:assert/strict";
import { countsAsRefreshFailure, reportFailure, reportOk, resetForTests, snapshot, staleMessage, subscribe } from "./refreshHealth.ts";

const t = (ms) => `t${ms}`;

test("network failures and 5xx count; 4xx answers don't", () => {
  assert.equal(countsAsRefreshFailure(null), true);
  assert.equal(countsAsRefreshFailure(503), true);
  assert.equal(countsAsRefreshFailure(404), false);
  assert.equal(countsAsRefreshFailure(401), false);
});

test("a failure after a success says how old the data is; the next success clears it", () => {
  resetForTests();
  reportOk(1000);
  assert.equal(staleMessage(snapshot(), t), null);
  reportFailure(null, 5000);
  reportFailure(502, 9000);
  assert.equal(snapshot().failingSince, 5000);
  assert.equal(snapshot().failures, 2);
  assert.equal(staleMessage(snapshot(), t), "Couldn't refresh — showing data from t1000. Retrying…");
  reportFailure(404, 9500);
  assert.equal(snapshot().failures, 2, "a 404 is an answer, not a failed refresh");
  reportOk(12000);
  assert.equal(staleMessage(snapshot(), t), null);
});

test("failing before any success says data may be missing, and listeners are told", () => {
  resetForTests();
  let calls = 0;
  const off = subscribe(() => calls++);
  reportFailure(null, 1);
  off();
  reportFailure(null, 2);
  assert.equal(calls, 1);
  assert.match(staleMessage(snapshot(), t), /data may be missing/);
});
