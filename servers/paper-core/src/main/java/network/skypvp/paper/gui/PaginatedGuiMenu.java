package network.skypvp.paper.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.library.NetworkSoundCue;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class PaginatedGuiMenu<T> implements GuiMenu, AnimatedGuiMenu {
   private final Component title;
   private final int size;
   private final List<Integer> pageSlots;
   private final Function<Player, List<T>> entriesProvider;
   private final BiFunction<Player, T, ItemStack> itemRenderer;
   private final PaginatedGuiMenu.PageItemClickHandler<T> itemClickHandler;
   private final Map<Integer, PaginatedGuiMenu.StaticButton> staticButtons;
   private final int previousPageSlot;
   private final BiFunction<Integer, Integer, ItemStack> previousPageItem;
   private final int nextPageSlot;
   private final BiFunction<Integer, Integer, ItemStack> nextPageItem;
   private final int initialPage;
   private final boolean liveRefresh;
   private int currentPage;

   private PaginatedGuiMenu(PaginatedGuiMenu.Builder<T> builder) {
      this.title = builder.title;
      this.size = builder.size;
      this.pageSlots = List.copyOf(builder.pageSlots);
      this.entriesProvider = builder.entriesProvider;
      this.itemRenderer = builder.itemRenderer;
      this.itemClickHandler = builder.itemClickHandler;
      this.staticButtons = Map.copyOf(builder.staticButtons);
      this.previousPageSlot = builder.previousPageSlot;
      this.previousPageItem = builder.previousPageItem;
      this.nextPageSlot = builder.nextPageSlot;
      this.nextPageItem = builder.nextPageItem;
      this.initialPage = Math.max(0, builder.initialPage);
      this.liveRefresh = builder.liveRefresh;
      this.currentPage = this.initialPage;
   }

   public static <T> PaginatedGuiMenu.Builder<T> create(Component title, int size) {
      return new PaginatedGuiMenu.Builder<>(title, size);
   }

   @Override
   public Component title() {
      return this.title;
   }

   @Override
   public int size() {
      return this.size;
   }

   @Override
   public void onPreOpen(Player viewer, Inventory inventory) {
      this.currentPage = this.initialPage;
   }

   @Override
   public void render(Player viewer, Inventory inventory) {
      inventory.clear();
      List<T> entries = this.safeEntries(viewer);
      int totalPages = this.totalPages(entries);
      this.currentPage = sanitizePage(this.currentPage, totalPages);

      for (Entry<Integer, PaginatedGuiMenu.StaticButton> entry : this.staticButtons.entrySet()) {
         ItemStack item = entry.getValue().itemFactory.apply(viewer);
         if (item != null) {
            inventory.setItem(entry.getKey(), item.clone());
         }
      }

      this.renderPageItems(viewer, inventory);

      if (this.previousPageSlot >= 0 && this.currentPage > 0) {
         inventory.setItem(this.previousPageSlot, this.previousPageItem.apply(this.currentPage, totalPages).clone());
      }

      if (this.nextPageSlot >= 0 && this.currentPage < totalPages - 1) {
         inventory.setItem(this.nextPageSlot, this.nextPageItem.apply(this.currentPage, totalPages).clone());
      }
   }

   @Override
   public void onClick(GuiClickContext context) {
      List<T> entries = this.safeEntries(context.viewer());
      int totalPages = this.totalPages(entries);
      this.currentPage = sanitizePage(this.currentPage, totalPages);
      if (context.rawSlot() == this.previousPageSlot && this.currentPage > 0) {
         this.currentPage--;
         context.playSound(NetworkSoundCue.UI_PAGE_TURN);
         context.refresh();
      } else if (context.rawSlot() == this.nextPageSlot && this.currentPage < totalPages - 1) {
         this.currentPage++;
         context.playSound(NetworkSoundCue.UI_PAGE_TURN);
         context.refresh();
      } else {
         PaginatedGuiMenu.StaticButton staticButton = this.staticButtons.get(context.rawSlot());
         if (staticButton != null) {
            staticButton.handler.accept(context);
         } else {
            int slotIndex = this.pageSlots.indexOf(context.rawSlot());
            if (slotIndex >= 0) {
               int entryIndex = this.currentPage * this.pageSlots.size() + slotIndex;
               if (entryIndex >= 0 && entryIndex < entries.size()) {
                  this.itemClickHandler.accept(context, entries.get(entryIndex));
               }
            }
         }
      }
   }

   @Override
   public long clickDebounceMillis() {
      return 90L;
   }

   @Override
   public GuiAnimation backgroundAnimation() {
      return null;
   }

   @Override
   public boolean hasAnimatedButtons() {
      return this.liveRefresh;
   }

   @Override
   public void renderAnimatedFrame(Player viewer, Inventory inventory, int frameIndex) {
   }

   @Override
   public void renderAnimatedButtons(Player viewer, Inventory inventory, long tickMillis) {
      if (!this.liveRefresh) {
         return;
      }
      for (Entry<Integer, PaginatedGuiMenu.StaticButton> entry : this.staticButtons.entrySet()) {
         ItemStack item = entry.getValue().itemFactory.apply(viewer);
         if (item != null) {
            inventory.setItem(entry.getKey(), item.clone());
         }
      }
      this.renderPageItems(viewer, inventory);
   }

   private void renderPageItems(Player viewer, Inventory inventory) {
      List<T> entries = this.safeEntries(viewer);
      int totalPages = this.totalPages(entries);
      this.currentPage = sanitizePage(this.currentPage, totalPages);
      int start = this.currentPage * this.pageSlots.size();
      int end = Math.min(start + this.pageSlots.size(), entries.size());
      for (int index = start; index < end; index++) {
         int pageIndex = index - start;
         ItemStack item = this.itemRenderer.apply(viewer, entries.get(index));
         if (item != null) {
            inventory.setItem(this.pageSlots.get(pageIndex), item.clone());
         }
      }
   }

   private List<T> safeEntries(Player viewer) {
      List<T> entries = this.entriesProvider.apply(viewer);
      return entries == null ? List.of() : entries;
   }

   private int totalPages(List<T> entries) {
      return Math.max(1, (int)Math.ceil((double)entries.size() / (double)this.pageSlots.size()));
   }

   private static int sanitizePage(int page, int totalPages) {
      return Math.max(0, Math.min(page, totalPages - 1));
   }

   public static final class Builder<T> {
      private final Component title;
      private final int size;
      private final List<Integer> pageSlots = new ArrayList<>();
      private final Map<Integer, PaginatedGuiMenu.StaticButton> staticButtons = new HashMap<>();
      private Function<Player, List<T>> entriesProvider = viewer -> List.of();
      private BiFunction<Player, T, ItemStack> itemRenderer;
      private PaginatedGuiMenu.PageItemClickHandler<T> itemClickHandler = (context, item) -> {
      };
      private int previousPageSlot = -1;
      private BiFunction<Integer, Integer, ItemStack> previousPageItem;
      private int nextPageSlot = -1;
      private BiFunction<Integer, Integer, ItemStack> nextPageItem;
      private int initialPage;
      private boolean liveRefresh;

      private Builder(Component title, int size) {
         this.title = title;
         this.size = size;
      }

      public PaginatedGuiMenu.Builder<T> pageSlots(List<Integer> slots) {
         this.pageSlots.clear();
         this.pageSlots.addAll(slots);
         return this;
      }

      public PaginatedGuiMenu.Builder<T> entries(Function<Player, List<T>> provider) {
         this.entriesProvider = provider;
         return this;
      }

      public PaginatedGuiMenu.Builder<T> renderItem(BiFunction<Player, T, ItemStack> renderer) {
         this.itemRenderer = renderer;
         return this;
      }

      public PaginatedGuiMenu.Builder<T> onItemClick(PaginatedGuiMenu.PageItemClickHandler<T> handler) {
         this.itemClickHandler = handler;
         return this;
      }

      public PaginatedGuiMenu.Builder<T> button(int slot, Function<Player, ItemStack> itemFactory, Consumer<GuiClickContext> handler) {
         this.staticButtons.put(slot, new PaginatedGuiMenu.StaticButton(itemFactory, handler));
         return this;
      }

      public PaginatedGuiMenu.Builder<T> previousButton(int slot, BiFunction<Integer, Integer, ItemStack> itemFactory) {
         this.previousPageSlot = slot;
         this.previousPageItem = itemFactory;
         return this;
      }

      public PaginatedGuiMenu.Builder<T> nextButton(int slot, BiFunction<Integer, Integer, ItemStack> itemFactory) {
         this.nextPageSlot = slot;
         this.nextPageItem = itemFactory;
         return this;
      }

      public PaginatedGuiMenu.Builder<T> initialPage(int page) {
         this.initialPage = page;
         return this;
      }

      public PaginatedGuiMenu.Builder<T> liveRefresh(boolean enabled) {
         this.liveRefresh = enabled;
         return this;
      }

      public PaginatedGuiMenu<T> build() {
         if (this.pageSlots.isEmpty()) {
            throw new IllegalStateException("Paginated menus require at least one page slot");
         } else if (this.itemRenderer == null) {
            throw new IllegalStateException("Paginated menus require an item renderer");
         } else if (this.previousPageSlot >= 0 && this.previousPageItem == null) {
            throw new IllegalStateException("Previous page button is missing an item renderer");
         } else if (this.nextPageSlot >= 0 && this.nextPageItem == null) {
            throw new IllegalStateException("Next page button is missing an item renderer");
         } else {
            return new PaginatedGuiMenu<>(this);
         }
      }
   }

   @FunctionalInterface
   public interface PageItemClickHandler<T> {
      void accept(GuiClickContext var1, T var2);
   }

   private static record StaticButton(Function<Player, ItemStack> itemFactory, Consumer<GuiClickContext> handler) {
   }
}
