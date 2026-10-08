package com.codesync.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled (used by DocumentService to save room code every few seconds). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
