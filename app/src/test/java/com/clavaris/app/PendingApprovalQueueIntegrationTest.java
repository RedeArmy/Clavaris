package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.PlatformApiTestSupport;
import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.approveaccountregistration.ApproveAccountRegistrationCommand;
import com.clavaris.identity.application.usecase.approveaccountregistration.ApproveAccountRegistrationUseCase;
import com.clavaris.identity.application.usecase.listpendingaccountsfororganization.ListPendingAccountsForOrganizationQuery;
import com.clavaris.identity.application.usecase.listpendingaccountsfororganization.ListPendingAccountsForOrganizationUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.rejectaccountregistration.RejectAccountRegistrationCommand;
import com.clavaris.identity.application.usecase.rejectaccountregistration.RejectAccountRegistrationUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * The approval queue's query against real Postgres: only PENDING_APPROVAL rows of the asked
 * Organization, oldest first, counted and bounded, with the partial index in place, and a decision
 * taking an Account out of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class PendingApprovalQueueIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private AccountRepository accounts;
  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private ListPendingAccountsForOrganizationUseCase listPending;
  @Autowired private ApproveAccountRegistrationUseCase approve;
  @Autowired private RejectAccountRegistrationUseCase reject;
  @Autowired private JdbcTemplate jdbc;

  private final HttpClient client = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void theQueueHoldsOnlyThePendingAccountsOfTheOrganizationOldestFirstAndDecidingEmptiesIt()
      throws Exception {
    final OrganizationId org = new OrganizationId(createOrganization("Queue Co"));
    final OrganizationId other = new OrganizationId(createOrganization("Other Queue Co"));
    final Account first = pending(org, "first@x.test");
    final Account second = pending(org, "second@x.test");
    final Account third = pending(org, "third@x.test");
    // Registered within the same millisecond, so the order is set explicitly rather than slept for.
    setCreatedAt(first, 3);
    setCreatedAt(second, 2);
    setCreatedAt(third, 1);
    final Account active = Account.register(org, new Email("active@x.test"));
    active.attachPasswordCredential("not-a-real-hash-this-test-never-signs-in");
    accounts.insert(active);
    pending(other, "elsewhere@x.test");

    final var queue = listPending.handle(new ListPendingAccountsForOrganizationQuery(org, 10));

    assertThat(queue.total()).isEqualTo(3);
    assertThat(queue.accounts())
        .extracting(Account::id)
        .containsExactly(first.id(), second.id(), third.id());
    assertThat(queue.accounts()).allMatch(a -> a.status() == AccountStatus.PENDING_APPROVAL);
    assertThat(queue.accounts()).allMatch(a -> a.organizationId().equals(org));

    assertThat(listPending.handle(new ListPendingAccountsForOrganizationQuery(org, 2)).accounts())
        .as("the limit bounds the page, the total still says how many wait")
        .hasSize(2);
    assertThat(listPending.handle(new ListPendingAccountsForOrganizationQuery(org, 0)).total())
        .isEqualTo(3);

    approve.handle(
        new ApproveAccountRegistrationCommand(first.id(), AuditActor.platformClient("test")));
    reject.handle(
        new RejectAccountRegistrationCommand(
            second.id(), "spam", AuditActor.platformClient("test")));

    final var after = listPending.handle(new ListPendingAccountsForOrganizationQuery(org, 10));
    assertThat(after.total()).isEqualTo(1);
    assertThat(after.accounts()).extracting(Account::id).containsExactly(third.id());
    assertThat(accounts.findById(first.id()).orElseThrow().status())
        .isEqualTo(AccountStatus.ACTIVE);
    assertThat(accounts.findById(second.id()).orElseThrow().status())
        .isEqualTo(AccountStatus.REJECTED);
    assertThat(listPending.handle(new ListPendingAccountsForOrganizationQuery(other, 10)).total())
        .as("another Organization's queue is its own")
        .isEqualTo(1);
  }

  @Test
  void thePartialIndexExistsAndCoversOnlyThePendingRows() {
    final List<String> definitions =
        jdbc.queryForList(
            "select indexdef from pg_indexes where indexname = 'ix_accounts_pending_approval'",
            String.class);

    assertThat(definitions).hasSize(1);
    assertThat(definitions.get(0)).contains("PENDING_APPROVAL").contains("organization_id");
  }

  private void setCreatedAt(final Account account, final int minutesAgo) {
    jdbc.update(
        "update accounts set created_at = ? where id = ?",
        java.time.OffsetDateTime.now().minusMinutes(minutesAgo),
        account.id().value());
  }

  private Account pending(final OrganizationId org, final String email) {
    final Account account = Account.registerPendingApproval(org, new Email(email));
    // An Account is never valid with zero ways to sign in (BR-ID-02), so even a waiting one has
    // one.
    account.attachPasswordCredential("not-a-real-hash-this-test-never-signs-in");
    accounts.insert(account);
    return account;
  }

  private UUID createOrganization(final String name) throws IOException, InterruptedException {
    final String token =
        PlatformApiTestSupport.platformAccessToken(
            client,
            objectMapper,
            URI.create("http://localhost:" + port + "/oauth2/token"),
            "platform:organizations:write");
    final HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/v1/admin/organizations"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"name\":\""
                            + name
                            + "\",\"ownerPlatformAccountId\":\""
                            + PlatformApiTestSupport.registerAPlatformAccount(platformAccounts)
                            + "\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }
}
