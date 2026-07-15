package network.skypvp.proxy.service;

import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KubernetesAutoscalerService implements Runnable {

    private static final double SCALE_UP_THRESHOLD = 0.8;
    private static final double SCALE_DOWN_THRESHOLD = 0.4;

    private final Logger logger;
    private final KubernetesApiService kubeClient;
    private final ServerRoutingService routingService;

    // Map of role -> (deployment name)
    private static final Map<String, String> DYNAMIC_ROLES = Map.of(
            "LOBBY", "skypvp-lobby",
            "EXTRACTION", "skypvp-extraction"
    );

    public KubernetesAutoscalerService(Logger logger, KubernetesApiService kubeClient, ServerRoutingService routingService) {
        this.logger = logger;
        this.kubeClient = kubeClient;
        this.routingService = routingService;
    }

    @Override
    public void run() {
        if (!kubeClient.available()) {
            return;
        }

        try {
            List<ServerRoutingService.ServerRouteStatus> statuses = routingService.snapshotStatuses();

            Map<String, RoleCapacity> roleCapacities = new HashMap<>();

            for (ServerRoutingService.ServerRouteStatus status : statuses) {
                if (status.role() == null) continue;
                String role = status.role().toUpperCase();

                if (DYNAMIC_ROLES.containsKey(role) && status.isHealthyJoinTarget()) {
                    roleCapacities.putIfAbsent(role, new RoleCapacity());
                    RoleCapacity rc = roleCapacities.get(role);
                    rc.onlinePlayers += status.onlinePlayers();
                    rc.softCapacity += (status.softCapacity() > 0 ? status.softCapacity() : status.maxPlayers());
                }
            }

            for (Map.Entry<String, RoleCapacity> entry : roleCapacities.entrySet()) {
                String role = entry.getKey();
                RoleCapacity rc = entry.getValue();
                
                if (rc.softCapacity == 0) continue;

                double loadRatio = (double) rc.onlinePlayers / rc.softCapacity;
                String deploymentName = DYNAMIC_ROLES.get(role);

                // Breach saturation: every session unjoinable AND no pod has instance
                // headroom left. Player-load ratio never catches this (instance caps
                // saturate long before player caps), so queued players would wait forever.
                ServerRoutingService.BreachSaturation saturation = "EXTRACTION".equals(role)
                        ? routingService.breachSaturation()
                        : null;
                boolean breachSaturated = saturation != null && saturation.saturated();

                try {
                    KubernetesApiService.KubernetesResourceStatus resStatus = kubeClient.getResourceStatus(deploymentName, KubernetesApiService.ResourceKind.STATEFULSET);
                    int currentReplicas = resStatus.specReplicas();
                    int readyReplicas = resStatus.readyReplicas();

                    if (currentReplicas == 0) continue;

                    // Kubernetes pods are 0-indexed (e.g. skypvp-minigame-2 for 3rd replica)
                    // But Velocity server IDs are 1-indexed (e.g. minigame-3 for 3rd replica)
                    String velocityServerId = role.toLowerCase() + "-" + currentReplicas;

                    if (loadRatio >= SCALE_UP_THRESHOLD || breachSaturated) {
                        if (routingService.isServerDraining(velocityServerId)) {
                            logger.info("[Autoscaler] Load increased for role '{}' ({} / {} = {}). Canceling graceful drain for '{}'.",
                                    role, rc.onlinePlayers, rc.softCapacity, String.format("%.2f", loadRatio), velocityServerId);
                            routingService.unmarkServerAsDraining(velocityServerId);
                        }

                        if (readyReplicas >= currentReplicas) {
                            int nextReplicas = currentReplicas + 1;
                            if (breachSaturated) {
                                logger.info("[Autoscaler] Breach capacity saturated (joinable=0, headroom=0, queued={}). Scaling K8s StatefulSet '{}' from {} to {} replicas.",
                                        saturation.queuedPlayers(), deploymentName, currentReplicas, nextReplicas);
                            } else {
                                logger.info("[Autoscaler] Role '{}' has high load ({} / {} = {}). Scaling K8s StatefulSet '{}' from {} to {} replicas.",
                                        role, rc.onlinePlayers, rc.softCapacity, String.format("%.2f", loadRatio), deploymentName, currentReplicas, nextReplicas);
                            }
                            kubeClient.scaleResource(deploymentName, KubernetesApiService.ResourceKind.STATEFULSET, nextReplicas);
                        } else {
                            logger.debug("[Autoscaler] Role '{}' needs scale-up but Kubernetes is still provisioning or out of resources ({} / {} ready). Waiting.",
                                    role, readyReplicas, currentReplicas);
                        }
                    } else if (loadRatio <= SCALE_DOWN_THRESHOLD) {
                        if (currentReplicas > 1) {
                            if (routingService.isServerDraining(velocityServerId)) {
                                java.util.Optional<ServerRoutingService.ServerRouteStatus> drainingStatus = statuses.stream().filter(s -> velocityServerId.equals(s.serverId())).findFirst();
                                if (drainingStatus.isPresent() && drainingStatus.get().onlinePlayers() == 0) {
                                    int nextReplicas = currentReplicas - 1;
                                    logger.info("[Autoscaler] Target server '{}' is empty. Safely scaling down K8s StatefulSet '{}' from {} to {} replicas.",
                                            velocityServerId, deploymentName, currentReplicas, nextReplicas);
                                    kubeClient.scaleResource(deploymentName, KubernetesApiService.ResourceKind.STATEFULSET, nextReplicas);
                                    routingService.unmarkServerAsDraining(velocityServerId);
                                } else if (!drainingStatus.isPresent()) {
                                    logger.debug("[Autoscaler] Target server '{}' is draining but not registered in routing. Waiting.", velocityServerId);
                                } else {
                                    logger.debug("[Autoscaler] Target server '{}' is draining but still has players. Waiting.", velocityServerId);
                                }
                            } else {
                                logger.info("[Autoscaler] Role '{}' has low load ({} / {} = {}). Marking '{}' for graceful drain.",
                                        role, rc.onlinePlayers, rc.softCapacity, String.format("%.2f", loadRatio), velocityServerId);
                                routingService.markServerAsDraining(velocityServerId);
                            }
                        }
                    } else {
                        if (routingService.isServerDraining(velocityServerId)) {
                            logger.info("[Autoscaler] Load normalized for role '{}' ({} / {} = {}). Canceling graceful drain for '{}'.",
                                    role, rc.onlinePlayers, rc.softCapacity, String.format("%.2f", loadRatio), velocityServerId);
                            routingService.unmarkServerAsDraining(velocityServerId);
                        }
                    }
                } catch (Exception e) {
                    logger.error("[Autoscaler] Failed to check or scale StatefulSet '{}': {}", deploymentName, e.getMessage());
                }
            }

        } catch (Exception e) {
            logger.error("[Autoscaler] Sweep failed: {}", e.getMessage());
        }
    }

    private static class RoleCapacity {
        int onlinePlayers = 0;
        int softCapacity = 0;
    }
}
