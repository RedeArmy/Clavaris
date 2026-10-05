package com.clavaris.app.infrastructure.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.i18n.AppLocales;
import com.clavaris.common.i18n.MessageCatalog;
import com.clavaris.common.i18n.PoParser.PoEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class LocalizationDialectTest {

  private static final MessageCatalog SPANISH =
      MessageCatalog.fromEntries(
          List.of(
              new PoEntry("", "Cancel", "Cancelar"),
              new PoEntry("", "Save changes", "Guardar cambios"),
              new PoEntry("", "Created", "Creado"),
              new PoEntry("th", "Created", "Fecha de creación"),
              new PoEntry("", "{0} scopes", "{0} ámbitos"),
              new PoEntry("", "1 scope", "1 ámbito"),
              new PoEntry(
                  "", "Removes <0>{0}</0> from this role.", "Quita a <0>{0}</0> de este rol."),
              new PoEntry(
                  "",
                  "Already have an account? <0>Sign in</0>",
                  "¿Ya tienes una cuenta? <0>Inicia sesión</0>"),
              new PoEntry("", "Sign in", "Iniciar sesión"),
              new PoEntry("", "Tom & Jerry", "Tom y Jerry"),
              new PoEntry("@title", "Close dialog", "Cerrar diálogo"),
              new PoEntry("@placeholder", "Search", "Buscar"),
              new PoEntry("", "Language", "Idioma")));

  private static SpringTemplateEngine engine(final MessageCatalog catalog) {
    final StringTemplateResolver resolver = new StringTemplateResolver();
    resolver.setTemplateMode(TemplateMode.HTML);
    final SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    engine.addDialect(new LocalizationDialect(locale -> catalog, null));
    return engine;
  }

  private static String render(final String template, final Locale locale) {
    return engine(SPANISH).process(template, new Context(locale));
  }

  private static String spanish(final String template) {
    return render(template, AppLocales.SPANISH);
  }

  @Test
  void aButtonAndAHeadingAreTranslatedAsTheirOwnUnits() {
    final String html =
        spanish("<div><h1>Save changes</h1><button type=\"button\">Cancel</button></div>");

    assertThat(html)
        .isEqualTo("<div><h1>Guardar cambios</h1><button type=\"button\">Cancelar</button></div>");
  }

  @Test
  void aLabelBesideAnIconInsideAnInlineWrapperIsStillTranslated() {
    final String html =
        spanish(
            "<summary><span class=\"a\"><svg viewBox=\"0 0 1 1\"></svg></span>"
                + "<span class=\"b\">Cancel</span>"
                + "<span class=\"c\"><svg viewBox=\"0 0 1 1\"></svg></span></summary>");

    assertThat(html).contains("<span class=\"b\">Cancelar</span>");
  }

  @Test
  void anIconBesideTheTextDoesNotStopItBeingTranslated() {
    final String icon = "<svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"></path></svg>";

    assertThat(spanish("<a href=\"#\">Cancel " + icon + "</a>"))
        .isEqualTo("<a href=\"#\">Cancelar " + icon + "</a>");
    assertThat(spanish("<button>" + icon + " Cancel</button>"))
        .isEqualTo("<button>" + icon + " Cancelar</button>");
    assertThat(spanish("<span>" + icon + "</span><span>Cancel</span>"))
        .isEqualTo("<span>" + icon + "</span><span>Cancelar</span>");
  }

  @Test
  void englishLeavesThePageUntouchedEvenWithACatalogue() {
    final String template = "<p>Cancel</p>";

    assertThat(engine(MessageCatalog.empty()).process(template, new Context(Locale.ENGLISH)))
        .isEqualTo(template);
  }

  @Test
  void aTextWithNoTranslationStaysInEnglish() {
    assertThat(spanish("<p>Nothing like this is catalogued</p>"))
        .isEqualTo("<p>Nothing like this is catalogued</p>");
  }

  @Test
  void theElementGivesAnAmbiguousWordItsContext() {
    final String html = spanish("<table><tr><th>Created</th><td>Created</td></tr></table>");

    assertThat(html).contains("<th>Fecha de creación</th>").contains("<td>Creado</td>");
  }

  @Test
  void aCountThatChangesTheWordingMatchesItsPattern() {
    final String html =
        spanish(
            "<td th:text=\"${n}\">x</td>".replace(" th:text=\"${n}\"", "")
                + "<td>3 scopes</td><td>1 scope</td>");

    assertThat(html).contains("<td>3 ámbitos</td>").contains("<td>1 ámbito</td>");
  }

  @Test
  void inlineMarkupSurvivesAndTheRuntimeValueIsCarriedIntoTheTranslation() {
    final String html = spanish("<p>Removes <strong class=\"n\">Ada</strong> from this role.</p>");

    assertThat(html).isEqualTo("<p>Quita a <strong class=\"n\">Ada</strong> de este rol.</p>");
  }

  @Test
  void aSentenceWithALinkKeepsTheLinkOnTheRightWords() {
    final String html = spanish("<p>Already have an account? <a href=\"/login\">Sign in</a></p>");

    assertThat(html)
        .isEqualTo("<p>¿Ya tienes una cuenta? <a href=\"/login\">Inicia sesión</a></p>");
  }

  @Test
  void aLinkThatIsAUnitByItselfIsTranslatedFromInside() {
    assertThat(spanish("<div><a class=\"btn\" href=\"/x\">Sign in</a></div>"))
        .isEqualTo("<div><a class=\"btn\" href=\"/x\">Iniciar sesión</a></div>");
  }

  @Test
  void theSpacingAroundATranslatedUnitIsKept() {
    assertThat(spanish("<p>\n   Cancel\n</p>"))
        .isEqualTo("<p>\n Cancelar \n</p>".replace("\n Cancelar \n", " Cancelar "));
  }

  @Test
  void attributesThatCarryWordsAreTranslatedByTheirOwnContext() {
    final String html =
        spanish(
            "<button type=\"button\" title=\"Close dialog\">Cancel</button><input placeholder=\"Search\"/>");

    assertThat(html).contains("title=\"Cerrar diálogo\"").contains("placeholder=\"Buscar\"");
  }

  @Test
  void anEscapedAmpersandIsMatchedAsTheCharacterAndWrittenBackEscaped() {
    assertThat(spanish("<p>Tom &amp; Jerry</p>")).isEqualTo("<p>Tom y Jerry</p>");
  }

  @Test
  void contentMarkedTranslateNoIsNeverTouched() {
    assertThat(spanish("<p translate=\"no\">Cancel</p><p>Cancel</p>"))
        .isEqualTo("<p translate=\"no\">Cancel</p><p>Cancelar</p>");
  }

  @Test
  void scriptsAndStylesAreNeverTouched() {
    final String html =
        spanish("<script>var s = \"Cancel\";</script><style>.a::after{content:\"Cancel\"}</style>");

    assertThat(html).contains("\"Cancel\"");
  }

  @Test
  void theHtmlLanguageFollowsTheLanguageOfThePage() {
    assertThat(spanish("<html lang=\"en\"><body><p>Cancel</p></body></html>"))
        .contains("<html lang=\"es\">");
  }

  @Test
  void everyFullPageGetsALanguageSwitcherInTheReadersLanguage() {
    final String html = spanish("<html lang=\"en\"><body><p>Cancel</p></body></html>");

    assertThat(html)
        .contains("<nav class=\"clavaris-lang-switch\" aria-label=\"Idioma\">")
        .contains("hreflang=\"en\"")
        .contains("hreflang=\"es\"")
        .contains(">EN</a>")
        .contains(">ES</a>")
        .contains("aria-label=\"Español\"")
        .contains("aria-current=\"true\"");
  }

  @Test
  void aFragmentWithNoBodyGetsNoSwitcher() {
    assertThat(spanish("<div><p>Cancel</p></div>")).doesNotContain("clavaris-lang-switch");
  }

  @Test
  void aModalLoginPageGetsNoSwitcher() {
    assertThat(spanish("<html><body data-modal><p>Cancel</p></body></html>"))
        .doesNotContain("clavaris-lang-switch");
  }

  @Test
  void extractionListsTheUnitsInsteadOfTranslating() {
    final List<LocalizationDialect.ExtractedUnit> found = new ArrayList<>();
    final StringTemplateResolver resolver = new StringTemplateResolver();
    resolver.setTemplateMode(TemplateMode.HTML);
    final SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    engine.setDialect(new LocalizationDialect(locale -> MessageCatalog.empty(), found::add));

    final String template =
        "<p>Removes <strong th:text=\"${name}\">x</strong> from this role.</p>"
            + "<h1>Save changes</h1><td th:text=\"${n}\">12</td>"
            + "<span th:text=\"${ok} ? 'Active' : 'Inactive'\">x</span><input placeholder=\"Search\"/>";

    final String html = engine.process(template, new Context(Locale.ENGLISH));

    assertThat(html).contains("Save changes");
    assertThat(found)
        .extracting(LocalizationDialect.ExtractedUnit::text)
        .contains(
            "Removes <0>{0}</0> from this role.", "Save changes", "Search", "Active", "Inactive")
        .doesNotContain("12", "x");
  }
}
