// Unit tests for the Agents-page pipeline logic. Run with `npm test`.
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { classifyWire, compactAge, convergePath, streamOf, wireLabel } from "./pipelineFlow.ts";

const NOW = Date.parse("2026-10-05T18:32:00Z");
const ago = (min) => new Date(NOW - min * 60_000).toISOString();
const agent = (over) => ({ status: "ACTIVE", lastActivity: ago(2), intervalMinutes: 5, staleAfterMinutes: 30, schedule: "≤5 min", ...over });

describe("classifyWire", () => {
  it("a frequent agent that just reported is a dense, fast stream", () => {
    const w = classifyWire(agent({}), NOW);
    assert.equal(w.state, "busy");
    assert.equal(w.particles, 4);
    assert.equal(w.seconds, 1.2);
    assert.equal(wireLabel(w), "2m ago");
  });

  it("a slower agent that reported within its cadence streams more gently", () => {
    const w = classifyWire(agent({ lastActivity: ago(90), intervalMinutes: 360, staleAfterMinutes: 720 }), NOW);
    assert.equal(w.state, "busy");
    assert.equal(w.particles, 2);
  });

  it("between runs but not stale: one slow particle", () => {
    const w = classifyWire(agent({ lastActivity: ago(800), intervalMinutes: 360, staleAfterMinutes: 4320 }), NOW);
    assert.equal(w.state, "between");
    assert.equal(w.particles, 1);
  });

  it("past its stale threshold the wire breaks", () => {
    const w = classifyWire(agent({ lastActivity: ago(47), intervalMinutes: 10, staleAfterMinutes: 30 }), NOW);
    assert.equal(w.state, "stalled");
    assert.equal(w.particles, 0);
    assert.equal(wireLabel(w), "stalled 47m");
  });

  it("exactly at the threshold is not yet stalled", () => {
    assert.notEqual(classifyWire(agent({ lastActivity: ago(30), intervalMinutes: 10, staleAfterMinutes: 30 }), NOW).state, "stalled");
  });

  it("a scheduled agent with no data yet is empty, not an alarm", () => {
    const w = classifyWire(agent({ lastActivity: null }), NOW);
    assert.equal(w.state, "nodata");
    assert.equal(wireLabel(w), "no data yet");
  });

  it("no cadence: continuous streams, on-demand waits", () => {
    assert.equal(classifyWire(agent({ intervalMinutes: null, staleAfterMinutes: null, lastActivity: null, schedule: "continuous" }), NOW).state, "continuous");
    const research = classifyWire(agent({ intervalMinutes: null, staleAfterMinutes: null, lastActivity: ago(5000), schedule: "on demand" }), NOW);
    assert.equal(research.state, "oncall");
    assert.equal(research.particles, 0);
  });

  it("an on-demand agent is never shown as stalled, however long it's been", () => {
    assert.equal(classifyWire(agent({ intervalMinutes: null, staleAfterMinutes: null, lastActivity: ago(100_000), schedule: "on demand" }), NOW).state, "oncall");
  });

  it("planned agents are dimmed and idle", () => {
    assert.equal(classifyWire(agent({ status: "PLANNED" }), NOW).state, "planned");
  });

  it("a timestamp slightly in the future (clock skew) counts as just now", () => {
    const w = classifyWire(agent({ lastActivity: new Date(NOW + 60_000).toISOString() }), NOW);
    assert.equal(w.ageMinutes, 0);
    assert.equal(w.state, "busy");
  });
});

describe("streams", () => {
  it("groups the fleet into sources, market and analysis", () => {
    assert.equal(streamOf("news"), "sources");
    assert.equal(streamOf("filings-reader"), "market");
    assert.equal(streamOf("recommender"), "analysis");
  });
  it("a new, unknown agent still shows up (in sources)", () => {
    assert.equal(streamOf("agent-16"), "sources");
  });
});

describe("convergePath", () => {
  it("starts at the stream and ends at the core, at any height", () => {
    assert.equal(convergePath(40, 200, 64), "M0 40.0 C32 40.0 32 200.0 64 200.0");
    assert.match(convergePath(400, 200, 64), /^M0 400\.0 .* 64 200\.0$/);
  });
});

describe("compactAge", () => {
  it("formats minutes, hours and days", () => {
    assert.equal(compactAge(0.4), "now");
    assert.equal(compactAge(47), "47m");
    assert.equal(compactAge(180), "3h");
    assert.equal(compactAge(2880), "2d");
  });
});

describe("dossiers", async () => {
  const { dossierStats, stampFor, reportsTo } = await import("./pipelineFlow.ts");
  const w = (over) => classifyWire(agent(over), NOW);

  it("stamps follow the pipeline's wire states", () => {
    assert.equal(stampFor(w({})), "ACTIVE");
    assert.equal(stampFor(w({ lastActivity: ago(800), intervalMinutes: 360, staleAfterMinutes: 4320 })), "ACTIVE");
    assert.equal(stampFor(w({ lastActivity: ago(74), intervalMinutes: 10, staleAfterMinutes: 60 })), "OFF GRID");
    assert.equal(stampFor(w({ intervalMinutes: null, staleAfterMinutes: null, lastActivity: null, schedule: "continuous" })), "ON WATCH");
    assert.equal(stampFor(w({ intervalMinutes: null, staleAfterMinutes: null, schedule: "on demand" })), "ON CALL");
    assert.equal(stampFor(w({ lastActivity: null })), "NO DATA");
  });

  it("a fresh, frequent, high-volume agent fills its bars and sheds bit dust", () => {
    const a = agent({ captured: 18240 });
    const s = dossierStats(a, classifyWire(a, NOW));
    assert.ok(s.volume > 0.85 && s.volume <= 1);
    assert.equal(s.tempo, 1);
    assert.ok(s.fresh > 0.9);
    assert.equal(s.live, true);
    assert.equal(s.decaying, false);
  });

  it("a stalled agent keeps a sliver of FRESH that decays instead of dust", () => {
    const a = agent({ captured: 9812, lastActivity: ago(74), intervalMinutes: 10, staleAfterMinutes: 60 });
    const s = dossierStats(a, classifyWire(a, NOW));
    assert.equal(s.fresh, 0.08);
    assert.equal(s.live, false);
    assert.equal(s.decaying, true);
  });

  it("freshness drains linearly toward the stale limit", () => {
    const a = agent({ captured: 10, lastActivity: ago(1080), intervalMinutes: 1440, staleAfterMinutes: 4320 });
    assert.ok(Math.abs(dossierStats(a, classifyWire(a, NOW)).fresh - 0.75) < 1e-9);
  });

  it("an on-demand agent has no tempo or freshness and stays still", () => {
    const a = agent({ captured: 37, intervalMinutes: null, staleAfterMinutes: null, schedule: "on demand" });
    const s = dossierStats(a, classifyWire(a, NOW));
    assert.equal(s.tempo, 0);
    assert.equal(s.fresh, 0);
    assert.equal(s.live, false);
  });

  it("zero captures is an empty volume bar, not NaN", () => {
    const a = agent({ captured: 0 });
    assert.equal(dossierStats(a, classifyWire(a, NOW)).volume, 0);
  });

  it("knows who each agent reports to", () => {
    assert.deepEqual(reportsTo("recommender"), ["You"]);
    assert.deepEqual(reportsTo("unknown"), []);
  });
});
