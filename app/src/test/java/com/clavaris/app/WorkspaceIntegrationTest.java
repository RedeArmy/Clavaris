package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccount;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0010 §3 addendum (Workspace, 2026-08-27): end to end against real code, real Postgres, real
 * HTTP — same "confirmed live, not assumed" bar as {@code DeleteAccountIntegrationTest}/{@code
 * DeleteOrganizationIntegrationTest}. Covers the whole v1 flow (create workspace, add a member —
 * provisioning a real {@code Account} and triggering a real password-reset email, change role,
 * remove member) plus the two ADR-0007 cascade obligations this feature closed: {@code
 * WorkspaceMembership} erasure on {@code Account} hard-delete ({@link
 * com.clavaris.app.infrastructure.config.WorkspaceMembershipEraserBridge}) and the free DB-level
 * cascade on {@code Organization} hard-delete.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class WorkspaceIntegrationTest extends RedisBackedIntegrationTest {

  private static final String FULL_SCOPE =
      "platform:organizations:write platform:workspaces:write platform:workspace-members:write"
          + " platform:workspace-members:remove platform:accounts:delete"
          + " platform:organizations:delete";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MailSender mailSender;

  private final HttpClient httpClient =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void addingAMemberProvisionsARealAccountAndTriggersTheirOwnPasswordResetEmail() throws Exception {
    String platformToken = requestPlatformAccessToken(FULL_SCOPE);
    // SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis):
    // every new Organization now defaults to DEVELOPMENT, which never sends a real outbound email
    // (BR-ID-15) — this test's own point is proving a real welcome email fires, so it needs a real
    // PRODUCTION Organization, not the sandbox default every other test in this file is indifferent
    // to.
    UUID organizationId =
        promoteToProduction(platformToken, createOrganization(platformToken, "Workspace Flow Co"));
    UUID workspaceId = createWorkspace(platformToken, organizationId, "Engineering");

    JsonNode membership = addMember(platformToken, workspaceId, "new-member@example.com", null);

    assertThat(membership.get("role").asString()).isEqualTo("MEMBER");
    UUID accountId = UUID.fromString(membership.get("accountId").asString());
    Integer accountRows =
        jdbcTemplate.queryForObject(
            "select count(*) from accounts where id = ? and organization_id = ? and email = ?",
            Integer.class,
            accountId,
            organizationId,
            "new-member@example.com");
    assertThat(accountRows).as("a real Account row must exist for the new member").isEqualTo(1);
    verify(mailSender)
        .sendPasswordReset(
            eq("new-member@example.com"),
            eq(new OrganizationId(organizationId)),
            org.mockito.ArgumentMatchers.anyString());

    Integer membershipRows =
        jdbcTemplate.queryForObject(
            "select count(*) from workspace_memberships where workspace_id = ? and account_id = ?",
            Integer.class,
            workspaceId,
            accountId);
    assertThat(membershipRows).isEqualTo(1);
  }

  @Test
  void changingRoleAndRemovingAMemberWorkEndToEnd() throws Exception {
    String platformToken = requestPlatformAccessToken(FULL_SCOPE);
    UUID organizationId = createOrganization(platformToken, "Role Change Co");
    UUID workspaceId = createWorkspace(platformToken, organizationId, "Sales");
    // BR-WS-01's replacement invariant needs a second ADMIN in place before the first can be
    // demoted/removed — two members, both created as ADMIN, then one demoted to MEMBER and
    // removed, proving the whole lifecycle, not just the happy-path single call.
    JsonNode firstAdmin = addMember(platformToken, workspaceId, "admin-one@example.com", "ADMIN");
    JsonNode secondAdmin = addMember(platformToken, workspaceId, "admin-two@example.com", "ADMIN");
    UUID firstAccountId = UUID.fromString(firstAdmin.get("accountId").asString());
    UUID secondAccountId = UUID.fromString(secondAdmin.get("accountId").asString());

    // Demotes the first admin — secondAccountId remains the workspace's own last ADMIN, so
    // removing the (now plain member) firstAccountId next is always safe and never trips
    // BR-WS-01's replacement invariant.
    HttpResponse<String> changeRoleResponse =
        changeRole(platformToken, workspaceId, firstAccountId, "MEMBER");
    assertThat(changeRoleResponse.statusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(changeRoleResponse.body()).get("role").asString())
        .isEqualTo("MEMBER");

    HttpResponse<String> removeResponse = removeMember(platformToken, workspaceId, firstAccountId);
    assertThat(removeResponse.statusCode()).isEqualTo(204);
    Integer remainingMemberships =
        jdbcTemplate.queryForObject(
            "select count(*) from workspace_memberships where workspace_id = ? and account_id = ?",
            Integer.class,
            workspaceId,
            firstAccountId);
    assertThat(remainingMemberships).isZero();
    // secondAccountId (still ADMIN throughout) must remain untouched by the removal above.
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from workspace_memberships where workspace_id = ? and account_id"
                    + " = ?",
                Integer.class,
                workspaceId,
                secondAccountId))
        .isEqualTo(1);
  }

  @Test
  void rejectsRemovingTheLastAdminOfAWorkspace() throws Exception {
    String platformToken = requestPlatformAccessToken(FULL_SCOPE);
    UUID organizationId = createOrganization(platformToken, "Last Admin Co");
    UUID workspaceId = createWorkspace(platformToken, organizationId, "Ops");
    JsonNode onlyAdmin = addMember(platformToken, workspaceId, "only-admin@example.com", "ADMIN");
    UUID accountId = UUID.fromString(onlyAdmin.get("accountId").asString());

    HttpResponse<String> removeResponse = removeMember(platformToken, workspaceId, accountId);

    assertThat(removeResponse.statusCode()).isEqualTo(409);
    Integer remaining =
        jdbcTemplate.queryForObject(
            "select count(*) from workspace_memberships where workspace_id = ? and account_id = ?",
            Integer.class,
            workspaceId,
            accountId);
    assertThat(remaining).isEqualTo(1);
  }

  @Test
  void rejectsAddingAMemberWithAnAlreadyRegisteredEmail() throws Exception {
    String platformToken = requestPlatformAccessToken(FULL_SCOPE);
    UUID organizationId = createOrganization(platformToken, "Duplicate Email Co");
    UUID workspaceId = createWorkspace(platformToken, organizationId, "Support");
    addMember(platformToken, workspaceId, "taken@example.com", null);

    HttpResponse<String> secondAttempt =
        addMemberRaw(platformToken, workspaceId, "taken@example.com", null);

    assertThat(secondAttempt.statusCode()).isEqualTo(409);
  }

  // ADR-0007: DeleteAccountService must erase every WorkspaceMembership row for the deleted
  // Account, synchronously, in the same transaction — closes the gap that class's own Javadoc used
  // to document explicitly (nothing to clean up before Workspace existed).
  @Test
  void deletingAnAccountErasesItsWorkspaceMemberships() throws Exception {
    String platformToken = requestPlatformAccessToken(FULL_SCOPE);
    UUID organizationId = createOrganization(platformToken, "Account Delete Cascade Co");
    UUID workspaceId = createWorkspace(platformToken, organizationId, "Engineering");
    JsonNode member = addMember(platformToken, workspaceId, "will-be-deleted@example.com", null);
    UUID accountId = UUID.fromString(member.get("accountId").asString());
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from workspace_memberships where account_id = ?",
                Integer.class,
                accountId))
        .isEqualTo(1);

    HttpResponse<String> deleteResponse = deleteAccount(platformToken, accountId);

    assertThat(deleteResponse.statusCode()).isEqualTo(204);
    Integer remainingMemberships =
        jdbcTemplate.queryForObject(
            "select count(*) from workspace_memberships where account_id = ?",
            Integer.class,
            accountId);
    assertThat(remainingMemberships)
        .as("no dangling membership row must survive the Account")
        .isZero();
  }

  // ADR-0010 §3 addendum: workspaces/workspace_memberships both cascade at the DB level (ON DELETE
  // CASCADE, same-module FK) when the owning Organization is hard-deleted — no application-layer
  // erasure code needed for this direction.
  @Test
  void deletingAnOrganizationCascadesAwayItsWorkspacesAndMemberships() throws Exception {
    String platformToken = requestPlatformAccessToken(FULL_SCOPE);
    UUID organizationId = createOrganization(platformToken, "Organization Delete Cascade Co");
    UUID workspaceId = createWorkspace(platformToken, organizationId, "Engineering");
    addMember(platformToken, workspaceId, "member@example.com", null);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from workspace_memberships where workspace_id = ?",
                Integer.class,
                workspaceId))
        .isEqualTo(1);

    HttpResponse<String> deleteResponse = deleteOrganization(platformToken, organizationId);

    assertThat(deleteResponse.statusCode()).isEqualTo(204);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from workspaces where id = ?", Integer.class, workspaceId))
        .as("the Workspace row itself must be gone")
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from workspace_memberships where workspace_id = ?",
                Integer.class,
                workspaceId))
        .as("its memberships must cascade away with it")
        .isZero();
  }

  @Test
  void returns404WhenAddingAMemberToAnUnknownWorkspace() throws Exception {
    String platformToken = requestPlatformAccessToken(FULL_SCOPE);

    HttpResponse<String> response =
        addMemberRaw(platformToken, UUID.randomUUID(), "someone@example.com", null);

    assertThat(response.statusCode()).isEqualTo(404);
  }

  private JsonNode addMember(String platformToken, UUID workspaceId, String email, String role)
      throws IOException, InterruptedException {
    HttpResponse<String> response = addMemberRaw(platformToken, workspaceId, email, role);
    assertThat(response.statusCode()).isEqualTo(201);
    return objectMapper.readTree(response.body());
  }

  private HttpResponse<String> addMemberRaw(
      String platformToken, UUID workspaceId, String email, String role)
      throws IOException, InterruptedException {
    String body =
        role == null
            ? "{\"email\":\"" + email + "\"}"
            : "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/workspaces/" + workspaceId + "/members"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> changeRole(
      String platformToken, UUID workspaceId, UUID accountId, String newRole)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri(
                    "/api/v1/admin/workspaces/" + workspaceId + "/members/" + accountId + "/role"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"role\":\"" + newRole + "\"}"))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> removeMember(String platformToken, UUID workspaceId, UUID accountId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri(
                    "/api/v1/admin/workspaces/"
                        + workspaceId
                        + "/members/"
                        + accountId
                        + ":remove"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> deleteAccount(String platformToken, UUID accountId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/accounts/" + accountId + ":delete"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> deleteOrganization(String platformToken, UUID organizationId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/organizations/" + organizationId + ":delete"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private UUID createWorkspace(String platformToken, UUID organizationId, String name)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/workspaces"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name + "\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  private String requestPlatformAccessToken(String scope) throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + scope))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readTree(response.body()).get("access_token").asString();
  }

  private UUID createOrganization(String platformToken, String name)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/organizations"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"name\":\""
                        + name
                        + "\",\"ownerPlatformAccountId\":\""
                        + registerAPlatformAccount()
                        + "\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  // SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis): a fresh
  // Organization now defaults to DEVELOPMENT (BR-ORG-08), which never sends real outbound email
  // (BR-ID-15) — a test proving a real email fires needs an actual PRODUCTION Organization.
  private UUID promoteToProduction(String platformToken, UUID developmentOrganizationId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri(
                    "/api/v1/admin/organizations/"
                        + developmentOrganizationId
                        + ":create-production-environment"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Production\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  private UUID registerAPlatformAccount() {
    PlatformAccount account =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    account.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(account);
    return account.id().value();
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
