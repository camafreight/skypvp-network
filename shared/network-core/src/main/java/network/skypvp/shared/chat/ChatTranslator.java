package network.skypvp.shared.chat;

/**
 * Azure Cognitive Services chat translation backend.
 */
public interface ChatTranslator {
    /** Provider id for logs ({@code azure}). */
    String providerId();

    boolean enabled();

    String translate(String text, String fromLocale, String toLocale);

    /** Non-secret summary for startup diagnostics. */
    String configuredSummary();
}
