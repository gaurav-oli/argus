package com.argus.deepanalysis;

/**
 * The prompts for Agent 11's multi-stage analysis. Every stage is told to use only the supplied evidence
 * (numbers are computed by code; the models reason over them, they do not produce them), to say so when
 * the evidence is thin, and to answer in strict JSON so the result can be validated and guard-checked.
 */
final class DeepPrompts {

	private DeepPrompts() {
	}

	/** The measured lessons block (or nothing) — these are outcomes from Argus's own paper trades, not opinions. */
	private static String lessonsBlock(String lessons) {
		return lessons == null || lessons.isBlank() ? "" : "\n" + lessons.strip()
				+ "\nTreat these as evidence about how this kind of setup has actually performed here; weigh them, and explain if you override one.\n";
	}

	private static final String SPECIALIST_SCHEMA = """
			Respond with ONLY a JSON object, no prose before or after:
			{"stance":"BULLISH|BEARISH|NEUTRAL","strength":0.0,"summary":"2-4 sentences","keyPoints":["evidence-backed point","..."],"risks":["what could make this wrong","..."]}
			"strength" (0.0-1.0) is how strongly the evidence supports your stance; use 0.0-0.2 when the evidence is thin or mixed.""";

	static String technical(String ticker, String evidence) {
		return specialist("chart technician", ticker, """
				Read the chart the way a disciplined technician would: the trend and whether the moving averages support it,
				momentum (RSI, MACD), whether volume confirms or contradicts the move (accumulation vs distribution), candlestick
				patterns and whether their context makes them meaningful, the nearest support and resistance, and strength relative to the
				market. Say where a buy would be invalidated (the level that would prove you wrong) and whether the setup is a
				trend-following one or a mean-reversion one.""", evidence);
	}

	static String fundamental(String ticker, String evidence) {
		return specialist("fundamental analyst", ticker, """
				Assess business quality and value: is revenue growth real and accelerating or fading, are margins expanding, is the balance
				sheet sound, does the company reliably beat earnings estimates, what do analysts think and is that improving, and is the
				valuation (P/E, vs peers, vs growth) already pricing in the good news? A great company at a bad price is not a buy. Use the
				COMPANY FILINGS section — what management itself said about guidance, tone and risks — and the reverse-DCF line (the growth
				the current price implies versus what the company has delivered): a stock priced for growth it has never delivered is rich
				however good the business is.""", evidence);
	}

	static String catalyst(String ticker, String evidence) {
		return specialist("news and catalyst analyst", ticker, """
				Separate signal from noise in the recent news, insider activity, crowd chatter and earnings timing. Which developments are durable
				changes to the business and which are one-off headlines? Does the news actually explain the recent price action? Treat routine
				insider sales as weak evidence and open-market insider buys as meaningful. Flag any imminent earnings release as event risk. The COMPANY FILINGS
				section is what the company itself just said (guidance raised or lowered, going-concern or new-risk language): weigh it above
				commentary about the company.""", evidence);
	}

	static String macro(String ticker, String evidence) {
		return specialist("macro and sector strategist", ticker, """
				Judge how the macro backdrop and market regime affect THIS stock's sector specifically. Is any recent weakness market-wide and likely
				temporary (a headline-driven selloff that tends to reverse), or company/sector-specific and likely to persist? Which of the listed
				macro themes actually matter for this sector, given the exposure shown, and which are irrelevant?""", evidence);
	}

	private static String specialist(String role, String ticker, String focus, String evidence) {
		return """
				You are the %s on Argus's investment research desk, analysing %s.
				Use ONLY the evidence below. Do not invent figures, events or facts that are not in it. If the evidence is thin or missing for
				part of your remit, say so plainly and lower your strength rather than guessing.

				YOUR FOCUS:
				%s

				EVIDENCE:
				%s

				%s
				""".formatted(role, ticker, focus.strip(), evidence, SPECIALIST_SCHEMA);
	}

	static String skeptic(String ticker, String overview, String analystDigest, String lessons) {
		return """
				You are the skeptic on Argus's research desk. Your only job is to find the strongest case AGAINST the emerging consensus on %s.
				Read the analysts' conclusions below and identify what they might be missing or over-weighting: crowded positioning, a
				priced-in story, a fragile assumption, evidence that contradicts the majority, a data gap, or a risk nobody addressed. Be specific
				and cite the analysts' own points; do not invent facts.

				OVERVIEW:
				%s
				ANALYSTS' CONCLUSIONS:
				%s
				%s
				Respond with ONLY a JSON object:
				{"consensusDirection":"BULLISH|BEARISH|NEUTRAL","counterpoints":["specific counterpoint","..."],"severity":0.0,"summary":"2-3 sentences"}
				"severity" (0.0-1.0) is how damaging your best counterpoints are to the consensus: 0.0 = nothing substantive, 1.0 = it should overturn it.
				""".formatted(ticker, overview, analystDigest, lessonsBlock(lessons));
	}

	static String verdict(String ticker, String overview, String quickAgents, String analystDigest, String skepticDigest, String lessons) {
		return """
				You are the portfolio manager on Argus's research desk. Four specialists and a skeptic have reported on %s. Decide whether it is
				worth buying, and if so for how long to hold it.

				Be calibrated: most stocks, most of the time, deserve WAIT. Only call WORTH_BUYING when independent lenses (chart, fundamentals,
				news/catalysts, macro) agree and the risk/reward is compelling for the horizon you choose. Only call NOT_WORTH_BUYING when the
				evidence points to decline or underperformance. Use ONLY the material below; do not invent facts.

				VERDICTS:
				- WORTH_BUYING: a buy is justified now. Give holdDays.
				- WAIT: promising or uncertain, but timing or risk is unresolved. Say what would trigger a buy.
				- NOT_WORTH_BUYING: avoid it (or sell if held).
				HOLDING PERIODS (WORTH_BUYING only): 7 = a swing trade resolving in 1-2 weeks (catalyst / technical setup);
				30 = about 1-3 months (earnings, momentum or a valuation re-rating); 90 = a 3-month-plus position that rests on strong,
				improving fundamentals. Do not choose 90 unless the fundamentals support it.
				"conviction" (0-100) is how sure you are of the VERDICT, not how good the company is.

				OVERVIEW:
				%s
				WHAT THE FAST AGENTS CONCLUDED:
				%s
				SPECIALISTS:
				%s
				SKEPTIC:
				%s
				%s
				Respond with ONLY a JSON object, no prose before or after:
				{"verdict":"WORTH_BUYING|WAIT|NOT_WORTH_BUYING","holdDays":30,"conviction":0,"headline":"one line a busy investor can act on",
				"thesis":"one paragraph explaining the verdict","bullCase":"the best case for owning it","bearCase":"the best case against",
				"risks":["specific risk","..."],"catalysts":["specific upcoming catalyst","..."],
				"invalidation":"the concrete evidence or price level that would change your mind","invalidationPrice":0.0}
				Use null for holdDays unless the verdict is WORTH_BUYING. "invalidationPrice" is the single price whose breach would prove
				you wrong (below today's price for WORTH_BUYING, above it for NOT_WORTH_BUYING; null for WAIT) — use a support/resistance
				level from the evidence, not a guess.
				""".formatted(ticker, overview, quickAgents, analystDigest, skepticDigest, lessonsBlock(lessons));
	}
}
