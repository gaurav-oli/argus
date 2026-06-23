package com.argus.calendar;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds Agent 7 calendar configuration ({@code argus.calendar.*}, Story 5.1). */
@Configuration
@EnableConfigurationProperties(CalendarProperties.class)
public class CalendarConfig {
}
