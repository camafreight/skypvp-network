package network.skypvp.paper.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.bukkit.Material;

/**
 * Temporary visual feedback shown on a GUI slot after an action completes.
 */
public final class GuiFeedback {

    public static final Material DEFAULT_SUCCESS_ICON = Material.LIME_WOOL;
    public static final Material DEFAULT_FAILURE_ICON = Material.BARRIER;

    private final boolean success;
    private final Material successIcon;
    private final Material failureIcon;
    private final String title;
    private final List<String> detailLines;
    private final List<String> footerLines;
    private final long expiresAtMs;

    private GuiFeedback(
            boolean success,
            Material successIcon,
            Material failureIcon,
            String title,
            List<String> detailLines,
            List<String> footerLines,
            long expiresAtMs
    ) {
        this.success = success;
        this.successIcon = Objects.requireNonNull(successIcon, "successIcon");
        this.failureIcon = Objects.requireNonNull(failureIcon, "failureIcon");
        this.title = Objects.requireNonNull(title, "title");
        this.detailLines = List.copyOf(detailLines == null ? List.of() : detailLines);
        this.footerLines = List.copyOf(footerLines == null ? List.of() : footerLines);
        this.expiresAtMs = expiresAtMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static GuiFeedback success(String title, List<String> detailLines, long durationMs) {
        return builder().success(true).title(title).detailLines(detailLines).durationMs(durationMs).build();
    }

    public static GuiFeedback failure(String title, List<String> detailLines, long durationMs) {
        return builder().success(false).title(title).detailLines(detailLines).durationMs(durationMs).build();
    }

    public boolean success() {
        return this.success;
    }

    public Material successIcon() {
        return this.successIcon;
    }

    public Material failureIcon() {
        return this.failureIcon;
    }

    public String title() {
        return this.title;
    }

    public List<String> detailLines() {
        return this.detailLines;
    }

    public List<String> footerLines() {
        return this.footerLines;
    }

    public long expiresAtMs() {
        return this.expiresAtMs;
    }

    public boolean expired() {
        return System.currentTimeMillis() >= this.expiresAtMs;
    }

    public int remainingSeconds() {
        long remainingMs = this.expiresAtMs - System.currentTimeMillis();
        if (remainingMs <= 0L) {
            return 0;
        }
        return (int) Math.ceil(remainingMs / 1000.0);
    }

    public static final class Builder {
        private boolean success = true;
        private Material successIcon = DEFAULT_SUCCESS_ICON;
        private Material failureIcon = DEFAULT_FAILURE_ICON;
        private String title = "";
        private final List<String> detailLines = new ArrayList<>();
        private final List<String> footerLines = new ArrayList<>();
        private long durationMs = GuiFeedbackService.DEFAULT_DURATION_MS;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder successIcon(Material successIcon) {
            this.successIcon = successIcon;
            return this;
        }

        public Builder failureIcon(Material failureIcon) {
            this.failureIcon = failureIcon;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder detailLine(String line) {
            if (line != null && !line.isBlank()) {
                this.detailLines.add(line);
            }
            return this;
        }

        public Builder detailLines(List<String> lines) {
            this.detailLines.clear();
            if (lines != null) {
                for (String line : lines) {
                    this.detailLine(line);
                }
            }
            return this;
        }

        public Builder footerLine(String line) {
            if (line != null && !line.isBlank()) {
                this.footerLines.add(line);
            }
            return this;
        }

        public Builder footerLines(List<String> lines) {
            this.footerLines.clear();
            if (lines != null) {
                for (String line : lines) {
                    this.footerLine(line);
                }
            }
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = Math.max(0L, durationMs);
            return this;
        }

        public GuiFeedback build() {
            return new GuiFeedback(
                    this.success,
                    this.successIcon,
                    this.failureIcon,
                    this.title,
                    Collections.unmodifiableList(new ArrayList<>(this.detailLines)),
                    Collections.unmodifiableList(new ArrayList<>(this.footerLines)),
                    System.currentTimeMillis() + this.durationMs
            );
        }
    }
}
