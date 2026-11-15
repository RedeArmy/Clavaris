package com.clavaris.clientregistry.domain.model;

/**
 * Whether an {@link OAuthClient} must prove possession of the authorization code with PKCE (RFC
 * 7636) when it asks for one. ADR-0043, which relaxes BR-CLIENT-03 from "every client, always" to a
 * per-client decision with a strict default.
 *
 * <ul>
 *   <li>{@link #REQUIRED}: an authorization request without a {@code code_challenge} is refused.
 *       The default for every new client and the only value any client had before the policy
 *       existed.
 *   <li>{@link #OPTIONAL}: a request without a {@code code_challenge} is accepted, because a
 *       confidential client already authenticates at the token endpoint with its secret and a
 *       library or product that does not send PKCE (most server-side OIDC libraries before OAuth
 *       2.1, and the OpenID Foundation's Basic OP conformance plan) could not integrate otherwise.
 *       A request that does send a challenge is still checked against its verifier, and a verifier
 *       sent with no challenge is refused (RFC 9700 section 4.8, by Spring Authorization Server):
 *       choosing this value removes a requirement, never a check on what was sent.
 * </ul>
 *
 * <p>OPTIONAL is a decision about a <b>confidential</b> client only. A client with no secret has
 * nothing but PKCE to bind the code to the party that asked for it, so {@link
 * #requireAllowedFor(boolean, PkcePolicy)} refuses OPTIONAL for one, whatever asks for it. Every
 * client is confidential today (ADR-0013); the rule is here so the first public client (TD-FUT-071)
 * cannot be registered weaker than its own protocol allows.
 */
public enum PkcePolicy {
  REQUIRED,
  OPTIONAL;

  /** Whether Spring Authorization Server must refuse an authorization request with no challenge. */
  public boolean enforced() {
    return this == REQUIRED;
  }

  /**
   * Refuses {@link #OPTIONAL} for a client that cannot authenticate with a secret.
   *
   * @throws IllegalArgumentException if the client is public and {@code policy} is OPTIONAL
   */
  public static PkcePolicy requireAllowedFor(final boolean confidential, final PkcePolicy policy) {
    if (policy == null) {
      throw new IllegalArgumentException("pkcePolicy must not be null");
    }
    if (!confidential && policy == OPTIONAL) {
      throw new IllegalArgumentException(
          "PKCE can only be optional for a confidential client: a client with no secret has no"
              + " other proof that the party redeeming the code is the one that asked for it");
    }
    return policy;
  }
}
