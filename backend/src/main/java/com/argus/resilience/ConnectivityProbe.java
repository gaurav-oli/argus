package com.argus.resilience;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Probes internet reachability on a schedule and feeds {@link PlatformModeService} (Story 10.4). A
 * lightweight HEAD to a highly-available host with a short timeout; any non-exception response counts
 * as online. Probe URL and cadence are configurable via {@code argus.resilience.*}.
 */
@Component
public class ConnectivityProbe {

	private static final Logger log = LoggerFactory.getLogger(ConnectivityProbe.class);

	private final PlatformModeService platform;
	private final String probeUrl;
	private final HttpClient http;

	public ConnectivityProbe(PlatformModeService platform,
			@Value("${argus.resilience.probe-url:https://one.one.one.one}") String probeUrl) {
		this.platform = platform;
		this.probeUrl = probeUrl;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@Scheduled(fixedDelayString = "${argus.resilience.probe-ms:30000}",
			initialDelayString = "${argus.resilience.probe-ms:30000}")
	public void probe() {
		platform.report(reachable(), Instant.now());
	}

	private boolean reachable() {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(probeUrl))
					.method("HEAD", HttpRequest.BodyPublishers.noBody())
					.timeout(Duration.ofSeconds(5))
					.build();
			return http.send(req, BodyHandlers.discarding()).statusCode() > 0;
		} catch (Exception ex) {
			log.debug("Connectivity probe to {} failed: {}", probeUrl, ex.getMessage());
			return false;
		}
	}
}
