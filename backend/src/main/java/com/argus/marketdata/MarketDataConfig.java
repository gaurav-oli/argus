package com.argus.marketdata;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds market-data configuration, incl. Finnhub REST resilience ({@code argus.finnhub.resilience.*}). */
@Configuration
@EnableConfigurationProperties(FinnhubResilienceProperties.class)
public class MarketDataConfig {
}
