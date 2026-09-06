package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OidcScopeCatalogTest {

  @Test
  void knownListsExactlyTheFourConstants() {
    // Guards against a constant being added without also adding it to KNOWN, or vice versa — the
    // same "the derived list and the individual constants can't silently drift apart" property
    // PlatformScopes.BOOTSTRAP_DEFAULT relies on for OAuthClient/PlatformClient's own validators.
    assertThat(OidcScopeCatalog.KNOWN)
        .containsExactlyInAnyOrder(
            OidcScopeCatalog.OPENID,
            OidcScopeCatalog.PROFILE,
            OidcScopeCatalog.EMAIL,
            OidcScopeCatalog.OFFLINE_ACCESS);
  }

  @Test
  void noKnownScopeFallsInTheReservedPlatformNamespace() {
    // The two vocabularies must never collide — see OidcScopeCatalog's own Javadoc.
    assertThat(OidcScopeCatalog.KNOWN)
        .noneMatch(scope -> scope.startsWith(PlatformScopes.NAMESPACE_PREFIX));
  }
}
