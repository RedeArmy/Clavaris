package com.clavaris.webhook.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeleteWebhookEndpointConfirmationTokensTest {

  // A real Map-backed fake, not a chain of Mockito stubs — issue()/consume() only ever call
  // setAttribute/getAttribute/removeAttribute, and a real map exercises their actual read-your-
  // own-write semantics instead of scripting each call individually.
  private static HttpServletRequest requestWithSession() {
    final Map<String, Object> attributes = new HashMap<>();
    final HttpSession session = mock(HttpSession.class);
    doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
        .when(session)
        .setAttribute(anyString(), any());
    when(session.getAttribute(anyString())).thenAnswer(inv -> attributes.get(inv.getArgument(0)));
    doAnswer(invocation -> attributes.remove(invocation.getArgument(0)))
        .when(session)
        .removeAttribute(anyString());

    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getSession(true)).thenReturn(session);
    when(request.getSession(false)).thenReturn(session);
    return request;
  }

  @Test
  void aFreshlyIssuedTokenIsConsumedSuccessfullyOnce() {
    HttpServletRequest request = requestWithSession();
    UUID endpointId = UUID.randomUUID();

    String token = DeleteWebhookEndpointConfirmationTokens.issue(request, endpointId);

    assertThat(DeleteWebhookEndpointConfirmationTokens.consume(request, endpointId, token))
        .isTrue();
  }

  @Test
  void aTokenCannotBeConsumedTwiceEvenWithTheCorrectValue() {
    HttpServletRequest request = requestWithSession();
    UUID endpointId = UUID.randomUUID();
    String token = DeleteWebhookEndpointConfirmationTokens.issue(request, endpointId);
    DeleteWebhookEndpointConfirmationTokens.consume(request, endpointId, token);

    boolean secondAttempt =
        DeleteWebhookEndpointConfirmationTokens.consume(request, endpointId, token);

    assertThat(secondAttempt)
        .as("single-use: a stale confirm page resubmitted must never re-trigger a delete")
        .isFalse();
  }

  @Test
  void aWrongTokenValueIsRejectedAndStillInvalidatesTheRealOne() {
    HttpServletRequest request = requestWithSession();
    UUID endpointId = UUID.randomUUID();
    String realToken = DeleteWebhookEndpointConfirmationTokens.issue(request, endpointId);

    boolean wrongAttempt =
        DeleteWebhookEndpointConfirmationTokens.consume(request, endpointId, "not-the-real-token");

    assertThat(wrongAttempt).isFalse();
    assertThat(DeleteWebhookEndpointConfirmationTokens.consume(request, endpointId, realToken))
        .as("the real token was already invalidated by the wrong attempt above")
        .isFalse();
  }

  @Test
  void aTokenIssuedForADifferentEndpointDoesNotMatch() {
    HttpServletRequest request = requestWithSession();
    UUID endpointId = UUID.randomUUID();
    UUID otherEndpointId = UUID.randomUUID();
    String token = DeleteWebhookEndpointConfirmationTokens.issue(request, endpointId);

    assertThat(DeleteWebhookEndpointConfirmationTokens.consume(request, otherEndpointId, token))
        .isFalse();
  }

  @Test
  void consumingWithNoSessionAtAllReturnsFalse() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getSession(false)).thenReturn(null);

    assertThat(
            DeleteWebhookEndpointConfirmationTokens.consume(
                request, UUID.randomUUID(), "any-token"))
        .isFalse();
  }

  @Test
  void reIssuingForTheSameEndpointOverwritesThePreviousToken() {
    HttpServletRequest request = requestWithSession();
    UUID endpointId = UUID.randomUUID();
    String firstToken = DeleteWebhookEndpointConfirmationTokens.issue(request, endpointId);
    DeleteWebhookEndpointConfirmationTokens.issue(request, endpointId);

    assertThat(DeleteWebhookEndpointConfirmationTokens.consume(request, endpointId, firstToken))
        .as("the first token was overwritten by the second issue() call")
        .isFalse();
  }

  // The sessions are allow-listed JSON: a record of ours is written without a type id and cannot be
  // read back, which broke every later request of the session. Only a plain String is safe.
  @Test
  void whatTheSessionHoldsIsAPlainString() {
    final Map<String, Object> attributes = new HashMap<>();
    final HttpSession session = mock(HttpSession.class);
    doAnswer(invocation -> attributes.put(invocation.getArgument(0), invocation.getArgument(1)))
        .when(session)
        .setAttribute(anyString(), any());
    final HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getSession(true)).thenReturn(session);

    DeleteWebhookEndpointConfirmationTokens.issue(request, UUID.randomUUID());

    assertThat(attributes.values())
        .hasSize(1)
        .allSatisfy(v -> assertThat(v).isInstanceOf(String.class));
  }
}
