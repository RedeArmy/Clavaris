# First Vertical Slice Blueprint — reference template for every use case

🟡 En revisión — illustrative, not yet implemented (no application classes exist yet)

## 1. Purpose

The project's own conventions fix the folder shape (`domain/` → `application/usecase/<name>/` → `infrastructure/adapter/{in,out}/`) but don't show it filled in with real code. This document works through **two** concrete use cases end-to-end — one per architectural concern that needed a worked example — so that when implementation actually starts (roadmap v1), every subsequent use case has a template to copy instead of re-deriving the pattern from principles each time.

This is not a spec to build *now*. It exists to make four things concrete, each demonstrated against a real use case rather than described abstractly:

| Concern | Demonstrated by |
|---|---|
| DDD + Hexagonal + Vertical Slice folder shape | Example A — `RegisterAccount` |
| Correct concurrency handling under a race | Example A — `RegisterAccount` (duplicate-email race) |
| OpenAPI-documented, versioned REST contract | Example B — `RegisterWebhookEndpoint` |
| Transactional outbox write (ADR-0007) | Example B — `RegisterWebhookEndpoint` emitting nothing, contrasted with Example A's registration, which *does* enqueue `account.created` |

## 2. Example A — `identity-module` / `RegisterAccount`

Chosen as the primary template because it's the first use case on the v1 roadmap (`roadmap-and-release-plan.md` §2) and touches the most architecturally interesting concern in `identity-module`: BR-ID-02 (never zero auth methods) and a genuine concurrency hazard (two requests racing to register the same email).

### 2.1 Folder layout

```
identity-module/src/main/java/com/clavaris/identity/
├── domain/
│   ├── model/
│   │   ├── Account.java                  (aggregate root — no framework imports)
│   │   ├── PasswordCredential.java
│   │   └── AccountStatus.java
│   ├── event/
│   │   └── AccountRegisteredEvent.java
│   └── service/
│       └── PasswordPolicy.java           (pure domain rule, no Spring)
├── application/
│   └── usecase/
│       └── registeraccount/
│           ├── RegisterAccountCommand.java     (input DTO, domain-shaped, not a web DTO)
│           ├── RegisterAccountUseCase.java      (inbound port — an interface)
│           ├── RegisterAccountService.java      (the actual orchestration)
│           ├── AccountRepository.java           (outbound port — an interface)
│           ├── PasswordHasher.java              (outbound port — an interface)
│           ├── EventOutboxWriter.java           (outbound port — an interface, ADR-0007)
│           └── EmailAlreadyRegisteredException.java
└── infrastructure/
    ├── adapter/
    │   ├── in/web/
    │   │   └── RegisterAccountController.java   (Thymeleaf form POST — hosted UI, not JSON API)
    │   └── out/
    │       ├── persistence/
    │       │   ├── JpaAccountRepository.java     (implements AccountRepository)
    │       │   └── AccountEntity.java             (JPA entity — never leaks into domain/)
    │       └── security/
    │           └── Argon2PasswordHasher.java      (implements PasswordHasher, ADR-0005)
    └── config/
        └── (Spring wiring only — bean definitions binding ports to adapters)
```

**Rule demonstrated:** `domain/model/Account.java` imports nothing from `org.springframework.*` or `jakarta.persistence.*`. Persistence-specific concerns (`@Entity`, `@Id`, `@Column`) live only on `AccountEntity`, a separate class the infrastructure layer maps to/from — this is what makes the ArchUnit boundary test in `coding-standards.md` §2 pass, not a style preference.

### 2.2 Domain layer (sketch)

```java
// domain/model/Account.java
// BR-ID-02: an Account is never valid with zero authentication methods.
// This invariant is enforced here, in the aggregate, not in a service —
// so it holds no matter which use case touches an Account in the future.
// ADR-0010: an Account always belongs to exactly one Organization — there
// is no factory path that produces an Account without one.
public final class Account {
    private final AccountId id;
    private final OrganizationId organizationId;
    private final Email email;
    private Instant emailVerifiedAt;
    private AccountStatus status;

    public static Account register(OrganizationId organizationId, Email email) {
        // BR-ID-02: registration itself doesn't attach a credential — the
        // use case does, in the same transaction — but the factory method
        // exists so "an Account with no credential yet" is never a state
        // reachable from outside this package.
        // ADR-0010: organizationId is mandatory here, not defaulted or
        // inferred later — an Account is meaningless without its tenant.
        return new Account(AccountId.newId(), organizationId, email, AccountStatus.ACTIVE);
    }
    // ...
}
```

### 2.3 Application layer — orchestration and the concurrency hazard

