package network.skypvp.paper.gui;

import java.util.function.Consumer;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class GuiButtonLibrary {
   private GuiButtonLibrary() {
   }

   public static ItemStack primaryAction(Material material, String title, Consumer<GuiTextLibrary.LoreBuilder> loreConsumer) {
      return build(material, "#FFD700", title, loreConsumer);
   }

   public static ItemStack secondaryAction(Material material, String title, Consumer<GuiTextLibrary.LoreBuilder> loreConsumer) {
      return build(material, "#FFFFFF", title, loreConsumer);
   }

   public static ItemStack infoCard(Material material, String title, Consumer<GuiTextLibrary.LoreBuilder> loreConsumer) {
      return secondaryAction(material, title, loreConsumer);
   }

   public static ItemStack positiveAction(Material material, String title, Consumer<GuiTextLibrary.LoreBuilder> loreConsumer) {
      return build(material, "#55FF55", title, loreConsumer);
   }

   public static ItemStack warningAction(Material material, String title, Consumer<GuiTextLibrary.LoreBuilder> loreConsumer) {
      return build(material, "#FF5555", title, loreConsumer);
   }

   public static ItemStack close(String summary) {
      return secondaryAction(Material.COMPASS, "Close", lore -> lore.bullet(summary).footer("<#888888>", "Click to close"));
   }

   public static ItemStack back(String summary) {
      return secondaryAction(Material.ARROW, "Back", lore -> lore.bullet(summary).footer("<#888888>", "Click to go back"));
   }

   public static ItemStack backToMainMenu() {
      return back("Return to the network menu");
   }

   public static ItemStack locked(Material material, String title, String reason) {
      return build(material, "#888888", title, lore -> lore
            .plain(reason)
            .footerStrong("<red>", "Locked during raid"));
   }

   public static ItemStack toggle(boolean enabled, String title, Consumer<GuiTextLibrary.LoreBuilder> loreConsumer) {
      Material material = enabled ? Material.LIME_DYE : Material.GRAY_DYE;
      String accent = enabled ? "#55FF55" : "#888888";
      return build(material, accent, title, lore -> {
         lore.fact("Status", enabled ? "ON" : "OFF");
         if (loreConsumer != null) {
            loreConsumer.accept(lore);
         }
         lore.footer("<#888888>", "Click to toggle");
      });
   }

   public static ItemStack cancel(String summary) {
      return warningAction(Material.RED_WOOL, "Cancel", lore -> lore.bullet(summary).footer("<#888888>", "Click to go back"));
   }

   public static ItemStack previousPage(int currentPage, int totalPages) {
      return secondaryAction(
         Material.ARROW, "Back Page", lore -> lore.fact("Page", currentPage + " / " + totalPages).footerStrong("<yellow>", "Click for the previous page")
      );
   }

   public static ItemStack nextPage(int currentPage, int totalPages) {
      return secondaryAction(
         Material.ARROW, "Next Page", lore -> lore.fact("Page", currentPage + 2 + " / " + totalPages).footerStrong("<yellow>", "Click for the next page")
      );
   }

   public static ItemStack feedbackAction(GuiFeedback feedback) {
      Objects.requireNonNull(feedback, "feedback");
      Material icon = feedback.success() ? feedback.successIcon() : feedback.failureIcon();
      String accent = feedback.success() ? "#55FF55" : "#FF5555";
      return build(icon, accent, feedback.title(), lore -> {
         for (String line : feedback.detailLines()) {
            lore.plain(line);
         }
         for (String line : feedback.footerLines()) {
            if (!line.isBlank()) {
               lore.plain(line);
            }
         }
         lore.plain(" ");
         int remainingSeconds = Math.max(0, feedback.remainingSeconds());
         lore.footerStrong("<red>", remainingSeconds + "s");
      });
   }

   private static ItemStack build(Material material, String accent, String title, Consumer<GuiTextLibrary.LoreBuilder> loreConsumer) {
      GuiTextLibrary.LoreBuilder lore = GuiTextLibrary.lore();
      if (loreConsumer != null) {
         loreConsumer.accept(lore);
      }

      return GuiItems.named(material, GuiTextLibrary.title(accent, title), lore.build());
   }
}
