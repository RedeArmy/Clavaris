package com.clavaris.app.infrastructure.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

class ReauthenticationMarkerTest {

  private static final Instant T0 = Instant.parse("2026-10-13T12:00:00Z");

  private static MockHttpServletRequest request(final MockHttpSession session, final String state) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/o/x/oauth2/authorize");
    request.setSession(session);
    request.setParameter("client_id", "c");
    request.setParameter("state", state);
    return request;
  }

  private static int notesIn(final MockHttpSession session) {
    return session.getAttribute(ReauthenticationMarker.SESSION_ATTRIBUTE) instanceof Map<?, ?> notes
        ? notes.size()
        : 0;
  }

  @Test
  void theNotesAreBoundedAndTheOldestGoesFirst() {
    MockHttpSession session = new MockHttpSession();
    for (int i = 0; i < ReauthenticationMarker.MAX_NOTES + 3; i++) {
      ReauthenticationMarker.leave(request(session, "s" + i), T0.plusSeconds(i));
    }

    assertThat(notesIn(session)).isEqualTo(ReauthenticationMarker.MAX_NOTES);
    Instant later = T0.plusSeconds(100);
    assertThat(ReauthenticationMarker.consumedByLoginSince(request(session, "s0"), later, later))
        .as("the oldest note was dropped")
        .isFalse();
    assertThat(
            ReauthenticationMarker.consumedByLoginSince(
                request(session, "s" + (ReauthenticationMarker.MAX_NOTES + 2)),
                T0.plusSeconds(50),
                T0.plusSeconds(60)))
        .as("the newest one is still there")
        .isTrue();
  }

  @Test
  void aNoteIsUsedUpWhenItCounts() {
    MockHttpSession session = new MockHttpSession();
    ReauthenticationMarker.leave(request(session, "a"), T0);

    assertThat(
            ReauthenticationMarker.consumedByLoginSince(
                request(session, "a"), T0.plusSeconds(5), T0.plusSeconds(6)))
        .isTrue();
    assertThat(
            ReauthenticationMarker.consumedByLoginSince(
                request(session, "a"), T0.plusSeconds(5), T0.plusSeconds(7)))
        .isFalse();
    assertThat(notesIn(session)).isZero();
  }

  @Test
  void aLoginBeforeTheNoteWasLeftNeverCounts() {
    MockHttpSession session = new MockHttpSession();
    ReauthenticationMarker.leave(request(session, "a"), T0);

    assertThat(
            ReauthenticationMarker.consumedByLoginSince(
                request(session, "a"), T0.minusSeconds(5), T0.plusSeconds(1)))
        .isFalse();
  }

  @Test
  void anExpiredNoteNeverCountsAndIsForgotten() {
    MockHttpSession session = new MockHttpSession();
    ReauthenticationMarker.leave(request(session, "a"), T0);
    Instant later = T0.plus(ReauthenticationMarker.TTL).plus(Duration.ofSeconds(1));

    assertThat(
            ReauthenticationMarker.consumedByLoginSince(
                request(session, "a"), later.minusSeconds(1), later))
        .isFalse();
    ReauthenticationMarker.discardExpired(request(session, "a"), later);
    assertThat(notesIn(session)).isZero();
  }
}
