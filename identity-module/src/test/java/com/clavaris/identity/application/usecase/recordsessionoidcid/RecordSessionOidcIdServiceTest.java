package com.clavaris.identity.application.usecase.recordsessionoidcid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Session;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordSessionOidcIdServiceTest {

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final RecordSessionOidcIdService service = new RecordSessionOidcIdService(sessions);

  @Test
  void recordsTheSidOnASessionThatHasNone() {
    Session session = Session.open(AccountId.newId(), List.of("openid"));
    when(sessions.findById(session.id())).thenReturn(Optional.of(session));

    service.handle(session.id(), "the-sid");

    assertThat(session.oidcSid()).contains("the-sid");
    verify(sessions).save(session);
  }

  @Test
  void neverReplacesTheSidASessionAlreadyHas() {
    Session session = Session.open(AccountId.newId(), List.of("openid"));
    session.recordOidcSid("first");
    when(sessions.findById(session.id())).thenReturn(Optional.of(session));

    service.handle(session.id(), "second");

    assertThat(session.oidcSid()).contains("first");
    verify(sessions, never()).save(any());
  }

  @Test
  void ignoresAnUnknownSession() {
    UUID unknown = UUID.randomUUID();
    when(sessions.findById(unknown)).thenReturn(Optional.empty());

    service.handle(unknown, "the-sid");

    verify(sessions, never()).save(any());
  }
}
