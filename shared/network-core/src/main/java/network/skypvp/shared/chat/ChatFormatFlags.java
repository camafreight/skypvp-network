package network.skypvp.shared.chat;

import java.util.Objects;

/**
 * Declarative chat format fields configured via {@code /chat formats} and system format commands.
 */
public record ChatFormatFlags(
        int priority,
        String prefix,
        String nameColor,
        String name,
        String suffix,
        String chatColor,
        String channelTooltip,
        String prefixTooltip,
        String nameTooltip,
        String suffixTooltip,
        String prefixClickCommand,
        String nameClickCommand,
        String suffixClickCommand
) {
    public static final ChatFormatFlags EMPTY = new ChatFormatFlags(
            0,
            "",
            "white",
            "%player_name%",
            "",
            "white",
            "",
            "",
            "",
            "",
            "",
            "",
            ""
    );

    public ChatFormatFlags {
        prefix = nullToEmpty(prefix);
        nameColor = nullToEmpty(nameColor);
        name = nullToEmpty(name);
        suffix = nullToEmpty(suffix);
        chatColor = nullToEmpty(chatColor);
        channelTooltip = nullToEmpty(channelTooltip);
        prefixTooltip = nullToEmpty(prefixTooltip);
        nameTooltip = nullToEmpty(nameTooltip);
        suffixTooltip = nullToEmpty(suffixTooltip);
        prefixClickCommand = nullToEmpty(prefixClickCommand);
        nameClickCommand = nullToEmpty(nameClickCommand);
        suffixClickCommand = nullToEmpty(suffixClickCommand);
    }

    public ChatFormatFlags merge(ChatFormatFlags override) {
        if (override == null) {
            return this;
        }
        return new ChatFormatFlags(
                override.priority != 0 ? override.priority : this.priority,
                pick(override.prefix, this.prefix),
                pick(override.nameColor, this.nameColor),
                pick(override.name, this.name),
                pick(override.suffix, this.suffix),
                pick(override.chatColor, this.chatColor),
                pick(override.channelTooltip, this.channelTooltip),
                pick(override.prefixTooltip, this.prefixTooltip),
                pick(override.nameTooltip, this.nameTooltip),
                pick(override.suffixTooltip, this.suffixTooltip),
                pick(override.prefixClickCommand, this.prefixClickCommand),
                pick(override.nameClickCommand, this.nameClickCommand),
                pick(override.suffixClickCommand, this.suffixClickCommand)
        );
    }

    private static String pick(String candidate, String fallback) {
        return candidate == null || candidate.isEmpty() ? fallback : candidate;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ChatFormatFlags flags)) {
            return false;
        }
        return this.priority == flags.priority
                && Objects.equals(this.prefix, flags.prefix)
                && Objects.equals(this.nameColor, flags.nameColor)
                && Objects.equals(this.name, flags.name)
                && Objects.equals(this.suffix, flags.suffix)
                && Objects.equals(this.chatColor, flags.chatColor)
                && Objects.equals(this.channelTooltip, flags.channelTooltip)
                && Objects.equals(this.prefixTooltip, flags.prefixTooltip)
                && Objects.equals(this.nameTooltip, flags.nameTooltip)
                && Objects.equals(this.suffixTooltip, flags.suffixTooltip)
                && Objects.equals(this.prefixClickCommand, flags.prefixClickCommand)
                && Objects.equals(this.nameClickCommand, flags.nameClickCommand)
                && Objects.equals(this.suffixClickCommand, flags.suffixClickCommand);
    }
}
