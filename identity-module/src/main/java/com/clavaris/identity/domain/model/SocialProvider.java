package com.clavaris.identity.domain.model;

/**
 * ADR-0020: the social-login providers this codebase supports. Deliberately an extensible enum, not
 * a free-text column — {@code TD-FUT-022} (Microsoft) is a future value here, not a redesign,
 * exactly the "additive, not a rewrite" property ADR-0020 Decision 5 relies on.
 */
public enum SocialProvider {
  GOOGLE,
  GITHUB
}
