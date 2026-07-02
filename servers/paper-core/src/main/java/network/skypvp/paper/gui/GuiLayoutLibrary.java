package network.skypvp.paper.gui;

import java.util.List;
import java.util.stream.IntStream;

public final class GuiLayoutLibrary {
   private GuiLayoutLibrary() {
   }

   public static final class Auction45 {
      public static final int HEADER_SLOT = 4;
      public static final List<Integer> CHROME_SLOTS = IntStream.range(0, 9).boxed().toList();
      public static final List<Integer> PAGE_SLOTS = IntStream.range(9, 36).boxed().toList();
      public static final int PREVIOUS_SLOT = 36;
      public static final int SORT_SLOT = 37;
      public static final int FILTER_SLOT = 38;
      public static final int WALLET_SLOT = 39;
      public static final int SEARCH_SLOT = 40;
      public static final int PRIMARY_ACTION_SLOT = 41;
      public static final int CLAIMS_SLOT = 42;
      public static final int BACK_SLOT = 43;
      public static final int NEXT_SLOT = 44;

      private Auction45() {
      }
   }

   public static final class Browser36 {
      public static final int HEADER_SLOT = 4;
      public static final List<Integer> PAGE_SLOTS = List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25);
      public static final int PREVIOUS_SLOT = 27;
      public static final int FOOTER_LEFT = 30;
      public static final int FOOTER_CENTER = 31;
      public static final int BACK_SLOT = 32;
      public static final int NEXT_SLOT = 35;

      private Browser36() {
      }
   }

   public static final class Browser54 {
      public static final List<Integer> PAGE_SLOTS = IntStream.range(0, 45).boxed().toList();
      public static final int PREVIOUS_SLOT = 45;
      public static final int HOME_SLOT = 49;
      public static final int NEXT_SLOT = 53;

      private Browser54() {
      }
   }

   public static final class Browser54Spacious {
      public static final int HEADER_SLOT = 4;
      public static final List<Integer> PAGE_SLOTS = List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34);
      public static final int PREVIOUS_SLOT = 45;
      public static final int FOOTER_LEFT = 48;
      public static final int FOOTER_CENTER = 49;
      public static final int BACK_SLOT = 50;
      public static final int NEXT_SLOT = 53;

      private Browser54Spacious() {
      }
   }

   public static final class Dashboard27 {
      public static final List<Integer> PRIMARY_SLOTS = List.of(10, 11, 13, 15, 16);
      public static final int FOOTER_SLOT = 22;

      private Dashboard27() {
      }
   }

   public static final class Dashboard36 {
      public static final List<Integer> PRIMARY_SLOTS = List.of(10, 12, 14, 16, 20, 22, 24);
      public static final int FOOTER_LEFT = 30;
      public static final int FOOTER_CENTER = 31;
      public static final int FOOTER_RIGHT = 32;

      private Dashboard36() {
      }
   }

   public static final class Decision27 {
      public static final int PRIMARY_SLOT = 11;
      public static final int FOCUS_SLOT = 13;
      public static final int ESCAPE_SLOT = 15;

      private Decision27() {
      }
   }

   public static final class Panel27 {
      public static final int HEADER_SLOT = 4;
      public static final List<Integer> ACTION_SLOTS = List.of(10, 12, 14, 16);
      public static final int BACK_SLOT = 22;
      public static final int AUX_SLOT = 24;

      private Panel27() {
      }
   }

   public static final class QuickSelect9 {
      public static final List<Integer> ACTION_SLOTS = List.of(1, 3, 5, 7);

      private QuickSelect9() {
      }
   }
}
