package com.iadmin.config;

import com.iadmin.model.ConfigSnapshot;

public interface ConfigConnector {
    ConfigSnapshot getDeploymentConfig(String namespace, String deploymentName);
}
