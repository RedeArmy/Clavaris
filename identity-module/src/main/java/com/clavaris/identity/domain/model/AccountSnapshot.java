package com.clavaris.identity.domain.model;

import java.time.Instant;

/**
 * Every persisted attribute of an {@link Account}, in one value: what the persistence adapter
 * rehydrates from ({@link Account#reconstitute(AccountSnapshot)}) and reads back ({@link
 * Account#snapshot()}). It replaces the five telescoping {@code reconstitute} overloads (up to 21
 * positional parameters) that each new attribute used to add another of.
 *
 * <p>{@code passwordCredential} and {@code username} may be {@code null} (a social-only account,
 * ADR-0020; a username never assigned, ADR-0024 §4) — BR-ID-02 still guarantees at least one
 * authentication method exists. The grouped components are never {@code null}: use their {@code
 * EMPTY}/{@code DEFAULT}/{@code NONE} constants.
 */
// PMD.LongVariable/ShortVariable: the component names are the Account accessors they back.
@SuppressWarnings({"PMD.LongVariable", "PMD.ShortVariable"})
public record AccountSnapshot(
    AccountId id,
    OrganizationId organizationId,
    Email email,
    Instant createdAt,
    Instant emailVerifiedAt,
    AccountStatus status,
    PasswordCredential passwordCredential,
    Username username,
    Instant passwordResetRequiredAt,
    Instant lastSignedInAt,
    AccountProfile profile,
    AccountPermissions permissions,
    RegistrationDecision registration,
    AccountMetadata metadata) {}
