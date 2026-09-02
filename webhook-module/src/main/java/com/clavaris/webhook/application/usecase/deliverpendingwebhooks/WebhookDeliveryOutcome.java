package com.clavaris.webhook.application.usecase.deliverpendingwebhooks;

/**
 * @param statusCode {@code null} only when the request never got a response at all (timeout,
 *     connection refused, DNS failure, ...) — {@code success} is the single field callers should
 *     branch on, {@code statusCode}/{@code errorMessage} are for the audit trail ({@code
 *     WebhookDelivery.lastResponseStatus}/{@code lastError}).
 * @param errorMessage BR-DATA-01: a short, safe-to-log reason only — never the response body (a
 *     consumer's own error page could echo back anything, including data this Organization's own
 *     account pool might carry).
 */
public record WebhookDeliveryOutcome(boolean success, Integer statusCode, String errorMessage) {}
