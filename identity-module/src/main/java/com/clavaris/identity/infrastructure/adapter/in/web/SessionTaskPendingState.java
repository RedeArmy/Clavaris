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
@SuppressWarnings("PMD.LongVariable")
final class SessionTaskPendingState {

  /* package */ static final String ACCOUNT_ID_ATTRIBUTE = "clavaris.sessionTask.pendingAccountId";

  /* package */ static final String FACTOR_ATTRIBUTE = "clavaris.sessionTask.pendingFactor";

  /* package */ static final String ORGANIZATION_ID_ATTRIBUTE =
      "clavaris.sessionTask.pendingOrganizationId";

  /* package */ static final String CLIENT_ID_ATTRIBUTE = "clavaris.sessionTask.pendingClientId";

  /* package */ static final String REDIRECT_URL_ATTRIBUTE =
      "clavaris.sessionTask.pendingRedirectUrl";

  private SessionTaskPendingState() {
    // Constants only.
  }
}
