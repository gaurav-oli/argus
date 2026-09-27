package com.argus.deepanalysis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Read side of Agent 11: the latest still-fresh verdict per ticker, for the recommender, Investor, Agent 9 and the UI. */
@Service
public class DeepAnalysisService {

	private final DeepAnalysisRepository repository;

	public DeepAnalysisService(DeepAnalysisRepository repository) {
		this.repository = repository;
	}

	/** The newest finished verdict for {@code ticker} if it has not expired; empty otherwise. */
	public Optional<DeepView> viewFor(String ticker) {
		return repository.findFirstByTickerAndStatusOrderByFinishedAtDesc(normalize(ticker), DeepAnalysis.Status.DONE)
				.filter(d -> d.getExpiresAt() != null && d.getExpiresAt().isAfter(Instant.now()) && d.getVerdict() != null
						&& d.getConviction() != null)
				.map(d -> new DeepView(d.getVerdict(), d.getHoldDays(), d.getConviction(), d.getHeadline(), d.getInvalidation(),
						Duration.between(d.getFinishedAt(), Instant.now()).toDays(), d.isAtRisk(), d.getThesisReason(),
						d.getInvalidationPrice() == null ? null : d.getInvalidationPrice().doubleValue()));
	}

	/** The newest finished analysis for {@code ticker} regardless of expiry (for showing history and for Agent 9). */
	public Optional<DeepAnalysis> latestDone(String ticker) {
		return repository.findFirstByTickerAndStatusOrderByFinishedAtDesc(normalize(ticker), DeepAnalysis.Status.DONE);
	}

	/** The newest run per ticker (any status): a running analysis shows its progress next to the last verdict. */
	public List<DeepAnalysis> latestPerTicker() {
		return repository.latestPerTicker();
	}

	private static String normalize(String ticker) {
		return ticker == null ? "" : ticker.trim().toUpperCase(java.util.Locale.ROOT);
	}
}
