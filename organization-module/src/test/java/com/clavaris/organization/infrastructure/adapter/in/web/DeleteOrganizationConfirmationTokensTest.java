package com.clavaris.organization.infrastructure.adapter.in.web;

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

class DeleteOrganizationConfirmationTokensTest {

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
    UUID organizationId = UUID.randomUUID();

    String token = DeleteOrganizationConfirmationTokens.issue(request, organizationId);

    assertThat(DeleteOrganizationConfirmationTokens.consume(request, organizationId, token))
        .isTrue();
  }

  @Test
  void aTokenCannotBeConsumedTwiceEvenWithTheCorrectValue() {
    HttpServletRequest request = requestWithSession();
    UUID organizationId = UUID.randomUUID();
    String token = DeleteOrganizationConfirmationTokens.issue(request, organizationId);
    DeleteOrganizationConfirmationTokens.consume(request, organizationId, token);

    boolean secondAttempt =
        DeleteOrganizationConfirmationTokens.consume(request, organizationId, token);

    assertThat(secondAttempt)
        .as("single-use: a stale confirm page resubmitted must never re-trigger a delete")
        .isFalse();
  }

  @Test
  void aWrongTokenValueIsRejectedAndStillInvalidatesTheRealOne() {
    HttpServletRequest request = requestWithSession();
    UUID organizationId = UUID.randomUUID();
    String realToken = DeleteOrganizationConfirmationTokens.issue(request, organizationId);

    boolean wrongAttempt =
        DeleteOrganizationConfirmationTokens.consume(request, organizationId, "not-the-real-token");

    assertThat(wrongAttempt).isFalse();
    assertThat(DeleteOrganizationConfirmationTokens.consume(request, organizationId, realToken))
        .as("the real token was already invalidated by the wrong attempt above")
        .isFalse();
  }

  @Test
  void aTokenIssuedForADifferentOrganizationDoesNotMatch() {
    HttpServletRequest request = requestWithSession();
    UUID organizationId = UUID.randomUUID();
    UUID otherOrganizationId = UUID.randomUUID();
    String token = DeleteOrganizationConfirmationTokens.issue(request, organizationId);

    assertThat(DeleteOrganizationConfirmationTokens.consume(request, otherOrganizationId, token))
        .isFalse();
  }

  @Test
  void consumingWithNoSessionAtAllReturnsFalse() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getSession(false)).thenReturn(null);

    assertThat(
            DeleteOrganizationConfirmationTokens.consume(request, UUID.randomUUID(), "any-token"))
        .isFalse();
  }

  @Test
  void reIssuingForTheSameOrganizationOverwritesThePreviousToken() {
    HttpServletRequest request = requestWithSession();
    UUID organizationId = UUID.randomUUID();
    String firstToken = DeleteOrganizationConfirmationTokens.issue(request, organizationId);
    DeleteOrganizationConfirmationTokens.issue(request, organizationId);

    assertThat(DeleteOrganizationConfirmationTokens.consume(request, organizationId, firstToken))
        .as("the first token was overwritten by the second issue() call")
        .isFalse();
  }
}
