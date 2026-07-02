package network.skypvp.proxy.service;

import org.slf4j.Logger;

import java.util.List;

public class KubernetesOrchestratorService {

    private final Logger logger;
    private final KubernetesApiService kubeClient;

    public KubernetesOrchestratorService(Logger logger, KubernetesApiService kubeClient) {
        this.logger = logger;
        this.kubeClient = kubeClient;
    }

    public void wakeUpCoreBackends() {
        if (!kubeClient.available()) {
            logger.warn("Kubernetes Orchestrator skipping sweep: API is unavailable.");
            return;
        }

        try {
            logger.info("Kubernetes Orchestrator checking core backend states in namespace '{}'...", kubeClient.namespace());

            // Deployments
            List<String> expectedDeployments = List.of();
            for (String deploymentName : expectedDeployments) {
                try {
                    KubernetesApiService.KubernetesResourceStatus status = kubeClient.getResourceStatus(deploymentName, KubernetesApiService.ResourceKind.DEPLOYMENT);
                    if (status.specReplicas() == 0) {
                        logger.info("Deployment '{}' is scaled to 0. Waking it up to 1 replica...", deploymentName);
                        kubeClient.scaleResource(deploymentName, KubernetesApiService.ResourceKind.DEPLOYMENT, 1);
                    } else {
                        logger.info("Deployment '{}' is already running with {} replica(s).", deploymentName, status.specReplicas());
                    }
                } catch (IllegalArgumentException e) {
                    logger.warn("Deployment '{}' not found.", deploymentName);
                }
            }

            // StatefulSets
            List<String> expectedStatefulSets = List.of("skypvp-lobby", "skypvp-extraction");
            for (String statefulSetName : expectedStatefulSets) {
                try {
                    KubernetesApiService.KubernetesResourceStatus status = kubeClient.getResourceStatus(statefulSetName, KubernetesApiService.ResourceKind.STATEFULSET);
                    if (status.specReplicas() == 0) {
                        logger.info("StatefulSet '{}' is scaled to 0. Waking it up to 1 replica...", statefulSetName);
                        kubeClient.scaleResource(statefulSetName, KubernetesApiService.ResourceKind.STATEFULSET, 1);
                    } else {
                        logger.info("StatefulSet '{}' is already running with {} replica(s).", statefulSetName, status.specReplicas());
                    }
                } catch (IllegalArgumentException e) {
                    logger.warn("StatefulSet '{}' not found.", statefulSetName);
                }
            }

            logger.info("Kubernetes Orchestrator backend sweep complete.");

        } catch (Exception e) {
            logger.error("Failed to execute Kubernetes Orchestrator sweep: {}", e.getMessage(), e);
        }
    }
}
