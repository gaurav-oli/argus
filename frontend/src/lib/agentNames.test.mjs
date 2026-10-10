import { test } from "node:test";
import assert from "node:assert/strict";
import { agentName, topSignals } from "./agentNames.ts";

test("known ids get friendly names; unknown ids are tidied", () => {
  assert.equal(agentName("agent-11-deep"), "Deep Analyst");
  assert.equal(agentName("agent-99-earnings-call"), "Earnings call");
  assert.equal(agentName(null), "an agent");
});

test("topSignals keeps the call's side, strongest first", () => {
  const s = [
    { agent: "a", direction: "BULLISH", weight: 0.2 },
    { agent: "b", direction: "BEARISH", weight: 0.9 },
    { agent: "c", direction: "BULLISH", weight: 0.7 },
    { agent: "d", direction: "BULLISH", weight: 0 },
    { agent: "e", direction: "BULLISH", weight: 0.4 },
    { agent: "f", direction: "BULLISH", weight: 0.3 },
  ];
  assert.deepEqual(topSignals(s, "BULLISH").map((x) => x.agent), ["c", "e", "f"]);
  assert.deepEqual(topSignals(s, "BEARISH", 5).map((x) => x.agent), ["b"]);
});
