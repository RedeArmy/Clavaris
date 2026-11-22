# Frontend visual audit — 2026-11-20

🟡 En revisión

| | |
|---|---|
| **Owner** | Engineering (solo project) |
| **Date** | 2026-11-20 |
| **Scope** | Every server-rendered page family (landing, platform sign-in and registration, the tenant's hosted sign-in, the error page, the dashboard and its tabs, the Configure sub-pages, account pages), `clavaris.css`, `clavaris-dashboard.css`, the 28 scripts under `static/js`, and the brand assets |
| **Companions** | `frontend-design-system.md` (the rules this audit measures against) and `frontend-audit-and-environment-switcher.md` (the 2026-10-03 audit, whose findings were mostly structural) |

## 1. Method

Looked at, not only read: the pages were opened in a real browser on a throwaway instance, signed in with a test account, with a test Organization, an OAuth client and the Configure pages, in light and dark and at 1280 and 390 pixels wide. On top of that, one script run on each dashboard page measured what a screenshot hides: horizontal overflow, interactive targets under 32 px, text contrast against the real background (WCAG AA, 4.5:1 and 3:1 for large text), inputs without a label, heading structure and the font sizes in use. Flows tried: registration, sign-in, a refused sign-in, creating an Organization from the dialog, moving through every tab. The CSS and templates were then read for the cause of each finding, and the fixes were prototyped on the live page before they were written to the files.

## 2. What is in good shape

Worth saying first, because the work below is polish on a sound base and not a redesign:

- **Accessibility measures are clean.** On the dashboard pages the script found no input without a label, one `<h1>` per page, no horizontal overflow at 1280 or 390, and one contrast failure in the whole set (finding 7).
- **The system is token-driven** (4px spacing grid, five radii, five elevations, light and dark), with no inline style and no inline script, as the CSP requires.
- **Mobile is handled properly.** At 390 px the top bar, the tab rows, the cards and the dialogs keep their rhythm; nothing overflows.
- **The dialogs, the refused sign-in message (a live-region alert that keeps the username and clears the password), the confirmation of destructive actions and the empty states** behave as the design system says.

## 3. Findings and what was done

| # | Finding | Evidence | Rating | Done |
|---|---|---|---|---|
| 1 | **The page title started 4 px under the tab rule on every tab page.** The tab strip and the title read as one block. | Measured on Organization, Users, Logs, every Configure sub-page and the client detail: tab strip bottom at y 353, content at y 357 | P1 | The strip's bottom margin is now a full step of the scale (24 px). One rule, every tab. |
| 2 | **The sub-strips (Users: "All users", Workspace: "Manage") sat 52 px under the page description and 4 px over their content**, the reverse of the intended rhythm. They reused the primary strip's class, which carries a 20 px top margin that a strip inside a page should not have. | Users page: header bottom 449, strip top 501, search form 546 | P2 | Both fragments take the existing `clavaris-org-tabs--spaced` modifier (no top margin, 24 px below). |
| 3 | **The wordmark vanished in dark mode** on the landing page and on the sign-in and registration cards: the logo is a near-black text in an image, and an image does not read the page's CSS. | Dark landing: only the shield is visible next to an empty gap | P1 | The SVG now carries a `prefers-color-scheme: dark` rule for its two text lines (an SVG shown through `<img>` follows the browser's scheme). |
| 4 | **The landing headline broke into four lines with one word alone on the last** ("Infraestructura de / identidad / que de verdad es / tuya"), 90 px type in an 820 px box with a forced break. | Spanish landing at 1280 | P2 | 4.5 rem in a 900 px box with a slightly looser line height: two balanced lines in both languages. |
| 5 | **A 404 said "The sign-in request is not valid or has expired."** The shared error page assumed every failure is a refused sign-in. Someone who mistyped an address was told to restart a sign-in. | `/nonexistent-page` | P1 (flow) | A 404 now says "Page not found", explains it in one sentence and offers the way home; Spanish added. Refused sign-ins and server errors keep their wording. |
| 6 | **Targets under the 24 px minimum (WCAG 2.5.8) and under 32 px on the controls people actually use**: the breadcrumb links 20 px, the environment switch 25 px, the language links 26 px. | Script, every dashboard page | P2 | `min-height` of 28 px on the breadcrumb links and 32 px on the switch and the language links; the visible layout barely moves. |
| 7 | **"Set up" in the environment menu had a contrast of 2.15:1** (brand amber on white, 12 px). | Script | P2 | It uses the warning text colour, the AA foreground for the same tint. |
| 8 | **Text below the type scale**: `<small>` rendered at 10.4 px and inline `code` at 11.7 px (the browser's own defaults), the top bar's "Control plane" caption at 10.4 px, the Organization card footer and the Configure rail label at 11.2 px. Eleven different font sizes were in use on one page. | Script: `10.4, 11.7, 12, 13, 13.6, 14, 14.4, 15, 16.8, 25.6, 36` | P2 | Nothing is under 12 px any more (`small` and `code` are clamped globally; the three component literals use `--text-xs`). The in-between sizes (13.6, 14.4, 16.8, 25.6) are TD-UX-003. |
| 9 | **The language pill, fixed at the bottom left, covered the last items of the Configure rail on a short window and the end of the page on a phone.** | Screenshots at 1280×506 and 390×844 | P2 | The rail stops above the pill and scrolls inside itself; every dashboard page keeps at least 72 px of bottom padding so its last block scrolls clear. |
| 10 | **The "Create user" dialog's email, username and password inputs had no `autocomplete` hint**, so a browser could fill the operator's own saved credentials into the form that creates someone else's account. | Template read | P2 (flow) | `autocomplete="off"` on email and username, `new-password` on the password. |
| 11 | **Empty states were a single line in a short box**, easy to read as a failed load. | Workspaces, Users, Webhook Endpoints | P3 | More air (48 px above and below) and a readable line height, balanced text. |

## 4. Not changed, and why

- **The type scale is still wider than its four tokens** (`--text-xs` to `--text-md`): 13.6, 14.4, 16.8 and 25.6 px are literal values in components. Folding them into the scale moves text by up to a pixel on every page, which is worth doing with before-and-after screenshots of each page and is registered as TD-UX-003.
- **The search field of the Users page cuts its placeholder on a phone** ("Buscar por nombre, usuario o co…") because the Search button sits beside it. A shorter placeholder is a copy change in both languages, left to whoever owns the copy (`frontend-copy-audit.md`).
- **"Back" and the breadcrumb on the same page** say the same thing twice and stack three rows above the title. The pattern is the design system's deliberate one and its fragment is shared by four modules; merging them into one row needs a markup change in each.
- **The tenant's hosted sign-in card is narrower and its labels smaller than the platform's.** The tenant page is meant to sit inside the consuming application's own look, and the brand colour is the tenant's, so it was left alone.
- **The Organizations grid card leaves empty space between the name and the footer when the name is short.** Cosmetic, and tied to a fixed card height the animation of the menu depends on.
- **Dark mode and the tenant brand colour** were looked at on the dashboard and the landing page only; the consent and passkey pages were not rendered in this audit.

## 5. Verification

- The Spanish catalogue test, the stylesheet split and minification tests and the template tests that cover the pages changed are run by the build.
- The 215 script tests pass unchanged (no script was modified).
- The pages were rendered again after the build, in light and dark at 1280 and 390 pixels, and the same script re-run; the result is in section 6.

## 6. Result of the second pass

Run on the application built from this branch (the real jar, a copy of the throwaway database):

- **Targets under 28 px on the Configure page:** before, the breadcrumb links (20), the environment switch (25) and the language links (26); after, none except an inline text link inside a table cell (16 px, exempt as inline text).
- **Contrast failures:** before, one (2.15:1); after, none.
- **Tab strip to page title:** before, 4 px; after, 24 px on every tab, and the sub-strips of Users and Workspaces no longer double the header's margin.
- **Landing headline:** two balanced lines in Spanish and English, light and dark, and the wordmark is visible on the dark page.
- **404:** "Page not found", one sentence, a button to the home page; Spanish added and covered by the catalogue test.
- **Mobile (390 px):** the Users page keeps its rhythm in Spanish, the last block scrolls clear of the language pill, nothing overflows.
- **Not re-measured:** the font sizes after the last three literals were moved onto the scale (the build ran before that edit; it is a change of three declarations to an existing token).
