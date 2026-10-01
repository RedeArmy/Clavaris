package com.clavaris.identity.infrastructure.adapter.in.web;

import java.time.LocalDate;

/**
 * TD-FUT-034, Clerk "View Profile" activity heatmap parity — a presentation-only grid cell, never a
 * domain concept: {@code date} is {@code null} for a padding cell outside the actual trailing
 * window (the grid is aligned to a Sunday-starting week, so the first/last partial weeks need blank
 * cells). {@code cssClass}/{@code title} are precomputed here, not built inline in the Thymeleaf
 * template — same "the controller does the computation, the template just renders" convention
 * {@code friendlyDeviceLabels} already establishes on this same page; a {@code null} title renders
 * no {@code title} attribute at all (Thymeleaf's own behavior for a null attribute value).
 */
record HeatmapDayCell(LocalDate date, long count, int level, String cssClass, String title) {}
