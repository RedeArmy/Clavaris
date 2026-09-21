package com.clavaris.identity.application.usecase.revokeoauthgrant;

/**
 * Thrown when {@link com.clavaris.identity.application.usecase.listoauthgrantsforaccount
 * .OAuthGrantsRepository#revokeById} deletes nothing — a benign race (the grant's own token expired
 * and SAS already reaped the row) or a foreign {@code authorizationId}, indistinguishable by design
 * (same posture as {@code SessionNotFoundException}'s own Javadoc).
 */
public final class OAuthGrantNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OAuthGrantNotFoundException(final String authorizationId) {
    super("OAuth grant " + authorizationId + " not found");
  }
}
