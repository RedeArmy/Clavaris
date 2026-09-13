package com.clavaris.common.domain.model;

/**
 * TD-PERF-020: a page-number-based pagination request — the shape every one of this codebase's
 * dashboard list pages (Organizations, Workspaces, Workspace Members, Secret Keys, OAuth Clients,
 * Webhook Endpoints) now asks its own repository for, instead of an unbounded {@code List<X>}.
 *
 * <p><b>Page-number, not keyset/cursor:</b> a real, deliberate choice, not the default because it
 * was easiest. Every one of these six lists is an ordinary admin-dashboard table with a
 * Previous/Next control — page-number is the conventional shape for that UI, lets a page link be a
 * plain, bookmarkable/shareable {@code ?page=N} URL, and needs no extra column (a cursor needs a
 * stable, indexed sort key to resume from). Keyset/cursor pagination is the better choice under
 * heavy concurrent inserts (exactly why {@code WebhookDeliveryRepository#claimDueBatch} already
 * uses a cursor shape) — none of these six lists has that profile:
 * Organizations/Workspaces/Members/ Secret Keys/OAuth Clients/Webhook Endpoints are all low-churn,
 * admin-triggered writes, not a high-frequency event stream.
 *
 * <p>{@code page} is 0-indexed (matches {@code org.springframework.data.domain.Pageable}'s own
 * convention, which every JPA adapter implementing a paginated repository method maps this onto) —
 * {@code page=0} is the first page, not {@code page=1}.
 */
public record PageRequest(int page, int size) {

  /** Dashboard default — proportionate to a table meant to be scanned by a human, not exported. */
  public static final int DEFAULT_SIZE = 20;

  /**
   * Hard ceiling — same "a fixed system-wide cap no caller can exceed" posture ADR-0010 §6.2's own
   * {@code hard-cap-requests-per-minute} already establishes for a different resource.
   */
  public static final int MAX_SIZE = 100;

  public PageRequest {
    if (page < 0) {
      throw new IllegalArgumentException("page must be >= 0, was " + page);
    }
    if (size < 1 || size > MAX_SIZE) {
      throw new IllegalArgumentException(
          "size must be between 1 and " + MAX_SIZE + ", was " + size);
    }
  }

  /** The first page, at this codebase's own dashboard-wide default size. */
  public static PageRequest first() {
    return new PageRequest(0, DEFAULT_SIZE);
  }
}
