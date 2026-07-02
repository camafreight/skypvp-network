package network.skypvp.proxy.service;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.OptionalInt;

public class Fabric8KubernetesApiService implements KubernetesApiService {

    private final Logger logger;
    private final String namespace;
    private final KubernetesClient client;
    private final boolean available;

    public Fabric8KubernetesApiService(Logger logger) {
        this.logger = logger;
        this.namespace = "skypvp-network"; // Fallback/Default

        KubernetesClient builtClient = null;
        boolean isAvailable = false;
        try {
            builtClient = new KubernetesClientBuilder().build();
            isAvailable = true;
        } catch (Exception e) {
            logger.warn("Kubernetes API is not available (not running in cluster or missing credentials): {}", e.getMessage());
        }

        this.client = builtClient;
        this.available = isAvailable;
    }

    @Override
    public boolean available() {
        return this.available;
    }

    @Override
    public String namespace() {
        return this.namespace;
    }

    @Override
    public KubernetesResourceStatus getResourceStatus(String resourceName, ResourceKind kind) throws Exception {
        if (!available) throw new IllegalStateException("Kubernetes API unavailable");

        if (kind == ResourceKind.DEPLOYMENT) {
            Deployment deployment = client.apps().deployments()
                    .inNamespace(namespace)
                    .withName(resourceName)
                    .get();

            if (deployment == null) throw new IllegalArgumentException("Deployment not found: " + resourceName);

            int specReplicas = deployment.getSpec().getReplicas() != null ? deployment.getSpec().getReplicas() : 0;
            int readyReplicas = deployment.getStatus().getReadyReplicas() != null ? deployment.getStatus().getReadyReplicas() : 0;
            int availableReplicas = deployment.getStatus().getAvailableReplicas() != null ? deployment.getStatus().getAvailableReplicas() : 0;

            return new KubernetesResourceStatus(specReplicas, readyReplicas, availableReplicas);

        } else {
            StatefulSet statefulSet = client.apps().statefulSets()
                    .inNamespace(namespace)
                    .withName(resourceName)
                    .get();

            if (statefulSet == null) throw new IllegalArgumentException("StatefulSet not found: " + resourceName);

            int specReplicas = statefulSet.getSpec().getReplicas() != null ? statefulSet.getSpec().getReplicas() : 0;
            int readyReplicas = statefulSet.getStatus().getReadyReplicas() != null ? statefulSet.getStatus().getReadyReplicas() : 0;
            int availableReplicas = statefulSet.getStatus().getAvailableReplicas() != null ? statefulSet.getStatus().getAvailableReplicas() : 0;

            return new KubernetesResourceStatus(specReplicas, readyReplicas, availableReplicas);
        }
    }

    @Override
    public void restartResource(String resourceName, ResourceKind kind) throws Exception {
        if (!available) throw new IllegalStateException("Kubernetes API unavailable");

        String restartedAt = Instant.now().toString();

        if (kind == ResourceKind.DEPLOYMENT) {
            client.apps().deployments()
                    .inNamespace(namespace)
                    .withName(resourceName)
                    .rolling().restart();
        } else {
            client.apps().statefulSets()
                    .inNamespace(namespace)
                    .withName(resourceName)
                    .rolling().restart();
        }
    }

    @Override
    public void scaleResource(String resourceName, ResourceKind kind, int replicas) throws Exception {
        if (!available) throw new IllegalStateException("Kubernetes API unavailable");

        if (kind == ResourceKind.DEPLOYMENT) {
            client.apps().deployments()
                    .inNamespace(namespace)
                    .withName(resourceName)
                    .scale(replicas);
        } else {
            client.apps().statefulSets()
                    .inNamespace(namespace)
                    .withName(resourceName)
                    .scale(replicas);
        }
    }

    @Override
    public OptionalInt getConfigMapInt(String configMapName, String key) throws Exception {
        if (!available) return OptionalInt.empty();

        ConfigMap configMap = client.configMaps()
                .inNamespace(namespace)
                .withName(configMapName)
                .get();

        if (configMap != null && configMap.getData() != null) {
            String value = configMap.getData().get(key);
            if (value != null && !value.isBlank()) {
                try {
                    return OptionalInt.of(Integer.parseInt(value.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public void patchConfigMapValue(String configMapName, String key, String value) throws Exception {
        if (!available) throw new IllegalStateException("Kubernetes API unavailable");

        client.configMaps()
                .inNamespace(namespace)
                .withName(configMapName)
                .edit(cm -> {
                    if (cm.getData() != null) {
                        cm.getData().put(key, value);
                    }
                    return cm;
                });
    }
    
    public KubernetesClient getRawClient() {
        return this.client;
    }
}
