package com.clavaris.app.infrastructure.config;

import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.resource.ResourceUrlProvider;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

/**
 * TD-PERF-024 (content-hash revision, 2026-09-14): exposes {@code #resourceUrl.url(path)} to every
 * Thymeleaf template, resolving a plain static-resource path (e.g. {@code /css/clavaris.css}) to
 * its content-hashed URL (e.g. {@code /css/clavaris-a1b2c3d4.css}) via the same {@link
 * ResourceUrlProvider} Spring's own resource-chain versioning already computes.
 *
 * <p>Deliberately not {@link FilterOrderingConfig}'s own {@code
 * org.springframework.web.servlet.resource.ResourceUrlEncodingFilter} alone — confirmed live (a
 * real integration test asserted on the actual rendered HTML) that Thymeleaf's core {@code
 * StandardLinkBuilder} (what every {@code @{...}} expression resolves through) never calls {@code
 * HttpServletResponse#encodeURL}, the one hook that filter depends on; that mechanism is real and
 * exists (it backs JSP's {@code <spring:url>} tag), but Thymeleaf's own link expressions simply
 * never invoke it. This dialect is the actual integration point for this template engine — every
 * {@code th:href="@{/css/clavaris.css}"} in this codebase was rewritten to {@code
 * th:href="${#resourceUrl.url('/css/clavaris.css')}"} to use it — the {@code #} prefix is not
 * optional, confirmed live: Thymeleaf expression objects are only resolved through it (SpEL's own
 * {@code #variable} reference syntax); the bare, unprefixed name silently evaluates to {@code null}
 * instead of failing loudly, which is exactly why every call site is still guarded with {@code !=
 * null} below — the fallback exists for a standalone unit test's own hand-built {@code
 * SpringTemplateEngine} (this dialect is never registered on one of those), not for this typo class
 * of bug.
 *
 * <p>{@link ResourceUrlProvider#getForLookupPath(String)}, not {@code #getForRequestUrl(request,
 * path)} — confirmed live (a diagnostic assertion against the real bean) that {@code
 * getForLookupPath} alone already returns the correct hashed path for a plain root-relative
 * static-resource path like the ones used everywhere in this codebase (none of the six call sites
 * is ever behind a non-root servlet mapping or context path), while {@code getForRequestUrl} needs
 * the exact request-relative form its own {@code getLookupPathIndex} expects and silently fell
 * through to the unmodified input otherwise — no {@code HttpServletRequest}/Thymeleaf web exchange
 * plumbing needed at all once that's the call being made.
 *
 * <p>PMD.LongVariable throughout (this class and its two nested ones): {@code
 * resourceUrlProvider}/{@code expressionObjectName}/{@code EXPRESSION_OBJECT_NAME}/{@code
 * ALL_EXPRESSION_OBJECT_NAMES} all name exactly what they are — same "deliberate, descriptive name
 * over an arbitrary shortening" convention this codebase applies everywhere else this rule fires.
 * PMD.OnlyOneReturn on {@code buildObject}: the "not my name, null" early exit is the whole point
 * of an {@link IExpressionObjectFactory}'s own contract (one engine can register many dialects'
 * factories in a chain), same rationale as every other short-circuiting lookup in this codebase.
 */
@SuppressWarnings({"PMD.LongVariable", "PMD.OnlyOneReturn"})
@Component
class ResourceUrlDialect extends AbstractDialect implements IExpressionObjectDialect {

  private static final String EXPRESSION_OBJECT_NAME = "resourceUrl";
  private static final Set<String> ALL_EXPRESSION_OBJECT_NAMES = Set.of(EXPRESSION_OBJECT_NAME);

  private final ResourceUrlProvider resourceUrlProvider;

  /* package */ ResourceUrlDialect(final ResourceUrlProvider resourceUrlProvider) {
    super("ResourceUrl");
    this.resourceUrlProvider = resourceUrlProvider;
  }

  @Override
  public IExpressionObjectFactory getExpressionObjectFactory() {
    return new ResourceUrlExpressionObjectFactory();
  }

  /** The one object this dialect contributes — see {@link ResourceUrl#url(String)}. */
  private final class ResourceUrlExpressionObjectFactory implements IExpressionObjectFactory {

    /* package */ ResourceUrlExpressionObjectFactory() {
      // Intentionally empty — this class holds no state of its own, only the outer
      // ResourceUrlDialect's resourceUrlProvider, already captured by its enclosing-instance link.
    }

    @Override
    public Set<String> getAllExpressionObjectNames() {
      return ALL_EXPRESSION_OBJECT_NAMES;
    }

    @Override
    public Object buildObject(final IExpressionContext context, final String expressionObjectName) {
      if (!EXPRESSION_OBJECT_NAME.equals(expressionObjectName)) {
        return null;
      }
      return new ResourceUrl(resourceUrlProvider);
    }

    // Cacheable would be safe too (the provider itself is a singleton bean), but this object is
    // cheap enough to rebuild that there's no real reason to opt into the extra cache-key
    // machinery FALSE already skips.
    @Override
    public boolean isCacheable(final String expressionObjectName) {
      return false;
    }
  }

  /* package */ static final class ResourceUrl {

    private final ResourceUrlProvider resourceUrlProvider;

    private ResourceUrl(final ResourceUrlProvider resourceUrlProvider) {
      this.resourceUrlProvider = resourceUrlProvider;
    }

    // PMD.PublicMemberInNonPublicType: must stay public even though ResourceUrl itself is
    // package-private — Thymeleaf's SpEL evaluator calls this reflectively from every
    // ${#resourceUrl.url(...)} template expression, which needs the method itself, not the
    // enclosing type, to be public.
    @SuppressWarnings("PMD.PublicMemberInNonPublicType")
    public String url(final String path) {
      final String resolved = resourceUrlProvider.getForLookupPath(path);
      return resolved != null ? resolved : path;
    }
  }
}
