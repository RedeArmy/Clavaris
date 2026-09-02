package com.clavaris.webhook.application.usecase.deliverpendingwebhooks;

import java.util.Map;

/**
 * Outbound port — implemented by {@code infrastructure/adapter/out/http/JdkHttpWebhookSender}
 * (plain {@code java.net.http.HttpClient}, same JDK-native choice identity-module's own {@code
 * ResendHttpClient} already makes for its own outbound HTTP calls, no new dependency needed).
 */
@FunctionalInterface
public interface WebhookHttpSender {

  WebhookDeliveryOutcome send(String url, Map<String, String> headers, String body);
}
