/**
 * Argie — Argus's pixel mascot: a little Argus Panoptes (one big eye, a crown of eye-stalks, because
 * Argus never closes all its eyes). Pure data here: which mood a number calls for, the words that go
 * with it, and the sprite's pixels. Rendering lives in components/brand/Argie.tsx.
 */

export type ArgieMood = "celebrate" | "happy" | "watching" | "worried" | "sad" | "napping";

/** Below this many resolved calls a win rate is noise, so Argie naps instead of reacting to it. */
export const MIN_CALLS_FOR_A_MOOD = 10;

/**
 * Mood for a win rate over a window. Thresholds: ≥65 celebrate, 55–64 happy, 48–54 watching (coin-flip
 * territory), 40–47 worried, <40 sad. Too few calls, or no rate at all, is "napping" — Argie never
 * celebrates or mourns a sample too small to mean anything.
 */
export function moodForWinRate(winRatePct: number | null, calls: number): ArgieMood {
  if (winRatePct == null || calls < MIN_CALLS_FOR_A_MOOD) return "napping";
  if (winRatePct >= 65) return "celebrate";
  if (winRatePct >= 55) return "happy";
  if (winRatePct >= 48) return "watching";
  if (winRatePct >= 40) return "worried";
  return "sad";
}

/** The word shown next to Argie — the emotion is always spelled out, never left to the picture alone. */
export const MOOD_LABEL: Record<ArgieMood, string> = {
  celebrate: "Celebrating",
  happy: "Happy",
  watching: "Watching",
  worried: "Worried",
  sad: "Sad",
  napping: "Napping",
};

/** One plain-English line explaining the mood for an accuracy window. */
export function accuracyLine(mood: ArgieMood, wins: number, calls: number): string {
  switch (mood) {
    case "celebrate":
      return `${wins} of ${calls} right in the last 30 days. The Analyst's reads are landing — the odds are still odds.`;
    case "happy":
      return `${wins} of ${calls} right in the last 30 days. Better than a coin flip, and holding.`;
    case "watching":
      return `${wins} of ${calls} right in the last 30 days — coin-flip territory. Watching closely.`;
    case "worried":
      return `${wins} of ${calls} right in the last 30 days. Slipping — the nightly logic review will look at which agents to trust less.`;
    case "sad":
      return `${wins} of ${calls} right in the last 30 days. A rough patch; losses feed the Analyst's post-mortems, which is how it learns.`;
    case "napping":
      return calls === 0
        ? "No resolved calls in the last 30 days yet — nothing to react to."
        : `Only ${calls} resolved call${calls === 1 ? "" : "s"} in the last 30 days — too few to mean anything. Argie wakes up at ${MIN_CALLS_FOR_A_MOOD}.`;
  }
}

/** Palette keys → colours (Terminal Noir). */
export const ARGIE_COLORS = {
  O: "#1a1408", // outline / features
  B: "#ffb000", // body
  S: "#c98a00", // shade, eye-stalks
  H: "#ffe08a", // highlight
  W: "#f4eedc", // eye white
  P: "#0a0a08", // pupil
  I: "#8a5f00", // iris
  M: "#3a2208", // mouth
  K: "#ff8f7a", // cheeks, tongue
  T: "#9fd3ff", // tears, sweat
} as const;
export type ArgieColor = keyof typeof ARGIE_COLORS;

/** Which animated layer a pixel belongs to: the body moves with the mood; the lid blinks; tears fall. */
export type ArgieLayer = "body" | "lid" | "tear" | "arm";
export type ArgiePixel = readonly [x: number, y: number, color: ArgieColor, layer: ArgieLayer];

export const ARGIE_SIZE = { w: 25, h: 24 } as const;

