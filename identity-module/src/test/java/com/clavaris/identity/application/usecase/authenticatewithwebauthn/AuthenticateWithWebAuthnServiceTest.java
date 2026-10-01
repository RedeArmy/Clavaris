package com.clavaris.identity.application.usecase.authenticatewithwebauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.WebAuthnCredential;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.exception.AssertionFailedException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Both {@link AssertionResult} and {@link com.yubico.webauthn.data.PublicKeyCredential} are {@code
 * final} Yubico DTOs with no public constructor/builder — unmockable via a subclass proxy, but real
 * {@code mock(...)} stand-ins work fine under this project's own inline mock-maker (already active,
 * see every other {@code HttpResponse<String>} mock in this codebase for the same precedent). A
 * full successful ceremony still needs a real, cryptographically valid signature — not reproduced
 * here — but {@code finishAssertion} itself is mocked, so every branch *after* the ceremony
 * (unknown/inactive Account, the signature-count bump, the credential-not-found case) is genuinely
 * exercised with a structurally valid (not cryptographically valid — parsing never checks the
 * signature) assertion response JSON.
 */
class AuthenticateWithWebAuthnServiceTest {

  // A syntactically valid WebAuthn assertion-response JSON: PublicKeyCredential.
  // parseAssertionResponseJson only validates shape/field sizes (authenticatorData must be >=37
  // bytes), never the signature itself — that check happens inside the (here, mocked)
  // RelyingParty#finishAssertion.
  private static final String VALID_SHAPED_ASSERTION_JSON =
      "{\"type\":\"public-key\",\"id\":\"AQIDBA\",\"rawId\":\"AQIDBA\",\"response\":{"
          + "\"clientDataJSON\":\"eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiQVFJREJBIiwib3JpZ2luIjoiaHR0cHM6Ly9leGFtcGxlLmNvbSJ9\","
          + "\"authenticatorData\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\","
          + "\"signature\":\"AQIDBA\",\"userHandle\":\"AQIDBA\"},\"clientExtensionResults\":{}}";

  private final RelyingParty relyingParty = mock(RelyingParty.class);
  private final WebAuthnCredentialRepository credentials = mock(WebAuthnCredentialRepository.class);
  private final AccountRepository accounts = mock(AccountRepository.class);
  private final AuthenticateWithWebAuthnService service =
      new AuthenticateWithWebAuthnService(relyingParty, credentials, accounts);

  private AccountId accountId;
  private AssertionRequest request;

  @BeforeEach
  void setUp() {
    accountId = new AccountId(UUID.randomUUID());
    request = mock(AssertionRequest.class);
  }

  private AuthenticateWithWebAuthnCommand validCommand() {
    return new AuthenticateWithWebAuthnCommand(request, VALID_SHAPED_ASSERTION_JSON);
  }

  private AssertionResult assertionResultFor(final AccountId resolvedAccountId) {
    AssertionResult result = mock(AssertionResult.class);
    when(result.getUserHandle())
        .thenReturn(
            new ByteArray(resolvedAccountId.value().toString().getBytes(StandardCharsets.UTF_8)));
    when(result.getCredentialId()).thenReturn(new ByteArray(new byte[] {1, 2, 3, 4}));
    when(result.getSignatureCount()).thenReturn(5L);
    return result;
  }

  @Test
  void aMalformedCredentialJsonNeverReachesTheRelyingPartyOrAnyRepository()
      throws AssertionFailedException {
    AuthenticateWithWebAuthnCommand command =
        new AuthenticateWithWebAuthnCommand(mock(AssertionRequest.class), "not valid json");

    assertThatThrownBy(() -> service.handle(command))
        .isInstanceOf(InvalidWebAuthnAssertionException.class);

    verifyNoInteractions(credentials, accounts);
    verify(relyingParty, never()).finishAssertion(any());
  }

  @Test
  void aFailedCeremonyNeverReachesAnyRepository() throws AssertionFailedException {
    when(relyingParty.finishAssertion(any())).thenThrow(new AssertionFailedException("rejected"));
    AuthenticateWithWebAuthnCommand command = validCommand();

    assertThatThrownBy(() -> service.handle(command))
        .isInstanceOf(InvalidWebAuthnAssertionException.class);

    verifyNoInteractions(credentials, accounts);
  }

