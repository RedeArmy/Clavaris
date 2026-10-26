package com.clavaris.identity.domain.model;

/**
 * TD-ARCH-037: another writer changed this account between the moment it was loaded and the moment
 * it was saved back. {@code AccountRepository#save} writes every column of what the caller loaded,
 * so without this check the second writer silently undid the first (an operator's suspension, a
 * consuming application's profile update). The caller is refused instead and should reload and
 * retry; the REST admin API answers 409 and the dashboard shows its "conflicts with a recent
 * change" message. Unchecked, like {@code ConcurrentClientModificationException}, because every
 * caller reaches it the same way and none can do more than report it.
 */
public final class ConcurrentAccountModificationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ConcurrentAccountModificationException(final AccountId accountId) {
    super("Account " + accountId.value() + " was modified by another request; reload and retry");
  }
}
