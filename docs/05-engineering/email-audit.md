# Transactional email audit and redesign

Clavaris sends twelve transactional emails. This is what each one is for, what the audit found, and how they are built now.

## The emails

All of them are composed in `identity-module/.../adapter/out/mail` and delivered through Resend (ADR-0011). Every email is sent while the person who will read it is waiting on the request that triggered it, so the language of that request is the language of the email.

| # | Email | Sent when | Action | Expires |
| --- | --- | --- | --- | --- |
| 1 | Verify your email address (link) | An account is registered, or verification is requested again | Button | 24 hours |
| 2 | Verify your email address (code) | Registration under a code-based verification policy | Six-digit code | 24 hours |
| 3 | Reset your password | A password reset is requested | Button | 30 minutes, single use |
| 4 | Confirm linking your Google / GitHub account | A social sign-in matches an existing account's email | Button | 24 hours, single use |
| 5 | Your sign-in code | Passwordless sign-in by code | Six-digit code | 10 minutes |
| 6 | Your sign-in link | Passwordless sign-in by link | Button | 10 minutes, single use |
| 7 | Confirm this new device | A sign-in from a device the account has not used | Six-digit code | 10 minutes |
| 8 | New sign-in to your account | A successful sign-in from a new device | "This wasn't me" button | 7 days, single use |
| 9–12 | The platform-tier versions of 1, 3, 4 and 8 | The same events, for console accounts | Same | Same |

Emails 1, 3, 4 and 8 exist twice (an organization's end user, and a console account). They share one definition and differ only in naming "your Clavaris account" where the organization tier says "your account".

## What the audit found

Before the redesign every email was a bare `<p>` with an unstyled link.

| Area | Finding |
| --- | --- |
| Presentation | No brand, layout, dark mode or phone layout; the link was the browser's default blue |
| Delivery | HTML only, no plain-text alternative, which hurts deliverability and accessibility |
| Content | The sign-in time was a raw ISO timestamp (`2026-08-31T10:00:00Z`); the provider was the enum constant (`GOOGLE`); a missing device or address would print `null` |
| Usability | No copy-and-paste fallback if the button is stripped; no preview line; the destructive action looked like any other link |
| Consistency | The same sentence was written five different ways across emails |
| Language | English only, although the console is available in Spanish |
| Security | Sound: values the server did not write (device and address come from request headers) were already escaped; links carry a URL-encoded single-use token; subjects never contain a code |

## How they are built now

- **`EmailContent`** says what an email contains (title, preview line, paragraphs, one action, a code, label and value rows, small print) with no layout.
- **`EmailRenderer` and `EmailBlocks`** turn that into HTML and into a plain-text twin, so the two cannot disagree. Every value passes through HTML escaping.
- **`EmailStyle` and `EmailHtml`** hold the look and the few building blocks.
- **`Emails`** has one method per email; **`EmailCopy`** holds every sentence; **`EmailValues`** formats a provider by its brand and a missing value as a dash.

The design follows the console: a 560px card on a warm canvas with the amber accent line, the amber dot beside the wordmark, 9px buttons with dark text, the console's neutral greys. It uses no images (nothing to block or track, and it reads the same with images off), system fonts, tables and inline styles, with a small stylesheet for the clients that honour one. That stylesheet switches to a dark palette when the reader prefers it and makes the button full width on a phone. The width is fluid up to 560px, with the width attribute kept for Outlook. A destructive action ("This wasn't me") is red.

## Language

Email text uses the same gettext-style catalogue as the pages (see `internationalization.md`), with the context `email` so it never collides with a page entry. The Spanish text lives in `identity-module/src/main/resources/i18n/messages_es_email.po`. `EmailsTest` fails if a sentence in `EmailCopy` has no Spanish translation, or if a translation changes its placeholders.

## Trying them

`EmailsTest` can write every email, in both languages, as HTML and text:

```
./mvnw -pl identity-module -am test -Dtest=EmailsTest -Demail.previews=<directory>
```

Open the HTML files in a browser. Switch the browser to dark mode or a phone width to check the other two layouts.

## Not done

- **Per-organization branding.** The ports pass an `OrganizationId`, not a name or a logo, so an organization's end users still receive a Clavaris-branded email. Branding it needs the organization's name at the send site.
- **A real inbox check.** The layout was checked in a browser, in light and dark, at desktop and phone width. It was not opened in Outlook, Gmail or Apple Mail, and Outlook's rendering engine in particular can differ.
- **Sending in a language other than the request's.** There is no stored per-account language, so an email written outside a request (none exists today) would be English.
