package network.skypvp.paper.gui;

import network.skypvp.shared.ServerTextUtil;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class GuiTextLibrary {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   public static final int SOFT_WRAP_CHARS = 32;
   public static final String NO_ITALIC = "<!italic>";
   public static final String RESET = "<reset>";
   public static final String BODY = "<gray>";
   public static final String LABEL = "<white>";
   public static final String VALUE = "<#60a5fa>";
   public static final String HIGHLIGHT = "<white>";
   public static final String SECTION = "<#FFFFFF>";
   public static final String ACTION = "<yellow>";
   public static final String POSITIVE = "<green>";
   public static final String WARNING = "<red>";
   public static final String MUTED = "<#888888>";
   public static final String SPACER = "<!italic><dark_gray> </dark_gray><reset>";
   public static final String BULLET_PREFIX = "  \u2022 ";
   public static final String CONTINUATION_PREFIX = "    ";

   private GuiTextLibrary() {
   }

   public static String title(String accent, String text) {
      String resolvedAccent = accent != null && !accent.isBlank() ? accent : "#FFD700";
      return "<!italic><" + resolvedAccent + "><bold>" + safeText(text) + "</bold></" + resolvedAccent + "><reset>";
   }

   public static String sectionHeader(String accent, String text) {
      String resolvedAccent = accent != null && !accent.isBlank() ? accent : "#FFFFFF";
      return "<!italic><" + resolvedAccent + "><bold>" + safeText(text) + "</bold></" + resolvedAccent + "><reset>";
   }

   public static GuiTextLibrary.LoreBuilder lore() {
      return new GuiTextLibrary.LoreBuilder();
   }

   public static List<String> standardLore(List<String> bodyLines, String... footerLines) {
      GuiTextLibrary.LoreBuilder lore = lore();
      if (bodyLines != null) {
         for (String line : bodyLines) {
            lore.bullet(line);
         }
      }

      if (footerLines != null) {
         for (String line : footerLines) {
            lore.footerStrong("<yellow>", line);
         }
      }

      return lore.build();
   }

   public static List<String> wrapPlainText(String text, int limit) {
      String remaining = collapseWhitespace(text);
      if (remaining.isBlank()) {
         return List.of();
      } else {
         List<String> lines = new ArrayList<>();
         int safeLimit = Math.max(8, limit);

         while (!remaining.isBlank()) {
            String segment = takeWrappedSegment(remaining, safeLimit);
            if (segment.isBlank()) {
               break;
            }

            lines.add(segment);
            remaining = remaining.substring(segment.length()).trim();
         }

         return List.copyOf(lines);
      }
   }

   public static String safeText(String value) {
      return value != null && !value.isBlank() ? MiniMessage.miniMessage().escapeTags(value.replace('\r', ' ').replace('\n', ' ')).trim() : "";
   }

   public static String progressBar(int progress, int target, int width, String filledColor, String emptyColor) {
      int clamped = target <= 0 ? 0 : Math.min(Math.max(0, progress), target);
      int filled = target <= 0 ? 0 : (int)Math.min((long)width, Math.round((double)clamped * (double)width / (double)target));
      int empty = width - filled;
      return filledColor + "\u2588".repeat(filled) + emptyColor + "\u2591".repeat(empty) + "<reset>";
   }

   public static String progressBar(int progress, int target) {
      return progressBar(progress, target, 20, "<green>", "<#888888>");
   }

   private static String maybeBold(String text, boolean bold) {
      return bold ? "<bold>" + text + "</bold>" : text;
   }

   private static String takeWrappedSegment(String text, int limit) {
      String safeText = collapseWhitespace(text);
      if (safeText.length() <= limit) {
         return safeText;
      } else {
         int breakIndex = safeText.lastIndexOf(32, limit);
         if (breakIndex < Math.max(6, limit / 2)) {
            breakIndex = limit;
         }

         return safeText.substring(0, breakIndex).trim();
      }
   }

   private static String collapseWhitespace(String value) {
      return value == null ? "" : value.trim().replaceAll("\\s+", " ");
   }

   public static final class LoreBuilder {
      private final List<String> lines = new ArrayList<>();
      private boolean footerStarted;

      public LoreBuilder() {
      }

      public GuiTextLibrary.LoreBuilder bullet(String text) {
         this.addWrapped("<gray>", BULLET_PREFIX, CONTINUATION_PREFIX, GuiTextLibrary.safeText(stripBulletPrefix(text)), false);
         return this;
      }

      public GuiTextLibrary.LoreBuilder plain(String text) {
         return this.bullet(text);
      }

      // $VF: renamed from: raw (java.lang.String) network.SkyPvP.paper.gui.GuiTextLibrary$LoreBuilder
      public GuiTextLibrary.LoreBuilder method_243(String miniMessageLine) {
         if (miniMessageLine != null && !miniMessageLine.isBlank()) {
            this.lines.add("<!italic>" + miniMessageLine);
         }

         return this;
      }

      public GuiTextLibrary.LoreBuilder section(String title) {
         return this.section("#FFFFFF", title);
      }

      public GuiTextLibrary.LoreBuilder section(String accent, String title) {
         String safeTitle = GuiTextLibrary.safeText(title);
         if (safeTitle.isBlank()) {
            return this;
         } else {
            this.spacer();
            this.lines.add(GuiTextLibrary.sectionHeader(accent, safeTitle));
            return this;
         }
      }

      public GuiTextLibrary.LoreBuilder fact(String label, String value) {
         return this.fact(label, value, "<#60a5fa>", false);
      }

      public GuiTextLibrary.LoreBuilder fact(String label, String value, String valueColor) {
         return this.fact(label, value, valueColor, false);
      }

      public GuiTextLibrary.LoreBuilder factStrong(String label, String value, String valueColor) {
         return this.fact(label, value, valueColor, true);
      }

      public GuiTextLibrary.LoreBuilder fieldValue(String field, String value) {
         return this.fact(field, value, "<#60a5fa>", false);
      }

      public GuiTextLibrary.LoreBuilder fieldValueStrong(String field, String value) {
         return this.factStrong(field, value, "<#60a5fa>");
      }

      public GuiTextLibrary.LoreBuilder spacer() {
         if (!this.lines.isEmpty() && !this.lastIsSpacer()) {
            this.lines.add("<!italic><dark_gray> </dark_gray><reset>");
            return this;
         } else {
            return this;
         }
      }

      public GuiTextLibrary.LoreBuilder footer(String text) {
         return this.footer("<yellow>", text, false);
      }

      public GuiTextLibrary.LoreBuilder footer(String color, String text) {
         return this.footer(color, text, false);
      }

      public GuiTextLibrary.LoreBuilder footerStrong(String color, String text) {
         return this.footer(color, text, true);
      }

      public List<String> build() {
         return List.copyOf(this.lines);
      }

      private GuiTextLibrary.LoreBuilder footer(String color, String text, boolean bold) {
         String safeText = GuiTextLibrary.safeText(text);
         if (safeText.isBlank()) {
            return this;
         } else {
            if (!this.footerStarted) {
               this.spacer();
               this.footerStarted = true;
            }

            this.addWrapped(color, "", "    ", safeText, bold);
            return this;
         }
      }

      private GuiTextLibrary.LoreBuilder fact(String label, String value, String valueColor, boolean boldValue) {
         String safeLabel = GuiTextLibrary.safeText(label);
         String safeValue = GuiTextLibrary.safeText(value);
         if (safeLabel.isBlank() && safeValue.isBlank()) {
            return this;
         } else if (safeLabel.isBlank()) {
            this.addWrapped(valueColor, "", "    ", safeValue, boldValue);
            return this;
         } else {
            String prefix = BULLET_PREFIX + safeLabel + ": ";
            int firstWidth = Math.max(8, 32 - prefix.length());
            int continuationWidth = Math.max(8, 32 - CONTINUATION_PREFIX.length());
            if (safeValue.isBlank()) {
               this.lines.add("<!italic><white>" + prefix + "<reset>");
               return this;
            } else {
               String firstSegment = GuiTextLibrary.takeWrappedSegment(safeValue, firstWidth);
               this.lines.add("<!italic><white>" + prefix + "<reset>" + valueColor + GuiTextLibrary.maybeBold(firstSegment, boldValue) + "<reset>");
               String remaining = safeValue.substring(firstSegment.length()).trim();

               while (!remaining.isBlank()) {
                  String segment = GuiTextLibrary.takeWrappedSegment(remaining, continuationWidth);
                  this.lines.add("<!italic><gray>" + CONTINUATION_PREFIX + "<reset>" + valueColor + GuiTextLibrary.maybeBold(segment, boldValue) + "<reset>");
                  remaining = remaining.substring(segment.length()).trim();
               }

               return this;
            }
         }
      }

      private void addWrapped(String color, String firstPrefix, String continuationPrefix, String text, boolean bold) {
         String remaining = GuiTextLibrary.collapseWhitespace(text);
         if (!remaining.isBlank()) {
            for (boolean first = true; !remaining.isBlank(); first = false) {
               String prefix = first ? firstPrefix : continuationPrefix;
               int width = Math.max(8, 32 - prefix.length());
               String segment = GuiTextLibrary.takeWrappedSegment(remaining, width);
               this.lines.add("<!italic>" + color + prefix + GuiTextLibrary.maybeBold(segment, bold) + "<reset>");
               remaining = remaining.substring(segment.length()).trim();
            }
         }
      }

      private boolean lastIsSpacer() {
         return !this.lines.isEmpty() && "<!italic><dark_gray> </dark_gray><reset>".equals(this.lines.get(this.lines.size() - 1));
      }

      private static String stripBulletPrefix(String text) {
         if (text == null) {
            return "";
         }

         String stripped = text.stripLeading();
         return stripped.startsWith("\u2022") ? stripped.substring(1).stripLeading() : text;
      }
   }
}
