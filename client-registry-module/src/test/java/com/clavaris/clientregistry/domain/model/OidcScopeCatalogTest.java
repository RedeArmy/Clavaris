package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OidcScopeCatalogTest {

  @Test
  void knownListsExactlyTheFourConstants() {
    // Guards against a constant being added without also adding it to KNOWN, or vice versa — the
    // same "the derived list and the individual constants can't silently drift apart" property
    // PlatformScopes.BOOTSTRAP_DEFAULT relies on for OAuthClient/PlatformClient's own validators.
    // java:S3415: the actual value under test bound to a local first, not
    // assertThat(OidcScopeCatalog.KNOWN) directly — SonarCloud's own argument-order check reads a
    // bare Class.CONSTANT passed straight into assertThat(...) as looking like the *expected* side
    // of the assertion, regardless of which assertion method follows; a local variable is what the
    // rule actually wants to see standing in for "the real thing under test".
    final List<String> known = OidcScopeCatalog.KNOWN;

    assertThat(known)
        .containsExactlyInAnyOrder(
            OidcScopeCatalog.OPENID,
            OidcScopeCatalog.PROFILE,
            OidcScopeCatalog.EMAIL,
            OidcScopeCatalog.OFFLINE_ACCESS);
  }

  @Test
  void noKnownScopeFallsInTheReservedPlatformNamespace() {
    // The two vocabularies must never collide — see OidcScopeCatalog's own Javadoc. Same
    // local-variable-first shape as the test above, same java:S3415 reasoning.
    final List<String> known = OidcScopeCatalog.KNOWN;

    assertThat(known)
        .allSatisfy(scope -> assertThat(scope).doesNotStartWith(PlatformScopes.NAMESPACE_PREFIX));
  }
}
