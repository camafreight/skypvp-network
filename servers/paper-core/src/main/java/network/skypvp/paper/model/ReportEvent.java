package network.skypvp.paper.model;

public record ReportEvent(String reporterName, String targetName, String reason, String serverId) {
}
