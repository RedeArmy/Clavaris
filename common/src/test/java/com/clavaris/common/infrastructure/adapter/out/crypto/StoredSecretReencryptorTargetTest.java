package com.clavaris.common.infrastructure.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clavaris.common.infrastructure.adapter.out.crypto.StoredSecretReencryptor.Target;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TD-SEC-089: the sweep puts a table, a column and the parts of a row's context into its SQL as
 * they are, so a {@link Target} refuses anything that is not a plain identifier or plain text.
 */
class StoredSecretReencryptorTargetTest {

  @Test
  void theContextIsThePrefixFollowedByTheColumnsJoinedWithASlash() {
    final Target target =
        new Target(
            "social-credential",
            "organization_social_credentials",
            "client_secret_encrypted",
            "organization_social_credentials.client_secret_encrypted/",
            List.of("organization_id", "provider"));

    assertThat(target.contextSql())
        .isEqualTo(
            "'organization_social_credentials.client_secret_encrypted/' || organization_id"
                + " || '/' || provider");
  }

  @Test
  void aTableThatIsNotAPlainIdentifierIsRefused() {
    assertThatThrownBy(() -> new Target("s", "t; drop table t", "c", "p/", List.of("id")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aColumnThatIsNotAPlainIdentifierIsRefused() {
    assertThatThrownBy(() -> new Target("s", "t", "c) or (1=1", "p/", List.of("id")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aContextColumnThatIsNotAPlainIdentifierIsRefused() {
    assertThatThrownBy(() -> new Target("s", "t", "c", "p/", List.of("id || (select 1)")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aContextPrefixWithAQuoteIsRefused() {
    assertThatThrownBy(() -> new Target("s", "t", "c", "p'/", List.of("id")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
