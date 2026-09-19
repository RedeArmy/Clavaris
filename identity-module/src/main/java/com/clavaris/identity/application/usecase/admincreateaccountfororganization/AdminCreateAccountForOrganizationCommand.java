package com.clavaris.identity.application.usecase.admincreateaccountfororganization;

import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab parity: the admin-initiated counterpart
 * to {@code RegisterAccountCommand}, with two real, structural differences (see {@code
 * AdminCreateAccountForOrganizationService}'s own Javadoc for why each is a distinct use case, not
 * an optional-parameter extension of {@code RegisterAccountService}):
 *
 * <ul>
 *   <li>{@code rawPassword} is never optional and never auto-generated — the operator sets it
 *       directly, right now, same as Clerk's own "Create user" form.
 *   <li>{@code ignorePasswordPolicy}/{@code ignoreAccessRestrictions} bypass the two checks {@code
 *       RegisterAccountCommand} never lets a caller skip.
 * </ul>
 *
 * {@code firstName}/{@code lastName}/{@code username}/{@code phoneNumber} may each be {@code null}
 * — every field on the create-user form except email and password is optional.
 */
// PMD.LongVariable: ignorePasswordPolicy/ignoreAccessRestrictions name exactly what each
// checkbox on the create-user form does — same rationale every other descriptively-named field in
// this codebase's own class-level suppressions document.
@SuppressWarnings("PMD.LongVariable")
public record AdminCreateAccountForOrganizationCommand(
    OrganizationId organizationId,
    Email email,
    String rawPassword,
    String firstName,
    String lastName,
    String username,
    String phoneNumber,
    boolean ignorePasswordPolicy,
    boolean ignoreAccessRestrictions) {}
