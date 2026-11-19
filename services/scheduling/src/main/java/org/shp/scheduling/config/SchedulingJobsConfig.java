package org.shp.scheduling.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables Spring {@code @Scheduled} background jobs for the scheduling service. */
@Configuration
@EnableScheduling
public class SchedulingJobsConfig {}
