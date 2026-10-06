package com.clavaris.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.i18n.PoParser.PoEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageCatalogTest {

  private static MessageCatalog catalog(final PoEntry... entries) {
    return MessageCatalog.fromEntries(List.of(entries));
  }

  @Test
  void anExactEntryTranslatesAndAMissingOneDoesNot() {
    final MessageCatalog catalog = catalog(new PoEntry("", "Cancel", "Cancelar"));

    assertThat(catalog.translate("button", "Cancel")).contains("Cancelar");
    assertThat(catalog.translate("button", "Delete")).isEmpty();
  }

  @Test
  void layoutNeverAffectsALookup() {
    final MessageCatalog catalog =
        catalog(new PoEntry("", "Read this carefully.", "Lee con atención."));

    assertThat(catalog.translate("p", "  Read   this\n   carefully.  "))
        .contains("Lee con atención.");
  }

  @Test
  void aContextualEntryWinsInItsElementAndTheGeneralOneAppliesEverywhereElse() {
    final MessageCatalog catalog =
        catalog(
            new PoEntry("", "Created", "Creado"),
            new PoEntry("th", "Created", "Fecha de creación"));

    assertThat(catalog.translate("th", "Created")).contains("Fecha de creación");
    assertThat(catalog.translate("td", "Created")).contains("Creado");
  }

  @Test
  void aPlaceholderEntryMatchesRuntimeValues() {
    final MessageCatalog catalog =
        catalog(
            new PoEntry("", "{0} scopes", "{0} ámbitos"), new PoEntry("", "1 scope", "1 ámbito"));

    assertThat(catalog.translate("td", "3 scopes")).contains("3 ámbitos");
    assertThat(catalog.translate("td", "1 scope")).contains("1 ámbito");
  }

  @Test
  void placeholdersCanBeReorderedInATranslation() {
    final MessageCatalog catalog =
        catalog(new PoEntry("", "{0} signed in on {1}", "El {1}, {0} inició sesión"));

    assertThat(catalog.translate("p", "Ada signed in on May 4th"))
        .contains("El May 4th, Ada inició sesión");
  }

  @Test
  void theMostSpecificPatternWinsOverAGreedierOne() {
    final MessageCatalog catalog =
        catalog(
            new PoEntry("", "{0} ago", "hace {0}"),
            new PoEntry("", "{0} hours ago", "hace {0} horas"));

    assertThat(catalog.translate("time", "3 hours ago")).contains("hace 3 horas");
    assertThat(catalog.translate("time", "5 minutes ago")).contains("hace 5 minutes");
  }

  @Test
  void inlineMarkupTagsTravelThroughAPattern() {
    final MessageCatalog catalog =
        catalog(
            new PoEntry(
                "", "Removes <0>{0}</0> from this role.", "Quita a <0>{0}</0> de este rol."));

    assertThat(catalog.translate("p", "Removes <0>Ada Lovelace</0> from this role."))
        .contains("Quita a <0>Ada Lovelace</0> de este rol.");
  }

  @Test
  void anEmptyTranslationIsReportedAsUntranslatedNotUsed() {
    final MessageCatalog catalog = catalog(new PoEntry("", "Pending", ""));

    assertThat(catalog.translate("p", "Pending")).isEmpty();
    assertThat(catalog.untranslated()).containsExactly("Pending");
  }

  @Test
  void theEmptyCatalogLeavesEverythingInEnglish() {
    assertThat(MessageCatalog.empty().translate("p", "Anything")).isEmpty();
  }

  @Test
  void aTranslatableCaptureIsTranslatedButAPlainOneIsLeftAlone() {
    final MessageCatalog catalog =
        catalog(
            new PoEntry("", "Client ID", "ID de cliente"),
            new PoEntry("", "Copy {0:t}", "Copiar {0}"),
            new PoEntry("", "Removes {0} from this role.", "Quita a {0} de este rol."));

    assertThat(catalog.translate("@aria-label", "Copy Client ID")).contains("Copiar ID de cliente");
    assertThat(catalog.translate("@aria-label", "Copy Something else"))
        .contains("Copiar Something else");
    // A name that happens to be an interface word is user data and must stay as typed.
    assertThat(catalog.translate("p", "Removes Client ID from this role."))
        .contains("Quita a Client ID de este rol.");
  }
}
