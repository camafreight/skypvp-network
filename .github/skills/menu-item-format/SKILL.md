---
name: menu-item-format
description: "Create or audit SkyPvP GUI item titles, lore, button families, and inventory layouts when building menus, shop items, auction listings, confirm screens, or status buttons. Use this when you need MiniMessage-ready item text and slot choreography that follow the network standard with restrained colors, bullet lore, bottom action hints, and stable navigation slots."
---

# SkyPvP Menu Item Format Skill

Use this skill when generating or reviewing GUI item text for SkyPvP.

## Workflow

1. Identify the surface type: decision screen, dashboard, browser, or large browser.
2. Choose the matching slot preset from `GuiLayoutLibrary` before placing items.
3. Identify each item's primary purpose.
4. Decide whether it is actionable, informational, locked, destructive, navigation, or footer utility.
5. Write one compact title line.
6. Write `1` to `3` muted bullet lines using the `  • ` prefix, soft-wrapping around `32` visible characters.
7. Add one spacer line between distinct sections or before the bottom action footer.
8. Add a bottom action footer such as `Click to open`, `Click to buy`, or `You do not have permission`.
9. Keep the total color palette small and consistent with `docs/menu-item-format-standard.md`.

## Output Rules

- Prefer MiniMessage-compatible strings.
- Use white and gray as the default body palette.
- Use one accent family for the focal value or action.
- Use bold for the title, section headers, and only the most important repeated keyword or value.
- Do not overload the item with multiple bold lines.
- If the item already has enough context in the title, keep the lore shorter.
- Prefer `GuiButtonLibrary` for shared button semantics and `GuiLayoutLibrary` for slot placement instead of inventing new positions per menu.
- Keep `Back` or `Close` in the preset escape slot when the chosen layout defines one.

## Review Checklist

- Does the surface use the correct layout preset?
- Are previous and next navigation on the outer edges?
- Is back or close in the preset escape slot?
- Is the action obvious at a glance?
- Are only important values highlighted?
- Does every lore line earn its space?
- Is the footer separated clearly from the information block?
- Are decorative or filler items clearly separate from clickable actions?
- Would this formatting and layout feel consistent beside SkyPvP chat and scoreboard lines?
