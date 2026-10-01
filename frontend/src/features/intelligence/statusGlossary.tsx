/**
 * Plain-English definitions for the one-word status chips scattered across the Intelligence page
 * (action, deep verdict, chart bias, valuation) — the thing a badge alone can't say. Centralised so the
 * explanation is the same wherever a chip shows up (the roster, the detail tabs), rather than redescribing
 * each one ad hoc per component.
 */

export const VALUATION_GLOSSARY: Record<string, string> = {
  CHEAP:
    "Agent 12's reverse DCF: the current price assumes less earnings growth than the company has actually delivered — the market may be underpricing its own track record.",
  FAIR: "Agent 12's reverse DCF: the growth the price assumes is roughly in line with what the company has actually delivered. No real edge either way.",
  RICH: "Agent 12's reverse DCF: the current price assumes more earnings growth than the company has actually delivered — you'd be paying for growth that hasn't shown up yet.",
};

export const ACTION_GLOSSARY: Record<string, string> = {
  STRONG_BUY: "Agent 5's strongest call: ≥3 independent agents agree, at least 2 of them hard evidence (not just an LLM read), and conviction clears the actionable bar by a wide margin.",
  BUY: "Agent 5 found a real, if more modest, edge to buy — independent sources agree, but with less breadth or conviction than a Strong Buy.",
  WATCH: "No actionable edge either way — too little independent evidence, or the signals disagree. Argus is watching, not calling anything. The most common and most honest state.",
  AVOID: "Agent 5 found a real edge to sell or avoid — independent sources agree the setup has turned against the stock.",
  STRONG_AVOID: "Agent 5's strongest sell call: ≥3 independent agents agree, at least 2 of them hard evidence, with high conviction.",
};

export const DEEP_GLOSSARY = {
  worthBuying: "Agent 11 (the slow, multi-stage deep analyst) read every agent's evidence, ran four specialists and a skeptic, and concluded this is worth buying.",
  atRisk:
    "Agent 11's standing verdict has been undermined since it was made — the price crossed its invalidation level, or a new filing contradicts it. A fresh analysis is already queued.",
  none: "Agent 11 hasn't produced a fresh verdict for this ticker, or it has expired.",
};

export const CHART_GLOSSARY: Record<string, string> = {
  BULLISH: "Agent 10's chart score is positive: the technical picture (trend, momentum, volume, candlesticks, relative strength) leans bullish.",
  BEARISH: "Agent 10's chart score is negative: the technical picture leans bearish.",
  NEUTRAL: "Agent 10's chart score is close to flat — no clear technical lean either way.",
};
