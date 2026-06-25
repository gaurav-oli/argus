package com.argus.portfolio;

/**
 * Published when the set of held positions changes (statement import confirm, manual add/remove).
 * {@link PriceFeedStarter} listens and re-subscribes the live price feed to the new symbol set so
 * freshly-added tickers get live prices without a backend restart.
 */
public record PortfolioChangedEvent() {
}
