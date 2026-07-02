package network.skypvp.shared;

public record NetworkServerDescriptor(
        String serverId,
        NetworkServerRole role,
        String cluster,
        String address,
        int capacity,
        boolean joinable
) {
}
