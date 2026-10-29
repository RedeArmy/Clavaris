package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * Clerk "session tasks" parity: the {@code HttpSession} attribute keys a paused-for-a-required-task
 * login is carried across on — same shape as {@link DeviceTrustPendingState}, deliberately a
 * separate holder rather than reusing that one's keys: a login can be paused for device trust,
 * resume, and then immediately be paused again for a session task (or vice versa) — two distinct,
 * non-overlapping pauses need two distinct sets of keys, not one shared set an early clear could
 * corrupt. {@link SessionTaskGate} is the sole writer, {@link SessionTaskChallengeController} the
 * sole reader.
 *
 * <p>Plain {@code String} values only — same {@code JdkSerializationRedisSerializer} constraint
 * {@code DeviceTrustPendingState}'s own Javadoc documents. {@code clientId}/{@code redirectUrl} are
 * genuinely nullable (Clerk "customize redirect URLs" parity) — see {@code DeviceTrustPendingState}
 * for the identical reasoning.
 */
final class SessionTaskPendingState {

  /* package */ static final String ACCOUNT_ID_ATTRIBUTE = "clavaris.sessionTask.pendingAccountId";

  /**
   * ADR-0031: which task the login is paused for, so the page of one task cannot be driven with the
   * pending state of another (the forced-reset page would otherwise accept a username-task pause
   * and set a password with no operator ever having asked for one). Absent means the forced
   * password reset, which is all a session paused before this attribute existed can be.
   */
  /* package */ static final String TASK_ATTRIBUTE = "clavaris.sessionTask.pendingTask";

  /* package */ static final String TASK_PASSWORD_RESET = "PASSWORD_RESET";

  /* package */ static final String TASK_USERNAME = "USERNAME";

  /* package */ static final String FACTOR_ATTRIBUTE = "clavaris.sessionTask.pendingFactor";

  /* package */ static final String ORGANIZATION_ID_ATTRIBUTE =
      "clavaris.sessionTask.pendingOrganizationId";

  /* package */ static final String CLIENT_ID_ATTRIBUTE = "clavaris.sessionTask.pendingClientId";

  /* package */ static final String REDIRECT_URL_ATTRIBUTE =
      "clavaris.sessionTask.pendingRedirectUrl";

  /**
   * TD-SEC-055: same rationale as {@code DeviceTrustPendingState}'s own identical attribute —
   * present only when {@code factor == SOCIAL}.
   */
  /* package */ static final String PROVIDER_ATTRIBUTE = "clavaris.sessionTask.pendingProvider";

  private SessionTaskPendingState() {
    // Constants only.
  }
}
