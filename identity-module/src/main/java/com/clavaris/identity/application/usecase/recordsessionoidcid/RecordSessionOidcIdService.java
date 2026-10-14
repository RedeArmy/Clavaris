package com.clavaris.identity.application.usecase.recordsessionoidcid;

import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Orchestration for {@link RecordSessionOidcIdUseCase}. */
public class RecordSessionOidcIdService implements RecordSessionOidcIdUseCase {

  private final SessionRepository sessions;

  public RecordSessionOidcIdService(final SessionRepository sessions) {
    this.sessions = sessions;
  }

  @Override
  @Transactional
  public void handle(final UUID sessionId, final String oidcSid) {
    sessions
        .findById(sessionId)
        .ifPresent(
            session -> {
              if (session.oidcSid().isEmpty()) {
                session.recordOidcSid(oidcSid);
                sessions.save(session);
              }
            });
  }
}
