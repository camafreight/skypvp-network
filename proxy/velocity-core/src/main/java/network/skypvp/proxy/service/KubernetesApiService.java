package network.skypvp.proxy.service;

import java.util.OptionalInt;

public interface KubernetesApiService {

    boolean available();

    String namespace();

    KubernetesResourceStatus getResourceStatus(String resourceName, ResourceKind kind) throws Exception;

    void restartResource(String resourceName, ResourceKind kind) throws Exception;

    void scaleResource(String resourceName, ResourceKind kind, int replicas) throws Exception;

    OptionalInt getConfigMapInt(String configMapName, String key) throws Exception;

    void patchConfigMapValue(String configMapName, String key, String value) throws Exception;

    enum ResourceKind {
        DEPLOYMENT,
        STATEFULSET
    }

    record KubernetesResourceStatus(int specReplicas, int readyReplicas, int availableReplicas) {}
}
