package com.argus.watchlist;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code argus.discovery.*} auto-discovery configuration (watchlist Phase 2). */
@Configuration
@EnableConfigurationProperties(DiscoveryProperties.class)
public class WatchlistConfig {
}
