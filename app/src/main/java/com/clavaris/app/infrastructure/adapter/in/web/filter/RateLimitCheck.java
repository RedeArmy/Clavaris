package com.clavaris.app.infrastructure.adapter.in.web.filter;

import java.time.Duration;

/**
 * One counter to consume in a {@link RateLimiter#tryConsumeAll} batch (TD-PERF-046): the same three
 * values {@link RateLimiter#tryConsume} takes, so a request that matches several rules can hand all
 * of them to the limiter at once instead of one call each.
 */
public record RateLimitCheck(String key, int limit, Duration window) {}
