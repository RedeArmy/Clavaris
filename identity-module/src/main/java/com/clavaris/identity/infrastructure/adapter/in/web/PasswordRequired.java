package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * Bean Validation group marker — lets {@link EmailPasswordConfirmationForm}'s {@code password}/
 * {@code confirmPassword} fields carry a real {@code @NotBlank} that only fires for a caller that
 * actually opts into this group, instead of forking into two near-identical field declarations.
 *
 * <p>{@link RegisterPlatformAccountForm}'s own controller validates with
 * {@code @Validated({Default.class, PasswordRequired.class})} — password stays hard-required there,
 * unchanged. {@link RegisterAccountForm} (ADR-0024 §5: password is optional when the Organization's
 * own {@code passwordAtSignUpEnabled} policy is off) validates with plain {@code @Valid} (the
 * {@code Default} group only) and enforces "required" itself via a runtime policy check ({@code
 * RegisterAccountController}'s own {@code bindingResult.rejectValue(...)}) — a business rule this
 * annotation-based group mechanism can't express, since it depends on data no static group
 * assignment can see.
 */
public interface PasswordRequired {
  // Marker only — no members.
}
