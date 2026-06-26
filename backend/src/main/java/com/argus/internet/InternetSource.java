package com.argus.internet;

import java.util.Collection;
import java.util.List;

/** A free public web-attention source for Agent 3 (Hacker News, Wikipedia). Best-effort. */
public interface InternetSource {

	String name();

	/** Recent web mentions for the held companies. Best-effort; returns empty on failure. */
	List<RawWebMention> fetch(Collection<HeldCompany> held);

	/** A held position, with the company name needed to query name-based web sources. */
	record HeldCompany(String ticker, String name) {
	}
}
