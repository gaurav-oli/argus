package com.argus.technical;

import java.time.LocalDate;

/**
 * Yahoo re-based a ticker's price history — a split, reverse split or spin-off — and the stored candles were
 * rebuilt. Every price recorded before {@code effectiveFrom} must be multiplied by {@code factor} to compare with
 * prices from then on (a 1-for-20 reverse split: factor 20; a 4-for-1 split: 0.25).
 */
public record HistoryReadjusted(String ticker, double factor, LocalDate effectiveFrom) {
}
