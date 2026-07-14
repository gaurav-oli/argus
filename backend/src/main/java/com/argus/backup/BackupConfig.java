package com.argus.backup;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link BackupProperties} (mirrors NotificationConfig). */
@Configuration
@EnableConfigurationProperties(BackupProperties.class)
public class BackupConfig {
}
