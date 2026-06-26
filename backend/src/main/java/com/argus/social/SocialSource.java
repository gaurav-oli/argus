package com.argus.social;

import java.util.Collection;
import java.util.List;

/** A crowd-sentiment source for Agent 2 (StockTwits, Reddit). One failing source never aborts a cycle. */
public interface SocialSource {

	String name();

	/** Recent posts mentioning any of the held tickers. Best-effort; returns empty on failure. */
	List<RawSocialPost> fetch(Collection<String> heldTickers);
}
