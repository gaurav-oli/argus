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

/** Agent 11's three possible verdicts, keyed exactly like DeepVerdictName/VERDICT_STYLE. */
export const DEEP_VERDICT_GLOSSARY: Record<"WORTH_BUYING" | "WAIT" | "NOT_WORTH_BUYING", string> = {
  WORTH_BUYING:
    "Agent 11 read every other agent's evidence, ran four local specialists (chart, fundamentals, catalysts, macro) and a skeptic, then a portfolio-manager pass — and concluded the case to buy is real.",
  WAIT: "Agent 11 found the evidence too thin or too mixed to call either way. This is the most common verdict — most stocks, most of the time, deserve a wait.",
  NOT_WORTH_BUYING:
    "Agent 11 ran the same multi-specialist process and concluded the case against owning it outweighs the case for it.",
};

/** A strategy's validation status in the Academic Strategies library (Agent 15), keyed like StrategyRow["status"]. */
export const STRATEGY_STATUS_GLOSSARY: Record<"ACTIVE" | "CANDIDATE" | "REJECTED" | "UNIMPLEMENTED", string> = {
  ACTIVE:
    "This published strategy actually beat a chronological hold-out backtest on Argus's own data — it's the only kind allowed to influence a recommendation. A paper's own t-statistic is never enough on its own.",
  CANDIDATE: "Argus can compute this strategy, but hasn't finished testing it against a held-back period of its own data yet. Not acted on until it is.",
  REJECTED: "This strategy was tested on Argus's own data and failed — either no real edge in-sample, or it worked in-sample but not on the held-back period (the classic sign of a decayed or fabricated edge).",
  UNIMPLEMENTED: "This published strategy needs data Argus doesn't have (e.g. point-in-time accounting, analyst, or options data) — it exists in the library for completeness but can't be computed here.",
};

export const CHART_GLOSSARY: Record<string, string> = {
  BULLISH: "Agent 10's chart score is positive: the technical picture (trend, momentum, volume, candlesticks, relative strength) leans bullish.",
  BEARISH: "Agent 10's chart score is negative: the technical picture leans bearish.",
  NEUTRAL: "Agent 10's chart score is close to flat — no clear technical lean either way.",
};
