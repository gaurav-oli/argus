package com.argus.sec;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A parsed SEC filing as returned by {@link EdgarClient}, before persistence (Agent 4). */
public record RawSecFiling(String ticker, String cik, String accession, String formType, LocalDate filedAt,
		String url, String insiderName, String insiderTitle, String transactionType, BigDecimal shares,
		BigDecimal value) {
}