```java
// application/usecase/registeraccount/RegisterAccountService.java
@Transactional // infrastructure concern — this annotation is the ONE place
               // Spring leaks into this class; application/ still has zero
               // Spring imports on the classes it depends on (domain/, the ports)
public class RegisterAccountService implements RegisterAccountUseCase {

    private final AccountRepository accounts;
    private final PasswordHasher hasher;
    private final EventOutboxWriter outbox;

    @Override
    public AccountId handle(RegisterAccountCommand cmd) {
        // Concurrency: two requests can race to register the same email
        // *within the same Organization* between this check and the insert
        // below. The pre-check is a fast-path UX improvement, NOT the actual
        // safety mechanism — that's the unique constraint on
        // accounts.(organization_id, email) (data-model.md §3, ADR-0010).
        // The same email in a DIFFERENT Organization is not a conflict at
        // all — cmd.organizationId() scopes this check by design.
        if (accounts.existsByOrganizationIdAndEmail(cmd.organizationId(), cmd.email())) {
            throw new EmailAlreadyRegisteredException(cmd.organizationId(), cmd.email());
        }

        Account account = Account.register(cmd.organizationId(), cmd.email());
        account.attachPasswordCredential(hasher.hash(cmd.rawPassword())); // BR-ID-01

        try {
            accounts.save(account);
        } catch (DataIntegrityViolationException raceLost) {
            // The pre-check above lost the race — another request committed
            // first. Translate the low-level DB exception into the same
            // domain exception the pre-check would have thrown, so the
            // caller (the web adapter) never needs to know a race occurred.
            throw new EmailAlreadyRegisteredException(cmd.email());
        }

        // ADR-0007 §1: outbox row written in the SAME transaction as the
        // account insert — @Transactional above covers both, so a crash
        // between the two is impossible; either both commit or neither does.
        outbox.write("account.created", account.id(), AccountRegisteredEvent.from(account));

        return account.id();
    }
}
```

**Concurrency pattern demonstrated:** optimistic pre-check + a real database constraint as the actual guarantee, with the low-level violation translated back into a domain exception. This is the pattern for every "must be unique" use case in the system (organization names if ever unique, OAuth `client_id`, webhook endpoint URLs per client) — never trust the pre-check alone under concurrent load; the constraint is what's actually load-bearing, matching `security-architecture.md`'s general stance that invariants are enforced at the layer that can't be bypassed, not just the layer that's convenient.

### 2.4 Infrastructure layer — web adapter

`RegisterAccountController` is a Thymeleaf form-POST controller (server-rendered hosted UI), not a JSON REST endpoint — registration happens through the login/consent UI, not the management API. It maps the form's `RegisterAccountForm` (web-specific, with Bean Validation annotations) to the domain-shaped `RegisterAccountCommand`, calls `RegisterAccountUseCase`, and redirects to the email-verification-pending page on success or re-renders the form with a field error on `EmailAlreadyRegisteredException` — the exception never becomes a raw 500 or leaks a stack trace.

### 2.5 Tests (per `test-strategy.md` §2)

| Level | What it asserts |
|---|---|
| Unit | `Account.register()` never produces an `Account` with a credential attached in the same call (BR-ID-02 factory invariant); `RegisterAccountService` calls `outbox.write(...)` exactly once per successful registration |
| Integration (Testcontainers) | The unique constraint on `accounts.email` actually exists and actually throws `DataIntegrityViolationException` under a genuine concurrent double-insert, not just a mocked assumption that it would |
| Architecture (ArchUnit) | `domain.model` package has zero dependencies on `org.springframework..` or `jakarta.persistence..` |

## 3. Example B — `webhook-module` / `RegisterWebhookEndpoint`

Chosen to demonstrate the OpenAPI/versioning story (ADR-0008) concretely, since this one *is* a JSON management-API endpoint (unlike Example A's server-rendered form).

### 3.1 The versioned, annotated controller

```java
// infrastructure/adapter/in/web/RegisterWebhookEndpointController.java
@RestController
@RequestMapping("/api/v1/admin/webhook-endpoints") // ADR-0008: URI path versioning, nested under the existing /admin prefix (api-contract-overview.md §3)
@Tag(name = "Webhook Endpoints")
public class RegisterWebhookEndpointController {

    private final RegisterWebhookEndpointUseCase useCase;

    @Operation(
        summary = "Register a webhook endpoint for the authenticated OAuth client",
        description = "Returns the signing secret exactly once — it is never retrievable again (data-model.md §2)."
    )
    @ApiResponse(responseCode = "201", description = "Endpoint registered")
    @ApiResponse(responseCode = "409", description = "This client already has an endpoint registered for this URL")
    @PostMapping
    public ResponseEntity<WebhookEndpointResponse> register(
            @AuthenticationPrincipal OAuthClientPrincipal client, // client_credentials grant, ADR-0006
            @Valid @RequestBody RegisterWebhookEndpointRequest request) {

        var result = useCase.handle(new RegisterWebhookEndpointCommand(
            client.clientId(), request.url(), request.subscribedEventTypes()));

        return ResponseEntity.status(HttpStatus.CREATED).body(WebhookEndpointResponse.from(result));
    }
}
```

This is the only hand-written contract artifact — `@Operation`/`@ApiResponse`/`@Valid` on the request DTO are what springdoc-openapi (ADR-0008 §1) reads to generate `/v3/api-docs` and the Swagger UI page. There is no separate YAML file to keep in sync.

### 3.2 What a v2 of this endpoint would look like

If a future breaking change is needed (e.g. supporting more than one signing secret per endpoint for rotation, ADR-0007's open question), it ships as a new controller under `@RequestMapping("/api/v2/admin/webhook-endpoints")`, routed independently, while `/api/v1/admin/webhook-endpoints` keeps running unchanged until its deprecation window closes (ADR-0008 §1) — not a branch inside the same method.

## 4. What this document is not

- Not an implementation plan or estimate — the actual roadmap sequencing lives in `roadmap-and-release-plan.md`.
- Not a claim that these two use cases are the *only* ones needed for v1 — see `prd-mvp.md` §2 for the full scope.
- Not a final API contract — field names, exact status codes, and error shapes here are illustrative; the real contract is whatever springdoc-openapi generates from the actual controllers once written, per ADR-0008's entire point (code is the source of truth, not this document).
