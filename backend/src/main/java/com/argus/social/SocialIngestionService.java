package com.argus.social;

import com.argus.intelligence.KnownUniverse;
import com.argus.intelligence.SentimentLabel;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Agent 2's social-ingestion pipeline. On a cadence ({@code argus.social.poll-ms}, default 10 min)
 * it pulls recent posts mentioning held tickers from every {@link SocialSource} (StockTwits always;
 * Reddit when keyed), dedups against what's stored, resolves each post's crowd sentiment, and
 * persists it. A single source failing never aborts the cycle.
 */
@Service
public class SocialIngestionService {

	private static final Logger log = LoggerFactory.getLogger(SocialIngestionService.class);

	private final List<SocialSource> sources;
	private final SocialPostRepository posts;
	private final KnownUniverse universe;

	public SocialIngestionService(List<SocialSource> sources, SocialPostRepository posts,
			KnownUniverse universe) {
		this.sources = sources;
		this.posts = posts;
		this.universe = universe;
	}

	@Scheduled(fixedDelayString = "${argus.social.poll-ms:600000}",
			initialDelayString = "${argus.social.initial-delay-ms:20000}")
	public void scheduledTick() {
		try {
			ingestOnce();
		}
		catch (RuntimeException ex) {
			log.warn("Social ingestion cycle failed: {}", ex.getMessage());
		}
	}

	/**
	 * Strips characters Postgres's UTF8 text columns reject outright — NUL (U+0000) shows up in a small
	 * fraction of StockTwits/Reddit bodies (truncated emoji, upstream encoding bugs) and fails the insert
	 * with no partial save. Other C0 control characters are stripped too; ordinary whitespace is kept.
	 */
	private static String sanitize(String s) {
		if (s == null) {
			return null;
		}
		return s.codePoints()
				.filter(c -> c == '\n' || c == '\r' || c == '\t' || c >= 0x20)
				.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
				.toString();
	}

	void ingestOnce() {
		List<String> heldTickers = universe.knownTickers().stream().distinct().toList();
		if (heldTickers.isEmpty() || sources.isEmpty()) {
			return;
		}
		int fetched = 0;
		int saved = 0;
		for (SocialSource source : sources) {
			List<RawSocialPost> raw;
			try {
				raw = source.fetch(heldTickers);
			}
			catch (RuntimeException ex) {
				log.debug("Social source {} failed: {}", source.name(), ex.getMessage());
				continue;
			}
			for (RawSocialPost p : raw) {
				fetched++;
				if (posts.existsBySourceAndExternalId(p.source(), p.externalId())) {
					continue;
				}
				SentimentLabel label = SocialSentiment.resolve(p.sentimentHint(), p.body());
				try {
					posts.save(new SocialPost(p.ticker(), p.source(), p.externalId(), sanitize(p.author()), sanitize(p.body()),
							p.url(), label, SocialSentiment.score(label), p.postedAt()));
					saved++;
				}
				catch (RuntimeException ex) {
					// One malformed post (a stray NUL byte, an oversized body) must not cost the rest of the
					// cycle — the class's whole promise is that a single failure stays single.
					log.debug("Social ingestion: dropped one post from {} ({}): {}", p.source(), p.externalId(), ex.getMessage());
				}
			}
		}
		if (fetched > 0) {
			log.info("Social ingestion: {} fetched, {} new across {} source(s)", fetched, saved, sources.size());
		}
	}
}
