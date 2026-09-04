package com.argus.marketdata;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds market-data configuration: Finnhub REST resilience ({@code argus.finnhub.resilience.*}) and
 * Alpha Vantage REST resilience ({@code argus.alpha-vantage.resilience.*}). */
@Configuration
@EnableConfigurationProperties({FinnhubResilienceProperties.class, AlphaVantageResilienceProperties.class})
public class MarketDataConfig {
}
