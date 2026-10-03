# Frontend Design System — Clavaris

🟡 En revisión

| | |
|---|---|
| **Owner** | Engineering (solo project) |
| **Scope** | Every server-rendered page: tenant login/consent flows, platform auth pages, landing page, admin dashboard |
| **Source of truth** | `app/src/main/resources/static/css/clavaris.css` (one stylesheet), `app/src/main/resources/static/js/*.js` (vanilla JS + HTMX) |

## 1. Principles

1. **One stylesheet, tokens first.** Every colour, spacing step, radius, shadow and duration is a CSS custom property declared in the `:root` block (and redeclared under `prefers-color-scheme: dark`). A component never hard-codes a value a token already names.
2. **`--clavaris-brand-color` is the only accent a component reads.** It defaults to the amber `--clavaris-accent` and is the per-tenant override point (ADR-0009 §3). Never reference `--clavaris-accent` directly in a component.
3. **Markup first, JS second.** Pages work with JavaScript disabled wherever the interaction allows it (`<details>` accordions and menus, radio-driven tabs, native `<dialog>`). JS only adds: dialog open/close wiring, HTMX partial swaps, pickers, the shared confirm modal.
4. **No inline `style` attributes, no inline `<script>`.** The CSP (`ContentSecurityPolicyHeaderWriter`) is `style-src 'self'; script-src 'self'`, so both are blocked by the browser. Dynamic geometry travels as SVG attributes (see the webhook activity chart); everything else is a class.
5. **Quiet by default.** One primary action per view, secondary actions outlined, destructive actions in the danger colour and behind a confirmation.

## 2. Stylesheet layout

`clavaris.css` is ordered in six layers; add new rules to the layer they belong to, not at the end of the file.

| Layer | Contents |
|---|---|
| 1. Tokens | colour (light + dark), spacing (4px grid), radius, elevation, motion, type scale, auth-surface tokens |
| 2. Base | reset, body, `:focus-visible`, reduced-motion, `.clavaris-visually-hidden`, `.clavaris-skip-link`, `.clavaris-icon` |
| 3. Page shells | card pages (the content landmark that is a direct child of the body), auth pages, dashboard shell (`.clavaris-dashboard*`), landing |
| 4. Components | form controls, toggle, buttons, alerts, badges, tables, pagination, breadcrumb/tabs, menus, dialogs |
| 5. Features | organizations grid, teams accordion, pickers, activity chart, heatmap |
| 6. Responsive | all breakpoints (920 / 800 / 640px) |

## 3. Components

### Buttons

`.clavaris-button` is a full-width block (one action per card form). Add `--inline` for content-sized. Variants: default (primary, brand colour), `--secondary` (outlined), `--ghost`, `--danger`. Inside table cells and `.clavaris-row-actions` buttons automatically shrink to a 32px compact size, so rows stay scannable. Disabled state: the `disabled` attribute or `aria-disabled="true"`.

### Forms

`.clavaris-field` wraps label + control + `.clavaris-field-error` / `.clavaris-field-hint`. One shared rule styles `input`, `select`, `textarea` (40px min-height, brand-colour focus ring). On/off settings use `.clavaris-toggle`; "pick N of many" lists use plain checkboxes (`.clavaris-event-picker`, `.clavaris-role-picker`).

### Tables

Wrap every `<table class="clavaris-table">` in `.clavaris-table-wrapper`: the wrapper is the card (border, radius, shadow) and the horizontal scroller. The last column holds `.clavaris-row-actions` or `.clavaris-menu`; it is right-aligned automatically. Header cells use `<th scope="col">`; an actions column header is a `.clavaris-visually-hidden` label.

### Dialogs

Native `<dialog class="clavaris-dialog">`. Open with `data-dialog-open="<id>"`, close with `data-dialog-close`. Esc and a click on the backdrop also dismiss it. Layout: `.clavaris-dialog__header` (title + close button), a `<form>` (body + `.clavaris-dialog__actions`, Cancel left of the primary action).

### Confirmation of destructive actions

Put `data-confirm="Consequence sentence."` on any `<form>` (optionally `data-confirm-title`, `data-confirm-label`, `data-confirm-tone="danger"`). `confirm-action.js` holds the submit event in the capture phase, shows the shared modal, and replays the submit on confirm — works for plain POSTs and HTMX forms. Irreversible deletions that need a typed confirmation keep their own dedicated dialog.

### Icons

Inline SVG with `class="clavaris-icon"`, `viewBox="0 0 24 24"`, `aria-hidden="true"`, stroke-based, coloured by `currentColor`. Do not use Unicode glyphs (◈ ⌄ ▦ …) as icons: their shapes differ per OS font.

### Navigation

`.clavaris-sidebar` is the top bar (brand, primary nav, account menu). Section navigation uses `.clavaris-org-tabs` (full-page navigations, not JS tabs). The active item carries both `.active` and `aria-current="page"`. Each dashboard page's content landmark carries the id the skip link points to.

## 4. Accessibility checklist for a new page

- One `<h1>`; headings do not skip levels.
- Every control has a visible `<label>` or a `.clavaris-visually-hidden` one.
- Server-rendered error alerts use `role="alert"`, success alerts `role="status"`.
- Focus ring is never removed (the global `:focus-visible` rule draws it; do not add `outline: none` without a replacement).
- Status is never colour-only: badges carry text.
- Test the page at 360px width and in dark mode.

## 5. Known limitations

- **Tenant brand colour is not applied.** `fragments/head.html` injects `--clavaris-brand-color` through an inline `<style>` element, which the `style-src 'self'` CSP blocks. Fixing it needs either a per-request CSP nonce or a per-tenant stylesheet endpoint (`/o/{organizationId}/branding.css`). Tracked as TD-UX-001 in `technical-debt-register.md`.
- Anything several modules render (the dashboard top bar, the Organization tab row and header, the back button, the `<head>`) lives once in `common` under `templates/platform/fragments/` (and `templates/fragments/head.html`). Add shared markup there, not as a per-module copy: templates in `common` are on every module's classpath and covered by their standalone-MockMvc tests.
- Failed HTMX requests need no per-page code: `htmx-feedback.js`, loaded by the navigation fragment, shows the toast and handles an expired session.
