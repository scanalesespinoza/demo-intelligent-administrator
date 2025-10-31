package com.iadmin.config;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class KubernetesClientProducer {

    private final KubernetesClient client = new DefaultKubernetesClient();

    @Produces
    @ApplicationScoped
    public KubernetesClient kubernetesClient() {
        return client;
    }

    @PreDestroy
    void close() {
        client.close();
    }
}
