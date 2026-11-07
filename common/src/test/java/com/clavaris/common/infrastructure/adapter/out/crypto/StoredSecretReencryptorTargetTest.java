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
  void theStatementsAreBuiltFromTheCheckedParts() {
    final Target target = new Target("s", "t", "c", "p/", List.of("id"));

    assertThat(target.selectSql())
        .isEqualTo(
            "SELECT id, c AS stored, 'p/' || id AS context FROM t WHERE id > ? AND c IS NOT NULL"
                + " AND NOT starts_with(c, ?) ORDER BY id LIMIT ?");
    assertThat(target.updateSql()).isEqualTo("UPDATE t SET c = ? WHERE id = ? AND c = ?");
  }

  @Test
  void aTableThatIsNotAPlainIdentifierIsRefused() {
    final List<String> columns = List.of("id");

    assertThatThrownBy(() -> new Target("s", "t; drop table t", "c", "p/", columns))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aColumnThatIsNotAPlainIdentifierIsRefused() {
    final List<String> columns = List.of("id");

    assertThatThrownBy(() -> new Target("s", "t", "c) or (1=1", "p/", columns))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aContextColumnThatIsNotAPlainIdentifierIsRefused() {
    final List<String> columns = List.of("id || (select 1)");

    assertThatThrownBy(() -> new Target("s", "t", "c", "p/", columns))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aContextPrefixWithAQuoteIsRefused() {
    final List<String> columns = List.of("id");

    assertThatThrownBy(() -> new Target("s", "t", "c", "p'/", columns))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
