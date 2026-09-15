package com.clavaris.clientregistry.domain.model;

/**
 * SDE-III review, 2026-09-15: signals a lost-update race on {@link OAuthClient}/{@link
 * OrganizationClient}/{@link PlatformClient} — none of the three had any concurrency guard (no
 * {@code @Version}), so a "revoke" and a "rotate secret" racing the same credential could silently
 * overwrite each other: rotate reads the row before deactivate's write commits, then saves back the
 * stale {@code active=true} it read, silently re-activating a client an operator believed was just
 * revoked — at exactly the moment correctness matters most, incident response on a compromised
 * client. Every JPA adapter's {@code save} now maps a real optimistic-lock conflict ({@code
 * org.springframework.dao.OptimisticLockingFailureException}, Spring's portable abstraction over
 * the underlying JPA/Hibernate exception — never referenced directly outside {@code
 * infrastructure.adapter.out.persistence}) to this single, shared, module-owned type, so {@code
 * application}/{@code infrastructure.adapter.in.web} never need to know which ORM produced the
 * conflict. One class for all three credential types (not three near-identical ones) — the failure
 * mode and the caller's correct response (409, retry with a fresh read) are identical regardless of
 * which credential lost the race.
 */
public final class ConcurrentClientModificationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ConcurrentClientModificationException(final String clientId) {
    super(
        "Client " + clientId + " was modified concurrently by another request — reload and retry");
  }
}
