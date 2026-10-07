package com.clavaris.identity.infrastructure.adapter.out.mail;

/**
 * The look of a Clavaris email: the palette and type the web console already uses, written as
 * inline values because a mail client cannot read the console's stylesheet.
 */
final class EmailStyle {

  // System fonts: Geist, the console's typeface, is not available in an inbox.
  /* default */ static final String SANS =
      "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
  /* default */ static final String MONO = "ui-monospace,SFMono-Regular,Menlo,Consolas,monospace";

  /* default */ static final String CANVAS = "#f5f5f2";
  /* default */ static final String SURFACE = "#ffffff";
  /* default */ static final String SUBTLE = "#fafafb";
  /* default */ static final String BORDER = "#e8e8ec";
  /* default */ static final String INK = "#131316";
  /* default */ static final String MUTED = "#5e5f6e";
  /* default */ static final String AMBER = "#f59e0b";
  /* default */ static final String DANGER = "#dc2626";
  /* default */ static final String WHITE = "#ffffff";

  /* default */ static final String ROUNDED = "border-radius:10px;";
  /* default */ static final String RULE = "1px solid " + BORDER;

  // For the clients that honour a stylesheet: a dark palette for readers who prefer one, and a
  // full-width button on a phone. Everything else is also written inline, so the email is complete
  // without it.
  /* default */ static final String STYLESHEET =
      """
      @media (max-width:620px){
        .frame{padding:20px 12px!important}
        .pad{padding:28px 22px!important}
        .btn,.btn tbody,.btn tr,.btn td,.btn a{display:block!important;width:100%!important;box-sizing:border-box}
      }
      @media (prefers-color-scheme:dark){
        .bg{background:#0f0f11!important}
        .card{background:#18181b!important;border-color:#2f3037!important}
        .ink{color:#f7f7f8!important}
        .muted{color:#9394a1!important}
        .well{background:#131316!important;border-color:#2f3037!important}
        .rule{border-color:#2f3037!important}
      }
      """;

  private EmailStyle() {
    // Constants only.
  }
}
