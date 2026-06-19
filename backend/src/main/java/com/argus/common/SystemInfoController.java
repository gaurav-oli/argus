package com.argus.common;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sample REST endpoint proving typed JSON responses + RFC 9457 errors. */
@RestController
@RequestMapping("/api/system-info")
public class SystemInfoController {

	private final Environment environment;
	private final String appName;

	public SystemInfoController(Environment environment,
			@Value("${spring.application.name:argus}") String appName) {
		this.environment = environment;
		this.appName = appName;
	}

	@GetMapping
	public SystemInfo current() {
		String profile = String.join(",", environment.getActiveProfiles());
		return new SystemInfo(appName, "0.0.1-SNAPSHOT", profile, Instant.now());
	}

	@GetMapping("/{key}")
	public SystemInfo byKey(@PathVariable String key) {
		if (!"current".equals(key)) {
			throw new NotFoundException("system-info", key);
		}
		return current();
	}
}
