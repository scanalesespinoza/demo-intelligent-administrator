package com.iadmin.config.impl;

import com.iadmin.config.ConfigConnector;
import com.iadmin.model.ConfigSnapshot;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class DefaultConfigConnector implements ConfigConnector {

    private final KubernetesClient client;

    @Inject
    public DefaultConfigConnector(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public ConfigSnapshot getDeploymentConfig(String namespace, String deploymentName) {
        if (namespace == null || namespace.isBlank()) {
            return new ConfigSnapshot(Map.of(), List.of(), Map.of());
        }
        if (deploymentName == null || deploymentName.isBlank()) {
            return new ConfigSnapshot(Map.of(), List.of(), Map.of());
        }
        Deployment deployment = client.apps().deployments().inNamespace(namespace).withName(deploymentName).get();
        if (deployment == null) {
            return new ConfigSnapshot(Map.of(), List.of(), Map.of());
        }
        List<Container> containers = Optional.ofNullable(deployment.getSpec())
                .map(spec -> spec.getTemplate())
                .map(template -> template.getSpec())
                .map(podSpec -> podSpec.getContainers())
                .orElse(List.of());

        Map<String, String> env = new LinkedHashMap<>();
        Set<String> configRefs = new LinkedHashSet<>();

        for (Container container : containers) {
            String prefix = container.getName() + ".";
            List<EnvVar> envVars = Optional.ofNullable(container.getEnv()).orElse(List.of());
            for (EnvVar envVar : envVars) {
                String key = prefix + envVar.getName();
                String value = resolveEnvValue(envVar, configRefs);
                env.put(key, value);
            }

            List<EnvFromSource> envFromSources = Optional.ofNullable(container.getEnvFrom()).orElse(List.of());
            for (EnvFromSource envFrom : envFromSources) {
                if (envFrom.getConfigMapRef() != null && envFrom.getConfigMapRef().getName() != null) {
                    configRefs.add(envFrom.getConfigMapRef().getName());
                }
            }
        }

        Map<String, String> configMapData = new LinkedHashMap<>();
        for (String configMapName : configRefs) {
            ConfigMap configMap = client.configMaps().inNamespace(namespace).withName(configMapName).get();
            if (configMap != null && configMap.getData() != null) {
                configMap.getData().forEach((key, value) -> configMapData.put(configMapName + ":" + key, value));
            }
        }

        return new ConfigSnapshot(Map.copyOf(env), List.copyOf(configRefs), Map.copyOf(configMapData));
    }

    private String resolveEnvValue(EnvVar envVar, Set<String> configRefs) {
        if (envVar.getValue() != null) {
            return envVar.getValue();
        }
        EnvVarSource source = envVar.getValueFrom();
        if (source == null) {
            return "";
        }
        if (source.getConfigMapKeyRef() != null) {
            var ref = source.getConfigMapKeyRef();
            if (ref.getName() != null) {
                configRefs.add(ref.getName());
            }
            return "configMap:" + Optional.ofNullable(ref.getName()).orElse("?") + ":" + Optional.ofNullable(ref.getKey()).orElse("?");
        }
        if (source.getSecretKeyRef() != null) {
            var ref = source.getSecretKeyRef();
            return "secret:" + Optional.ofNullable(ref.getName()).orElse("?") + ":" + Optional.ofNullable(ref.getKey()).orElse("?");
        }
        if (source.getFieldRef() != null) {
            var ref = source.getFieldRef();
            return "fieldRef:" + Optional.ofNullable(ref.getFieldPath()).orElse("?");
        }
        if (source.getResourceFieldRef() != null) {
            var ref = source.getResourceFieldRef();
            return "resourceRef:" + Optional.ofNullable(ref.getResource()).orElse("?");
        }
        return source.toString();
    }
}