  @Test
  void aUserHandleThatIsNotAValidUuidIsRejected() throws AssertionFailedException {
    AssertionResult result = mock(AssertionResult.class);
    when(result.getUserHandle())
        .thenReturn(new ByteArray("not-a-uuid".getBytes(StandardCharsets.UTF_8)));
    when(relyingParty.finishAssertion(any())).thenReturn(result);
    AuthenticateWithWebAuthnCommand command = validCommand();

    assertThatThrownBy(() -> service.handle(command))
        .isInstanceOf(InvalidWebAuthnAssertionException.class);

    verifyNoInteractions(credentials, accounts);
  }

  @Test
  void anUnresolvableAccountIsRejected() throws AssertionFailedException {
    // Computed before the when(...) chain below starts, not inlined as its argument — chaining a
    // nested when(...).thenReturn(...) (inside assertionResultFor) into an outer when(...)'s own
    // argument confuses Mockito's thread-local "ongoing stubbing" state.
    AssertionResult result = assertionResultFor(accountId);
    when(relyingParty.finishAssertion(any())).thenReturn(result);
    when(accounts.findById(accountId)).thenReturn(Optional.empty());
    AuthenticateWithWebAuthnCommand command = validCommand();

    assertThatThrownBy(() -> service.handle(command))
        .isInstanceOf(InvalidWebAuthnAssertionException.class);

    verifyNoInteractions(credentials);
    verify(accounts, never()).save(any());
  }

  @Test
  void anInactiveAccountIsRejected() throws AssertionFailedException {
    Account suspended =
        Account.register(new OrganizationId(UUID.randomUUID()), new Email("suspended@example.com"));
    suspended.suspend();
    AssertionResult result = assertionResultFor(accountId);
    when(relyingParty.finishAssertion(any())).thenReturn(result);
    when(accounts.findById(accountId)).thenReturn(Optional.of(suspended));
    AuthenticateWithWebAuthnCommand command = validCommand();

    assertThatThrownBy(() -> service.handle(command))
        .isInstanceOf(InvalidWebAuthnAssertionException.class);

    verifyNoInteractions(credentials);
    verify(accounts, never()).save(any());
  }

  @Test
  void aSuccessfulCeremonyBumpsTheMatchingCredentialsSignatureCountAndSignsIn()
      throws AssertionFailedException {
    Account account =
        Account.register(new OrganizationId(UUID.randomUUID()), new Email("real@example.com"));
    AssertionResult result = assertionResultFor(accountId);
    WebAuthnCredential matchingCredential = mock(WebAuthnCredential.class);
    UUID credentialRowId = UUID.randomUUID();
    when(matchingCredential.id()).thenReturn(credentialRowId);
    when(relyingParty.finishAssertion(any())).thenReturn(result);
    when(accounts.findById(accountId)).thenReturn(Optional.of(account));
    when(credentials.findByCredentialId(result.getCredentialId().getBytes()))
        .thenReturn(Optional.of(matchingCredential));

    Account signedIn = service.handle(validCommand());

    assertThat(signedIn).isSameAs(account);
    verify(credentials).updateSignatureCount(eq(credentialRowId), eq(5L), any());
    verify(accounts).save(account);
  }

  @Test
  void aSuccessfulCeremonyWithNoMatchingCredentialRowStillSignsInWithoutBumpingAnything()
      throws AssertionFailedException {
    Account account =
        Account.register(new OrganizationId(UUID.randomUUID()), new Email("real2@example.com"));
    AssertionResult result = assertionResultFor(accountId);
    when(relyingParty.finishAssertion(any())).thenReturn(result);
    when(accounts.findById(accountId)).thenReturn(Optional.of(account));
    when(credentials.findByCredentialId(result.getCredentialId().getBytes()))
        .thenReturn(Optional.empty());

    Account signedIn = service.handle(validCommand());

    assertThat(signedIn).isSameAs(account);
    verify(credentials, never()).updateSignatureCount(any(), anyLong(), any());
    verify(accounts).save(account);
  }
}
