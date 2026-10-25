package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The indexes behind the two lookups that scale with data volume, so a future migration cannot drop
 * them unnoticed. Measured before they existed (TD-PERF-037 to TD-PERF-039 in the debt register): a
 * token lookup in {@code oauth2_authorization} was a sequential scan (151 ms at 200,000 rows) and a
 * page of an Organization's users sorted the whole tenant (77 ms at 500,000 accounts).
 */
@SpringBootTest
@Import(TestMailSenderConfig.class)
@Testcontainers
class HotPathIndexesIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void everyTokenColumnSpringAuthorizationServerSearchesByIsIndexed() {
    assertThat(indexNames("oauth2_authorization"))
        .contains(
            "ix_oauth2_authorization_state",
            "ix_oauth2_authorization_authorization_code_value",
            "ix_oauth2_authorization_access_token_value",
            "ix_oauth2_authorization_oidc_id_token_value",
            "ix_oauth2_authorization_refresh_token_value",
            "ix_oauth2_authorization_user_code_value",
            "ix_oauth2_authorization_device_code_value");
  }

  @Test
  void theKeysetListOfAnOrganizationsAccountsHasAnIndexInItsSortOrder() {
    assertThat(indexNames("accounts")).contains("ix_accounts_organization_id_created_at_id");
  }

  // Postgres runs a cascade as "DELETE FROM child WHERE account_id = $1"; a partial index cannot
  // serve it, so these must be plain indexes (TD-PERF-039).
  @Test
  void everyForeignKeyAnAccountDeletionCascadesThroughHasAPlainIndex() {
    assertThat(indexDefinition("ix_sessions_account_id")).doesNotContain(" WHERE ");
    assertThat(indexDefinition("ix_refresh_tokens_account_id")).doesNotContain(" WHERE ");
    assertThat(indexDefinition("ix_refresh_tokens_session_id")).doesNotContain(" WHERE ");
  }

  private String indexDefinition(final String indexName) {
    return jdbcTemplate.queryForObject(
        "SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, indexName);
  }

  private List<String> indexNames(final String table) {
    return jdbcTemplate.queryForList(
        "SELECT indexname FROM pg_indexes WHERE tablename = ?", String.class, table);
  }
}