/** The sprite for a mood, as pixels on a 25×24 grid. Deterministic. */
export function argieSprite(mood: ArgieMood): ArgiePixel[] {
  const L: ArgiePixel[] = [];
  const px = (x: number, y: number, c: ArgieColor, layer: ArgieLayer = "body") => L.push([x, y, c, layer]);
  const line = (pts: [number, number][], c: ArgieColor, layer: ArgieLayer = "body") => pts.forEach(([x, y]) => px(x, y, c, layer));
  const sad = mood === "sad";
  const asleep = mood === "napping";

  // Eye-stalk crown — three stalks; they droop when sad.
  const stalks: [number, number, number][] = sad ? [[5, 6, -1], [12, 5, 0], [19, 6, 1]] : [[7, 3, 0], [12, 2, 0], [17, 3, 0]];
  for (const [sx, top, lean] of stalks) {
    for (let y = top + 3; y <= 8; y++) px(sx + (sad && y < 6 ? lean : 0), y, "S");
    const ex = sx + (sad ? lean : 0);
    for (const [dx, dy] of [[-1, -1], [0, -1], [1, -1], [-1, 0], [1, 0], [-1, 1], [0, 1], [1, 1]]) px(ex + dx, top + dy + 1, "B");
    if (asleep) px(ex, top + 1, "O");
    else if (mood === "celebrate") {
      px(ex, top + 1, "W");
      px(ex, top, "O");
    } else px(ex, top + 1, sad ? "W" : "P");
  }

  // Body: an outlined ellipse, shaded at the bottom, with a highlight.
  const inBody = (x: number, y: number) => ((x + 0.5 - 12) / 9.5) ** 2 + ((y + 0.5 - 15) / 7.6) ** 2 <= 1;
  for (let y = 6; y < 23; y++) {
    for (let x = 0; x < 25; x++) {
      if (!inBody(x, y)) continue;
      const edge = !inBody(x - 1, y) || !inBody(x + 1, y) || !inBody(x, y - 1) || !inBody(x, y + 1);
      px(x, y, edge ? "O" : y > 18 ? "S" : "B");
    }
  }
  line([[6, 11], [7, 10], [6, 12], [8, 10]], "H");
  line([[7, 22], [8, 22], [9, 22], [14, 22], [15, 22], [16, 22]], "O");
  line([[8, 21], [15, 21]], "S");

  // The big eye.
  const inEye = (x: number, y: number) => ((x + 0.5 - 12) / 4.4) ** 2 + ((y + 0.5 - 13) / 3.3) ** 2 <= 1;
  const eye: [number, number][] = [];
  for (let y = 9; y < 17; y++) for (let x = 6; x < 19; x++) if (inEye(x, y)) eye.push([x, y]);
  if (mood === "celebrate") {
    line([[9, 14], [10, 13], [11, 12], [12, 12], [13, 12], [14, 13], [15, 14]], "O"); // ^ happy-closed
  } else if (asleep) {
    line([[8, 13], [9, 14], [10, 14], [11, 14], [12, 14], [13, 14], [14, 14], [15, 14], [16, 13]], "O");
  } else {
    for (const [x, y] of eye) {
      const edge = !inEye(x - 1, y) || !inEye(x + 1, y) || !inEye(x, y - 1) || !inEye(x, y + 1);
      px(x, y, edge ? "O" : "W");
    }
    const [ox, oy] = mood === "happy" ? [1, -1] : sad ? [0, 1] : mood === "worried" ? [-1, 0] : [0, 0];
    const cx = 12 + ox;
    const cy = 13 + oy;
    for (const [dx, dy] of [[-1, -1], [0, -1], [-1, 0], [0, 0]]) px(cx + dx, cy + dy, mood === "worried" ? "P" : "I");
    px(cx, cy, "P");
    px(cx - 1, cy - 1, "W");
    if (sad) {
      // A heavy lid: the top half of the eye becomes skin.
      for (const [x, y] of eye) if (y <= 12) px(x, y, "B");
      line(eye.filter(([, y]) => y === 12).map(([x]) => [x, 12]), "O");
    } else {
      // The blink overlay (hidden except for a moment each cycle).
      for (const [x, y] of eye) px(x, y, "B", "lid");
      line(eye.filter(([, y]) => y === 13).map(([x]) => [x, 13]), "O", "lid");
    }
    if (mood === "worried") {
      line([[8, 9], [9, 8], [10, 8]], "O");
      line([[14, 8], [15, 8], [16, 9]], "O");
    }
  }

  // Mouth.
  if (mood === "celebrate") {
    for (let x = 9; x <= 15; x++) px(x, 17, "O");
    for (let x = 10; x <= 14; x++) {
      px(x, 18, "M");
      px(x, 19, x === 12 || x === 13 ? "K" : "M");
    }
    line([[9, 18], [15, 18], [10, 20], [11, 20], [12, 20], [13, 20], [14, 20]], "O");
  } else if (mood === "happy") {
    line([[9, 17], [10, 18], [11, 18], [12, 18], [13, 18], [14, 18], [15, 17]], "O");
  } else if (mood === "watching") {
    line([[10, 18], [11, 18], [12, 18], [13, 18], [14, 18]], "O");
  } else if (mood === "worried") {
    line([[9, 18], [10, 17], [11, 18], [12, 17], [13, 18], [14, 17], [15, 18]], "O");
  } else if (sad) {
    line([[9, 19], [10, 18], [11, 17], [12, 17], [13, 17], [14, 18], [15, 19]], "O");
  } else {
    line([[11, 18], [12, 18], [11, 19], [12, 19]], "M");
  }

  if (mood === "celebrate" || mood === "happy") line([[6, 16], [7, 16], [17, 16], [18, 16]], "K");

  // Arms.
  if (mood === "celebrate") {
    line([[3, 14], [2, 13], [1, 12], [1, 11]], "O");
    line([[21, 14], [22, 13], [23, 12], [23, 11]], "O");
    line([[0, 10], [1, 10], [23, 10], [24, 10]], "B");
  } else if (mood === "happy") {
    line([[21, 15], [22, 14], [23, 13], [23, 12]], "O", "arm");
    px(23, 11, "B", "arm");
    line([[3, 16], [2, 17]], "O");
  } else if (mood === "worried") {
    line([[3, 15], [2, 15], [2, 14]], "O");
    line([[21, 15], [22, 15], [22, 14]], "O");
    line([[20, 8], [20, 9], [19, 9]], "T"); // sweat drop
  } else {
    line([[3, 17], [2, 18], [2, 19]], "O");
    line([[21, 17], [22, 18], [22, 19]], "O");
  }
  if (sad) line([[8, 15], [8, 16]], "T", "tear");
  return L;
}
