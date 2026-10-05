package com.argus.portfolio;

import com.argus.marketdata.ListingResolver;
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
	private final ListingResolver listing;

	public PriceFeedStarter(ObjectProvider<PriceFeed> priceFeed, PositionRepository positions,
			LivePortfolioService live, ListingResolver listing) {
		this.priceFeed = priceFeed;
		this.positions = positions;
		this.live = live;
		this.listing = listing;
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

	/** Every ticker anyone holds, across ALL users (Phase 2) — the feed's own socket thread has no
	 * signed-in user on it, so the normal @TenantId-scoped finder would see nothing; streaming a live
	 * PRICE for a ticker is not financial data about any one person, unlike the position itself.
	 *
	 * <p>Declared TSX tickers ({@link ListingResolver}) are excluded: Finnhub's free tier doesn't
	 * cover the TSX, and subscribing to the bare symbol can silently stream a DIFFERENT US instrument
	 * instead (e.g. bare {@code DOL} is a US ETF, not Dollarama) — a wrong-but-present price that then
	 * never shows up in {@link LivePortfolioService#unpricedHeldTickers()} for the TSX feed to correct.
	 * Leaving these tickers unsubscribed here is what lets {@link
	 * com.argus.marketdata.CanadianEtfPriceFeed} be the one source of truth for them. */
	private Collection<String> heldTickers() {
		return positions.allTickersAcrossAllUsers().stream()
				.filter(t -> !listing.isTsx(t))
				.toList();
	}
}
