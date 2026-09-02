package com.clavaris.webhook.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

  @Test
  void headerCarriesTheTimestampAndOneV1EntryPerSecret() {
    Instant timestamp = Instant.ofEpochSecond(1_700_000_000L);

    String header = WebhookSignature.header(timestamp, "{\"a\":1}", List.of("secret-a"));

    assertThat(header).startsWith("t=1700000000,v1=").containsOnlyOnce("v1=");
  }

  @Test
  void twoSecretsProduceTwoV1EntriesInTheSameHeaderValue_rotationOverlap() {
    Instant timestamp = Instant.ofEpochSecond(1_700_000_000L);

    String header =
        WebhookSignature.header(timestamp, "{\"a\":1}", List.of("secret-a", "secret-b"));

    long v1Count = header.split(",v1=", -1).length - 1L;
    assertThat(v1Count).isEqualTo(2);
  }

  @Test
  void signatureMatchesAnIndependentlyComputedHmacSha256() throws Exception {
    Instant timestamp = Instant.ofEpochSecond(42);
    String rawBody = "{\"event\":\"account.created\"}";
    String secret = "the-signing-secret";

    String header = WebhookSignature.header(timestamp, rawBody, List.of(secret));

    String signedPayload = "42." + rawBody;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String expectedHex =
        HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));

    assertThat(header).isEqualTo("t=42,v1=" + expectedHex);
  }

  @Test
  void differentSecretsProduceDifferentSignaturesForTheSamePayload() {
    Instant timestamp = Instant.now();

    String headerA = WebhookSignature.header(timestamp, "{}", List.of("secret-a"));
    String headerB = WebhookSignature.header(timestamp, "{}", List.of("secret-b"));

    assertThat(headerA).isNotEqualTo(headerB);
  }

  @Test
  void sameInputsAlwaysProduceTheSameSignature_deterministic() {
    Instant timestamp = Instant.ofEpochSecond(123);

    String first = WebhookSignature.header(timestamp, "payload", List.of("secret"));
    String second = WebhookSignature.header(timestamp, "payload", List.of("secret"));

    assertThat(first).isEqualTo(second);
  }
}
