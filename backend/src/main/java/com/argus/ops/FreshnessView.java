package com.argus.ops;

import java.util.List;

/** Data-freshness rollup for the Ops dashboard (Story 9.7). {@code anyStale} drives the header alert. */
public record FreshnessView(List<SourceFreshness> sources, boolean anyStale) {
}
