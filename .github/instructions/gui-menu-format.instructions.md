---
applyTo: "servers/**/*.java"
description: "Use when creating or editing GUI items, menu titles, inventory lore, shop buttons, auction listings, click hints, or inventory layouts. Enforce the network menu item and layout standard: clear names, restrained color count, two-space bullet lore, standard button families, and reserved control-row slots."
---

# GUI Menu Format

When you create or modify menu items, menu titles, inventory lore, shop buttons, auction listings, click hints, or inventory layouts, follow these guidelines to maintain a consistent and user-friendly interface across the network:

- Follow `docs/menu-item-format-standard.md` for both item text and slot layout.
- Keep each item focused on one action or one state.
- Use mostly neutral text and reserve accent color for the key noun, price, or action.
- Do not exceed `3` to `4` distinct colors in a single item unless the menu is an intentional hero surface.
- Soft-wrap lore around `32` visible characters and split early for bold or wide-value lines.
- For multi-line lore, prefix each informational line with `  • `.
- Use one spacer line between sections and before the bottom action block; never stack multiple empty lines.
- Put click or permission hints at the bottom of the lore, ideally after a spacer line.
- Use bold for item titles, section headers, and only the most important repeated keyword or value.
- Keep item format consistent with chat, scoreboard, tab list, and hologram wording.
- Prefer shared builders in `paper-core`, especially `GuiTextLibrary`, `GuiButtonLibrary`, and `GuiLayoutLibrary`, over hand-written per-menu formatting.
- Use the standard `27`-slot decision, `36`-slot dashboard, `36`-slot browser, and `54`-slot browser layouts unless the menu has a justified exception.
- Keep paged navigation on the outer left and right, and keep the dedicated back or close action in the preset escape slot.
- Keep decorative chrome separate from clickable items. Filler panes should frame the menu, not compete with the actions.
