package com.clavaris.app.infrastructure.config;

import java.util.Set;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IPostProcessorDialect;
import org.thymeleaf.engine.AbstractTemplateHandler;
import org.thymeleaf.model.IComment;
import org.thymeleaf.postprocessor.IPostProcessor;
import org.thymeleaf.postprocessor.PostProcessor;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * Keeps HTML comments out of every rendered response.
 *
 * <p>The templates carry long design notes as ordinary {@code <!-- ... -->} comments (architecture
 * decision references, rationale, history). Thymeleaf copies those to the browser, so every page
 * shipped internal notes to anyone who viewed its source, and added them to every response's size.
 * This dialect drops each comment while the template is rendered, so the notes stay in the source
 * where they help maintainers and never reach a client.
 *
 * <p>Two kinds of comment are kept on purpose: conditional comments ({@code <!--[if ...]>}), which
 * browsers act on, and any comment that starts with {@code !} ({@code <!--! keep -->}), the usual
 * way to mark one a page really wants to ship. Thymeleaf's own parser-level comments ({@code <!--/*
 * ... *&#47;-->}) are removed at parse time and never reach this handler.
 */
public final class HtmlCommentStrippingDialect extends AbstractDialect
    implements IPostProcessorDialect {

  private static final String NAME = "Clavaris HTML comment stripper";
  // After the standard processors, so nothing that builds output on a comment sees it dropped.
  private static final int PRECEDENCE = 1000;

  public HtmlCommentStrippingDialect() {
    super(NAME);
  }

  @Override
  public int getDialectPostProcessorPrecedence() {
    return PRECEDENCE;
  }

  @Override
  public Set<IPostProcessor> getPostProcessors() {
    return Set.of(new PostProcessor(TemplateMode.HTML, CommentDroppingHandler.class, PRECEDENCE));
  }

  /**
   * Passes every event through, except a comment that is neither conditional nor marked to keep.
   */
  public static final class CommentDroppingHandler extends AbstractTemplateHandler {

    private static final String CONDITIONAL = "[";
    private static final String KEEP = "!";

    @Override
    public void handleComment(final IComment comment) {
      final String content = comment.getContent();
      if (content.startsWith(CONDITIONAL) || content.startsWith(KEEP)) {
        super.handleComment(comment);
      }
    }
  }
}
