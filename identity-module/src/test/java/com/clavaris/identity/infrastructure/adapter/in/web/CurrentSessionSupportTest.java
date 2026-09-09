package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * SDE-III optimization pass, P2 point 3: dedicated coverage for the shared helper extracted from
 * {@link AccountSessionsController}/{@link PlatformAccountSessionsController} — see {@link
 * CurrentSessionSupport}'s own Javadoc for the full extraction rationale.
 */
class CurrentSessionSupportTest {

  @Test
  void currentSessionIdIsNullWhenNoSessionExistsYet() {
    MockHttpServletRequest request = new MockHttpServletRequest();

    assertThat(CurrentSessionSupport.currentSessionId(request)).isNull();
  }

  @Test
  void currentSessionIdReturnsTheRealSessionIdOnceOneExists() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    String sessionId = request.getSession(true).getId();

    assertThat(CurrentSessionSupport.currentSessionId(request)).isEqualTo(sessionId);
  }

  @Test
  void requireResolvedReturnsThePresentValueUnwrapped() {
    String resolved = CurrentSessionSupport.requireResolved(Optional.of("a-value"), "Account");

    assertThat(resolved).isEqualTo("a-value");
  }

  @Test
  void requireResolvedThrowsADescriptiveIllegalStateExceptionWhenEmpty() {
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () -> CurrentSessionSupport.requireResolved(Optional.empty(), "PlatformAccount"))
        .withMessageContaining("PlatformAccount");
  }
}
