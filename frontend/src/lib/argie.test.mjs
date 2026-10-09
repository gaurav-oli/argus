// Unit tests for Argie's mood rules and sprite. Run with `npm test`.
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ARGIE_SIZE, MIN_CALLS_FOR_A_MOOD, MOOD_LABEL, accuracyLine, argieSprite, moodForWinRate } from "./argie.ts";

describe("moodForWinRate", () => {
  it("maps win rates to moods at the documented thresholds", () => {
    const at = (r) => moodForWinRate(r, 20);
    assert.equal(at(65), "celebrate");
    assert.equal(at(64.9), "happy");
    assert.equal(at(55), "happy");
    assert.equal(at(54), "watching");
    assert.equal(at(48), "watching");
    assert.equal(at(47), "worried");
    assert.equal(at(40), "worried");
    assert.equal(at(39), "sad");
    assert.equal(at(0), "sad");
  });
  it("naps on samples too small to mean anything, however good or bad", () => {
    assert.equal(moodForWinRate(100, MIN_CALLS_FOR_A_MOOD - 1), "napping");
    assert.equal(moodForWinRate(0, 3), "napping");
    assert.equal(moodForWinRate(null, 50), "napping");
    assert.equal(moodForWinRate(70, MIN_CALLS_FOR_A_MOOD), "celebrate");
  });
});

describe("words", () => {
  it("every mood has a written label and an explanation", () => {
    for (const m of ["celebrate", "happy", "watching", "worried", "sad", "napping"]) {
      assert.ok(MOOD_LABEL[m].length > 0, m);
      assert.ok(accuracyLine(m, 7, 12).length > 10, m);
    }
    assert.equal(MOOD_LABEL.celebrate, "Celebrating");
    assert.match(accuracyLine("napping", 0, 0), /No resolved calls/);
    assert.match(accuracyLine("napping", 2, 4), /Only 4 resolved calls/);
    assert.match(accuracyLine("happy", 8, 13), /8 of 13/);
  });
});

describe("argieSprite", () => {
  it("draws every mood inside its grid, with the body colour present", () => {
    for (const m of ["celebrate", "happy", "watching", "worried", "sad", "napping"]) {
      const px = argieSprite(m);
      assert.ok(px.length > 200, m);
      for (const [x, y] of px) {
        assert.ok(x >= 0 && x < ARGIE_SIZE.w && y >= 0 && y < ARGIE_SIZE.h, `${m} pixel out of bounds: ${x},${y}`);
      }
      assert.ok(px.some(([, , c]) => c === "B"), m);
    }
  });
  it("only the moods that should have them get a tear, a blink or a waving arm", () => {
    const layers = (m) => new Set(argieSprite(m).map((p) => p[3]));
    assert.ok(layers("sad").has("tear"));
    assert.ok(!layers("happy").has("tear"));
    assert.ok(layers("happy").has("arm"));
    assert.ok(layers("watching").has("lid"));
    assert.ok(!layers("sad").has("lid")); // a heavy lid instead of a blink
    assert.ok(!layers("celebrate").has("lid")); // eye already happy-closed
  });
  it("is deterministic", () => {
    assert.deepEqual(argieSprite("worried"), argieSprite("worried"));
  });
});
