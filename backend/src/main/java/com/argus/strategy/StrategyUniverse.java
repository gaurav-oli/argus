package com.argus.strategy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One member of the cross-sectional ranking universe.
 *
 * <p><b>Not a trading universe.</b> These tickers exist so a held name's signal can be ranked against a real
 * cross-section — a published decile strategy sorts thousands of stocks, and ranking 22 holdings against each
 * other would make a "decile" two names wide. Deliberately separate from {@code KnownUniverse}: nothing here is
 * recommended on, traded, deeply analysed, or watched for news. Only its price history is collected.
 */
@Entity
@Table(name = "strategy_universe")
public class StrategyUniverse {

	@Id
	private String ticker;

	private String name;

	@Column(name = "gics_sector")
	private String gicsSector;

	@Column(name = "gics_sub_industry")
	private String gicsSubIndustry;

	@Column(nullable = false)
	private String source;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "added_at", nullable = false)
	private Instant addedAt = Instant.now();

	protected StrategyUniverse() {
	}

	public StrategyUniverse(String ticker, String name, String gicsSector, String gicsSubIndustry, String source) {
		this.ticker = ticker;
		this.name = name;
		this.gicsSector = gicsSector;
		this.gicsSubIndustry = gicsSubIndustry;
		this.source = source;
	}

	public void refresh(String name, String gicsSector, String gicsSubIndustry) {
		this.name = name;
		this.gicsSector = gicsSector;
		this.gicsSubIndustry = gicsSubIndustry;
		this.active = true;
	}

	public void deactivate() {
		this.active = false;
	}

	public String getTicker() { return ticker; }
	public String getName() { return name; }
	public String getGicsSector() { return gicsSector; }
	public String getGicsSubIndustry() { return gicsSubIndustry; }
	public String getSource() { return source; }
	public boolean isActive() { return active; }
	public Instant getAddedAt() { return addedAt; }
}
