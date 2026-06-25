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
			feed.start(this::heldTickers, live::onPriceTick, live::recordPreviousClose);
		}
	}

	/**
	 * After a portfolio change (import confirm, manual edit), reconcile the feed's subscriptions to
	 * the new holdings and push a fresh snapshot — so new tickers stream live prices without a
	 * restart. Runs after the committing transaction so the new positions are visible.
	 */
	@org.springframework.transaction.event.TransactionalEventListener(
			phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT,
			fallbackExecution = true)
	public void onPortfolioChanged(PortfolioChangedEvent event) {
		PriceFeed feed = priceFeed.getIfAvailable();
		if (feed != null) {
			feed.resubscribe();
		}
		live.pushCurrent();
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
