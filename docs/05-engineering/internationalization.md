# Internationalisation and localisation

Clavaris renders every page in English or Spanish. A language button (EN | ES, bottom-left of every page) switches the whole interface, including dates, numbers, sizes and generated messages. Adding a language is a catalogue file, not a code change.

## How it works

Templates stay written in plain English. A Thymeleaf post-processor (`LocalizationDialect`, in `app/.../infrastructure/i18n`) sees the finished page, splits it into translatable units, and replaces each unit with its catalogue translation. A text with no translation stays in English, so a gap is never a broken page.

| Concern | Where |
| --- | --- |
| Locale choice (`?lang=`, cookie `clavaris-lang`, `Accept-Language`) | `LocaleConfig` |
| Segmenting text and attributes, inline markup | `LocalizingHandler`, `RunUnit`, `RunTranslator`, `AttributeLocalizer` |
| Language button | `LanguageSwitcher` (injected before `</body>`, not into `data-modal` fragments) |
| Catalogue loading and lookup | `common/.../i18n/MessageCatalog`, `PoParser`, `MessageCatalogs` |
| Dates, numbers, currency, sizes | `LocalizedDateFormatter`, `LocalizedNumbers`, `AppLocales` |
| Browser-side strings | `static/js/i18n.js` |

Locale resolution order: `?lang=xx` (then stored in the cookie), the cookie, `Accept-Language`, English. Outside a request the locale is always English, never the JVM default. Every response carries `Content-Language` and `<html lang>`.

## Catalogue format

`app/src/main/resources/i18n/messages_<language>*.po` (any module may add files; all are merged). The English text is the key.

```
msgid "Save changes"
msgstr "Guardar cambios"

msgctxt "button"
msgid "Open"
msgstr "Abrir"
```

- **Context** (`msgctxt`) is the element name (`button`, `th`, `h1`…) or `@attr` for an attribute (`@placeholder`). Use it only when one English word needs two translations. Lookup tries the context first, then any context.
- **Placeholders** `{0}`, `{1}` match runtime values (counts, names, dates). Plain `{n}` is copied untouched, which keeps user data (names, emails, ids) from ever being translated. `{n:t}` marks a capture that is itself interface text and is translated too (`"Copy {0:t}"` matches "Copy Client ID").
- **Inline markup** appears as numbered tags: `Removes <0>{1}</0> from this role` lets the translation reorder words around the tag.
- Whitespace is collapsed before lookup, so layout never matters.
- Put plurals as separate entries (`1 permission`, `{0} permissions`).

Mark text that must never be translated (user content, code samples) with `translate="no"`. A `<textarea>`'s own text is never translated (it is what the person typed), but its attributes, such as the placeholder, are.

### What counts as one unit

The handler groups text the way a person reads it: a button, a heading, or a sentence with its inline markup (`a`, `span`, `strong`, `code`, `time`…). Two rules are worth knowing:

- **Icons** (`<svg>`) at the edges of a label are kept where they are and left out of the text that is looked up, so `<a><span><svg/></span> Open <svg/></a>` is the unit `Open`. An element that holds only an icon counts as an icon.
- **An icon in the middle of a sentence**, or a stray tag, makes the group untranslatable. `SpanishCatalogTest` reports these under the context `untranslatable` instead of letting them stay English silently; restructure the template so the icon sits at an edge.

## Adding or changing text

1. Write the English in the template or Java message.
2. Add the Spanish entry. `SpanishCatalogTest` fails with the exact list of units that lack one, so nothing ships half-translated.
3. The same test checks that every placeholder and tag in the English also appears in the translation.

To list what the templates currently ask for (context, text, templates), run `./mvnw -pl app -am test -Dtest=TemplateUnitsDumpTest -Dsurefire.failIfNoSpecifiedTests=false -Di18n.dump=units.tsv`. On a machine whose default charset is not UTF-8, add `-Dfile.encoding=UTF-8` to the JVM options.

## Adding a language

1. Add the `Locale` to `AppLocales.SUPPORTED` (the switcher picks it up, labelled with its own name).
2. Add `messages_<lang>.po`, plus formats in `LocalizedDateFormatter`/`LocalizedNumbers` if the defaults do not suit.
3. Add the table to `i18n.js` for browser-side strings (confirmation prompts, toasts, the phone picker, the picture uploader). Server-rendered text never goes through it.
4. Copy `SpanishCatalogTest` for the new catalogue.

## Emails

The emails Clavaris sends are translated from the same catalogue format, but not by the page translator: their text is written in Java (`EmailCopy`) and looked up with the context `email`. Their Spanish lives in `identity-module/src/main/resources/i18n/messages_es_email.po`, which is merged with the page catalogue like any other `messages_<language>*.po`. The language is the one of the request being served, since every email is sent while its reader waits on that request. See `email-audit.md`.

## Formats

| | English | Spanish |
| --- | --- | --- |
| Date | `October 5, 2026` | `5 de octubre de 2026` |
| Date and time (UTC) | `October 5, 2026 at 14:30 UTC` | `5 de octubre de 2026, 14:30 UTC` |
| Number | `12,345.5` | `12.345,5` (four digits are not grouped: `1234,5`) |
| Percent / currency | via `LocalizedNumbers` | via `LocalizedNumbers` |
| File size | decimal units (`1.5 MB`) | decimal units (`1,5 MB`) |

Dates are shown in UTC in both languages.

## Spanish style

Neutral Latin American Spanish, informal *tú*. Glossary: Organization → Organización, Workspace → espacio de trabajo, Secret Key → clave secreta, Signing Key → clave de firma, Passkey → clave de acceso, OAuth Client → cliente OAuth, Webhook Endpoint → endpoint de webhook, Sign in → Iniciar sesión, Scope → ámbito. Technical identifiers (`client_credentials`, `JWKS`, `RS256`) and product names stay as they are.
