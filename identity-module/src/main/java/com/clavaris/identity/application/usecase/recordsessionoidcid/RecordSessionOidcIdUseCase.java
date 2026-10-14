package com.clavaris.identity.application.usecase.recordsessionoidcid;

import java.util.UUID;

/**
 * OIDC Core 12.2: keeps the {@code sid} of a Session's first ID token on the Session, so the ID
 * tokens its refreshes issue can repeat it.
 */
@FunctionalInterface
public interface RecordSessionOidcIdUseCase {

  /** Records {@code oidcSid} on the Session unless it already has one; an unknown id is ignored. */
  void handle(UUID sessionId, String oidcSid);
}
