package com.argus.sec;

import com.argus.intelligence.KnownUniverse;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Agent 4's SEC-ingestion pipeline. On a cadence ({@code argus.sec.poll-ms}, default 6h — filings
 * are infrequent) it pulls each held ticker's recent insider Form 4s from EDGAR, dedups against
 * what's stored, and persists a summarized transaction per filing.
 */
@Service
public class SecIngestionService {

	private static final Logger log = LoggerFactory.getLogger(SecIngestionService.class);
	private static final int LOOKBACK_DAYS = 45;

	private final EdgarClient edgar;
	private final SecFilingRepository filings;
	private final KnownUniverse universe;

	public SecIngestionService(EdgarClient edgar, SecFilingRepository filings, KnownUniverse universe) {
		this.edgar = edgar;
		this.filings = filings;
		this.universe = universe;
	}

	@Scheduled(fixedDelayString = "${argus.sec.poll-ms:21600000}",
			initialDelayString = "${argus.sec.initial-delay-ms:45000}")
	public void scheduledTick() {
		try {
			ingestOnce();
		}
		catch (RuntimeException ex) {
			log.warn("SEC ingestion cycle failed: {}", ex.getMessage());
		}
	}

	void ingestOnce() {
		List<String> heldTickers = universe.knownTickers().stream().distinct().toList();
		if (heldTickers.isEmpty()) {
			return;
		}
		LocalDate since = LocalDate.now().minusDays(LOOKBACK_DAYS);
		int fetched = 0;
		int saved = 0;
		for (String ticker : heldTickers) {
			for (RawSecFiling f : edgar.insiderFilings(ticker, since)) {
				fetched++;
				if (filings.existsByAccession(f.accession())) {
					continue;
				}
				filings.save(new SecFiling(f.ticker(), f.cik(), f.accession(), f.formType(), f.filedAt(),
						f.url(), f.insiderName(), f.insiderTitle(), f.transactionType(), f.shares(), f.value()));
				saved++;
			}
		}
		if (fetched > 0) {
			log.info("SEC ingestion: {} Form 4s fetched, {} new across {} ticker(s)", fetched, saved,
					heldTickers.size());
		}
	}
}
