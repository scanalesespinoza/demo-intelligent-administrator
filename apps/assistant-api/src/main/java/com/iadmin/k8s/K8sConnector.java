package com.iadmin.k8s;

import com.iadmin.model.DeploymentSummary;
import com.iadmin.model.K8sEvent;
import com.iadmin.model.LogChunk;
import com.iadmin.model.PodSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface K8sConnector {
    List<DeploymentSummary> listDeployments(String namespace);

    List<PodSummary> listPods(String namespace, Map<String, String> selector);

    List<K8sEvent> getEvents(String namespace, Instant from, Instant to);

    List<LogChunk> getPodLogs(String namespace, String pod, String container,
                              Instant from, Instant to, int tailLines);
}
