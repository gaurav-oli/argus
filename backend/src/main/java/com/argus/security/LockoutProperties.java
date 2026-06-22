package com.argus.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Failed-attempt lockout thresholds (FR-38 / Story 2.6). Defaults: 3 fails → 30s, 5 → 10m (+ alert),
 * 10 → full lock (clearable only from another authenticated device).
 *
 * @param warnThreshold     consecutive failures that trigger the short lockout
 * @param warnLockout       short lockout duration
 * @param alertThreshold    failures that trigger the longer lockout + secondary-device alert
 * @param alertLockout      longer lockout duration
 * @param fullLockThreshold failures that trigger a full lock requiring another device to clear
 */
@ConfigurationProperties("argus.security.lockout")
public record LockoutProperties(
		@DefaultValue("3") int warnThreshold,
		@DefaultValue("30s") Duration warnLockout,
		@DefaultValue("5") int alertThreshold,
		@DefaultValue("10m") Duration alertLockout,
		@DefaultValue("10") int fullLockThreshold) {
}
