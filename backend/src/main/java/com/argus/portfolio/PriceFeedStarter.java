package com.argus.portfolio;

import com.argus.marketdata.PriceFeed;
import jakarta.annotation.PreDestroy;
import java.util.Collection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Wires the (optional, key-gated) {@link PriceFeed} to {@link LivePortfolioService} once the app is
 * ready (Story 3.4). When no Finnhub key is configured the feed bean is absent and this is a no-op,
 * so dev/test/no-key contexts open no socket. Lives on the portfolio side so {@code marketdata} need
 * not depend on portfolio types (avoids a bean cycle).
 */
@Component
public class PriceFeedStarter {

	private final ObjectProvider<PriceFeed> priceFeed;
	private final PositionRepository positions;
	private final LivePortfolioService live;

	public PriceFeedStarter(ObjectProvider<PriceFeed> priceFeed, PositionRepository positions,
			LivePortfolioService live) {
		this.priceFeed = priceFeed;
		this.positions = positions;
		this.live = live;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void startFeed() {
		PriceFeed feed = priceFeed.getIfAvailable();
		if (feed != null) {
			feed.start(this::heldTickers, live::onPriceTick);
		}
	}

	@PreDestroy
	public void stopFeed() {
		PriceFeed feed = priceFeed.getIfAvailable();
		if (feed != null) {
			feed.stop();
		}
	}

	private Collection<String> heldTickers() {
		return positions.findAllByOrderByTickerAsc().stream().map(Position::getTicker).distinct().toList();
	}
}
