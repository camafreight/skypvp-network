package network.skypvp.paper.item.api;

import java.util.Locale;
import java.util.Objects;

/**
 * Globally unique custom item type identifier ({@code mode/path}).
 */
public record CustomItemTypeId(String namespace, String path) {

    public CustomItemTypeId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (namespace.isBlank() || path.isBlank()) {
            throw new IllegalArgumentException("Custom item type id requires non-blank namespace and path.");
        }
    }

    public static CustomItemTypeId parse(String uid) {
        Objects.requireNonNull(uid, "uid");
        int slash = uid.indexOf('/');
        if (slash <= 0 || slash >= uid.length() - 1) {
            throw new IllegalArgumentException("Invalid custom item uid: " + uid);
        }
        return new CustomItemTypeId(uid.substring(0, slash), uid.substring(slash + 1));
    }

    public String uid() {
        return namespace + "/" + path;
    }

    @Override
    public String toString() {
        return uid();
    }
}
