package com.clavaris.webhook.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * ADR-0007 §2: the {@code Clavaris-Signature} header — Stripe/Svix/Clerk's own well-recognized
 * shape ({@code t=<timestamp>,v1=<hex_hmac>}), signed HMAC-SHA256 over {@code timestamp + "." +
 * raw_body}, chosen deliberately because consumer developers already recognize it and existing
 * verification libraries exist for it. Pure function, no framework, no I/O — {@code rawSecrets}
 * arrives already decrypted (that's the caller's, {@code DeliverPendingWebhooksService}'s, job via
 * {@code WebhookSigningSecretCipher}); this class never touches encrypted material.
 *
 * <p>More than one raw secret produces more than one {@code v1=} entry in one header value,
 * comma-separated — {@link
 * com.clavaris.webhook.domain.model.WebhookEndpoint#activeSecretsEncrypted} returns two entries
 * during a rotation's overlap window, and a consumer verifying against either one accepts the
 * delivery, so signing with both means no delivery is ever rejected mid-rotation.
 */
public final class WebhookSignature {

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private WebhookSignature() {}

  public static String header(
      final Instant timestamp, final String rawBody, final List<String> rawSecrets) {
    final long epochSeconds = timestamp.getEpochSecond();
    final StringBuilder header = new StringBuilder("t=").append(epochSeconds);
    for (final String rawSecret : rawSecrets) {
      header.append(",v1=").append(hmacHex(epochSeconds, rawBody, rawSecret));
    }
    return header.toString();
  }

  private static String hmacHex(
      final long epochSeconds, final String rawBody, final String rawSecret) {
    final String signedPayload = epochSeconds + "." + rawBody;
    try {
      final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(rawSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      final byte[] digest = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
      // HmacSHA256 is a JDK-guaranteed algorithm on every conformant JVM — this is a programming
      // error (a malformed key), not a runtime condition callers should have to handle, same "fail
      // loudly, this can't legitimately happen" stance as JpaEventOutboxWriter's own serialization-
      // failure translation.
      throw new IllegalStateException("Failed to compute webhook HMAC signature", e);
    }
  }
}
