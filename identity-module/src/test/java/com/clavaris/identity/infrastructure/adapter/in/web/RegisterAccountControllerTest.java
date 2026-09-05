package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountCommand;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountUseCase;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.UsernameRequiredException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.requestemailsignincode.RequestEmailSignInCodeCommand;
import com.clavaris.identity.application.usecase.requestemailsignincode.RequestEmailSignInCodeUseCase;
import com.clavaris.identity.application.usecase.requestemailsigninlink.RequestEmailSignInLinkCommand;
import com.clavaris.identity.application.usecase.requestemailsigninlink.RequestEmailSignInLinkUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationCommand;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Standalone MockMvc setup, not {@code @WebMvcTest}: Spring Boot 4 removed the test-slice
 * annotation family (confirmed live — {@code spring-boot-test-autoconfigure:4.1.0} no longer
 * contains it at all, unlike the 3.4.x version still transitively present elsewhere in this
 * reactor). {@link MockMvcBuilders#standaloneSetup} needs no Spring context, only {@code
 * spring-test} + Mockito — wires just this one controller plus a real Thymeleaf view resolver, so
 * assertions on the rendered view name are checked against the actual templates, not a stub.
 */
class RegisterAccountControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private RegisterAccountUseCase useCase;
  private RequestEmailVerificationUseCase requestEmailVerification;
  private AccountAuthenticationPolicyProvider policyProvider;
  private RequestEmailSignInCodeUseCase requestEmailSignInCode;
  private RequestEmailSignInLinkUseCase requestEmailSignInLink;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(RegisterAccountUseCase.class);
    requestEmailVerification = mock(RequestEmailVerificationUseCase.class);
    policyProvider = mock(AccountAuthenticationPolicyProvider.class);
    requestEmailSignInCode = mock(RequestEmailSignInCodeUseCase.class);
    requestEmailSignInLink = mock(RequestEmailSignInLinkUseCase.class);
    // Matches today's real default (ADR-0024) — every existing test below predates this policy.
    when(policyProvider.policyFor(new OrganizationId(ORGANIZATION_ID)))
        .thenReturn(AccountAuthenticationPolicySnapshot.defaults());

    // SpringResourceTemplateResolver needs a real ApplicationContext to resolve "classpath:"
    // resources (confirmed live — IllegalArgumentException: Application Context cannot be null
    // — when none was set); ThymeleafViewResolver, in turn, only accepts ISpringTemplateEngine,
    // so the plain non-Spring resolver/engine pair isn't an option here. A minimal, empty,
    // refreshed context is enough — nothing in this test needs any bean from it beyond resource
    // loading.
    GenericApplicationContext applicationContext = new GenericApplicationContext();
    applicationContext.refresh();

    SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
    templateResolver.setApplicationContext(applicationContext);
    templateResolver.setPrefix("classpath:/templates/");
    templateResolver.setSuffix(".html");

    SpringTemplateEngine templateEngine = new SpringTemplateEngine();
    templateEngine.setTemplateResolver(templateResolver);

    ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
    viewResolver.setTemplateEngine(templateEngine);

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new RegisterAccountController(
                    useCase,
                    requestEmailVerification,
                    policyProvider,
                    requestEmailSignInCode,
                    requestEmailSignInLink))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getShowsTheRegistrationForm() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/register", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/register"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void validSubmissionRegistersTriggersVerificationEmailAndRedirectsToPendingVerification()
      throws Exception {
    AccountId accountId = AccountId.newId();
    when(useCase.handle(any())).thenReturn(accountId);

    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                .param("email", "new-user@example.com")
                .param("password", "a-valid-password")
                .param("confirmPassword", "a-valid-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/register/pending-verification"));

    verify(useCase)
        .handle(
            new RegisterAccountCommand(
                new OrganizationId(ORGANIZATION_ID),
                new Email("new-user@example.com"),
                "a-valid-password",
                null));
    // TD-SEC-004: registration must actually trigger the verification email it promises on the
    // page it redirects to, not just claim to have.
    verify(requestEmailVerification).handle(new RequestEmailVerificationCommand(accountId));
  }

  @Test
  void invalidEmailRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                .param("email", "not-an-email")
                .param("password", "a-valid-password")
                .param("confirmPassword", "a-valid-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/register"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(useCase);
    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void tooShortPasswordRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                .param("email", "new-user@example.com")
                .param("password", "short")
                .param("confirmPassword", "short"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/register"))
        .andExpect(model().attributeHasFieldErrors("form", "password"));

    verifyNoInteractions(useCase);
    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void tooLongPasswordRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    // 129 chars — one over PasswordPolicy.MAX_LENGTH's DoS-defence bound, mirrored on the form's
    // own @Size(max = 128) for a fast client/server round trip.
    String tooLong = "a".repeat(129);

    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                .param("email", "new-user@example.com")
                .param("password", tooLong)
                .param("confirmPassword", tooLong))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/register"))
        .andExpect(model().attributeHasFieldErrors("form", "password"));

    verifyNoInteractions(useCase);
    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void mismatchedConfirmationRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                .param("email", "new-user@example.com")
                .param("password", "a-valid-password")
                .param("confirmPassword", "a-different-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/register"))
        .andExpect(model().attributeHasFieldErrors("form", "confirmPassword"));

    verifyNoInteractions(useCase);
    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void blankConfirmationRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                .param("email", "new-user@example.com")
                .param("password", "a-valid-password")
                .param("confirmPassword", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/register"))
        .andExpect(model().attributeHasFieldErrors("form", "confirmPassword"));

    verifyNoInteractions(useCase);
    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void alreadyRegisteredEmailRerendersTheFormWithAFieldError_neverALeakedStackTrace()
      throws Exception {
    when(useCase.handle(any()))
        .thenThrow(new EmailAlreadyRegisteredException(new OrganizationId(ORGANIZATION_ID)));

    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                .param("email", "taken@example.com")
                .param("password", "a-valid-password")
                .param("confirmPassword", "a-valid-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/register"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void weakPasswordRejectedByTheUseCaseRerendersTheFormWithAFieldError() throws Exception {
    when(useCase.handle(any())).thenThrow(new WeakPasswordException());

    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                // long enough to pass Bean Validation's @Size(min = 8, max = 128) but still
                // rejected by the use case's own PasswordPolicy check — proves the two layers
                // are independent.
                .param("email", "new-user@example.com")
                .param("password", "aaaaaaaa")
                .param("confirmPassword", "aaaaaaaa"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/register"))
        .andExpect(model().attributeHasFieldErrors("form", "password"));

    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void usernameRequiredExceptionRerendersTheFormWithAFieldError() throws Exception {
    when(useCase.handle(any())).thenThrow(new UsernameRequiredException());

    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                .param("email", "new-user@example.com")
                .param("password", "a-valid-password")
                .param("confirmPassword", "a-valid-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/register"))
        .andExpect(model().attributeHasFieldErrors("form", "username"));

    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void usernameAlreadyRegisteredExceptionRerendersTheFormWithAFieldError() throws Exception {
    when(useCase.handle(any()))
        .thenThrow(new UsernameAlreadyRegisteredException(new OrganizationId(ORGANIZATION_ID)));

    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                .param("email", "new-user@example.com")
                .param("password", "a-valid-password")
                .param("confirmPassword", "a-valid-password")
                .param("username", "taken"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/register"))
        .andExpect(model().attributeHasFieldErrors("form", "username"));

    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void missingPasswordIsRejectedWhenThePolicyRequiresOne() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                .param("email", "new-user@example.com"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/register"))
        .andExpect(model().attributeHasFieldErrors("form", "password"));

    verifyNoInteractions(useCase);
    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void missingPasswordCompletesSignUpViaEmailCodeWhenThePolicyAllowsIt() throws Exception {
    when(policyProvider.policyFor(new OrganizationId(ORGANIZATION_ID)))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                false,
                EmailVerificationMethod.LINK,
                true,
                false,
                false,
                false,
                false,
                false,
                false));
    AccountId accountId = AccountId.newId();
    when(useCase.handle(any())).thenReturn(accountId);

    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                .param("email", "new-user@example.com"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl(
                "/o/" + ORGANIZATION_ID + "/login/email-code/confirm?email=new-user@example.com"));

    verify(requestEmailSignInCode)
        .handle(
            new RequestEmailSignInCodeCommand(
                new OrganizationId(ORGANIZATION_ID), new Email("new-user@example.com")));
    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void missingPasswordCompletesSignUpViaEmailLinkWhenCodeIsNotEnabled() throws Exception {
    when(policyProvider.policyFor(new OrganizationId(ORGANIZATION_ID)))
        .thenReturn(
            new AccountAuthenticationPolicySnapshot(
                false,
                EmailVerificationMethod.LINK,
                false,
                true,
                false,
                false,
                false,
                false,
                false));
    AccountId accountId = AccountId.newId();
    when(useCase.handle(any())).thenReturn(accountId);

    mockMvc
        .perform(
            post("/o/{organizationId}/register", ORGANIZATION_ID)
                .param("email", "new-user@example.com"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login/email-link/pending"));

    verify(requestEmailSignInLink)
        .handle(
            new RequestEmailSignInLinkCommand(
                new OrganizationId(ORGANIZATION_ID), new Email("new-user@example.com")));
    verifyNoInteractions(requestEmailVerification);
  }

  @Test
  void pendingVerificationPageRenders() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/register/pending-verification", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/register-pending-verification"));
  }
}
