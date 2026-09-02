package com.argus.technical;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One daily OHLC candle for a ticker (Agent 10 — Technical Analysis). Dedup key is
 * {@code (ticker, candleDate)}; {@link TechnicalIndicators} computes RSI/SMA/drawdown over a
 * ticker's recent candles.
 */
@Entity
@Table(name = "price_candles")
public class PriceCandle {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String ticker;

	@Column(name = "candle_date", nullable = false)
	private LocalDate candleDate;

	@Column(nullable = false)
	private BigDecimal open;

	@Column(nullable = false)
	private BigDecimal high;

	@Column(nullable = false)
	private BigDecimal low;

	@Column(nullable = false)
	private BigDecimal close;

	private Long volume;

	@Column(name = "ingested_at", nullable = false)
	private Instant ingestedAt = Instant.now();

	protected PriceCandle() {
		// JPA
	}

	public PriceCandle(String ticker, LocalDate candleDate, BigDecimal open, BigDecimal high,
			BigDecimal low, BigDecimal close, Long volume) {
		this.ticker = ticker;
		this.candleDate = candleDate;
		this.open = open;
		this.high = high;
		this.low = low;
		this.close = close;
		this.volume = volume;
	}

	public String getTicker() {
		return ticker;
	}

	public LocalDate getCandleDate() {
		return candleDate;
	}

	public BigDecimal getOpen() {
		return open;
	}

	public BigDecimal getHigh() {
		return high;
	}

	public BigDecimal getLow() {
		return low;
	}

	public BigDecimal getClose() {
		return close;
	}

	public Long getVolume() {
		return volume;
	}

	public Instant getIngestedAt() {
		return ingestedAt;
	}
}
