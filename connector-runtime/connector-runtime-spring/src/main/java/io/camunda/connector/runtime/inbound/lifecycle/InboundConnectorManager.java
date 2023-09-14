/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.camunda.connector.runtime.inbound.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.api.inbound.Health;
import io.camunda.connector.api.inbound.InboundConnectorExecutable;
import io.camunda.connector.api.inbound.PollingConnectorExecutable;
import io.camunda.connector.api.inbound.operate.ProcessInstance;
import io.camunda.connector.api.inbound.webhook.WebhookConnectorExecutable;
import io.camunda.connector.api.validation.ValidationProvider;
import io.camunda.connector.runtime.core.inbound.InboundConnectorContextImpl;
import io.camunda.connector.runtime.core.inbound.InboundConnectorDefinitionImpl;
import io.camunda.connector.runtime.core.inbound.InboundConnectorFactory;
import io.camunda.connector.runtime.core.inbound.InboundIntermediateConnectorContextImpl;
import io.camunda.connector.runtime.core.inbound.correlation.InboundCorrelationHandler;
import io.camunda.connector.runtime.core.secret.SecretProviderAggregator;
import io.camunda.connector.runtime.inbound.importer.ProcessDefinitionInspector;
import io.camunda.connector.runtime.inbound.importer.ProcessDefinitionSearch;
import io.camunda.connector.runtime.inbound.webhook.WebhookConnectorRegistry;
import io.camunda.connector.runtime.metrics.ConnectorMetrics.Inbound;
import io.camunda.operate.dto.ProcessDefinition;
import io.camunda.operate.exception.OperateException;
import io.camunda.zeebe.spring.client.metrics.MetricsRecorder;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class InboundConnectorManager {

  private static final Logger LOG = LoggerFactory.getLogger(InboundConnectorManager.class);
  private final InboundConnectorFactory connectorFactory;
  private final InboundCorrelationHandler correlationHandler;
  private final ProcessDefinitionInspector processDefinitionInspector;
  private final ProcessDefinitionSearch processDefinitionSearch;
  private final SecretProviderAggregator secretProviderAggregator;
  private final ValidationProvider validationProvider;

  private final WebhookConnectorRegistry webhookConnectorRegistry;
  private final MetricsRecorder metricsRecorder;

  // TODO: consider using external storage instead of these collections to allow multi-instance
  //  setup
  private final Map<Long, Set<ActiveInboundConnector>> activeConnectorsByProcDefKey =
      new HashMap<>();
  private Set<Long> registeredProcessDefinitions = new HashSet<>();

  private final ObjectMapper objectMapper;

  public InboundConnectorManager(
      ObjectMapper objectMapper,
      InboundConnectorFactory connectorFactory,
      InboundCorrelationHandler correlationHandler,
      ProcessDefinitionInspector processDefinitionInspector,
      ProcessDefinitionSearch processDefinitionSearch,
      SecretProviderAggregator secretProviderAggregator,
      ValidationProvider validationProvider,
      MetricsRecorder metricsRecorder,
      @Autowired(required = false) WebhookConnectorRegistry webhookConnectorRegistry) {
    this.objectMapper = objectMapper;
    this.connectorFactory = connectorFactory;
    this.correlationHandler = correlationHandler;
    this.processDefinitionInspector = processDefinitionInspector;
    this.processDefinitionSearch = processDefinitionSearch;
    this.secretProviderAggregator = secretProviderAggregator;
    this.validationProvider = validationProvider;
    this.metricsRecorder = metricsRecorder;
    this.webhookConnectorRegistry = webhookConnectorRegistry;
  }

  public void handleNewProcessDefinitions(Set<ProcessDefinition> newProcessDefinitions) {
    var connectorsToActivate =
        newProcessDefinitions.stream()
            .peek(d -> registeredProcessDefinitions.add(d.getKey()))
            .flatMap(
                d -> {
                  try {
                    return processDefinitionInspector.findInboundConnectors(d).stream();
                  } catch (OperateException e) {
                    LOG.error("Failed to inspect process definition {}", d.getKey(), e);
                    return Stream.empty();
                  }
                })
            .toList();

    for (var connector : connectorsToActivate) {
      try {
        activateConnector(connector);
      } catch (Exception e) {
        LOG.error("Failed to activate connector {}", connector, e);
      }
    }
  }

  public void handleDeletedProcessDefinitions(Set<Long> deletedProcessDefinitionKeys) {
    var connectorsToDeactivate =
        deletedProcessDefinitionKeys.stream()
            .flatMap(
                key ->
                    activeConnectorsByProcDefKey.getOrDefault(key, Collections.emptySet()).stream())
            .toList();

    for (var connector : connectorsToDeactivate) {
      try {
        deactivateConnector(connector);
      } catch (Exception e) {
        LOG.error("Failed to deactivate connector {}", connector, e);
      }
    }
  }

  public boolean isProcessDefinitionRegistered(Long key) {
    return registeredProcessDefinitions.contains(key);
  }

  private void activateConnector(InboundConnectorDefinitionImpl newConnector) {
    InboundConnectorExecutable executable = connectorFactory.getInstance(newConnector.type());
    Consumer<Throwable> cancellationCallback = throwable -> deactivateConnector(newConnector);

    var inboundContext =
        new InboundConnectorContextImpl(
            secretProviderAggregator,
            validationProvider,
            newConnector,
            correlationHandler,
            cancellationCallback,
            objectMapper);

    var connector = new ActiveInboundConnector(executable, inboundContext);

    try {
      addActiveConnector(connector);
      if (webhookConnectorRegistry == null && executable instanceof WebhookConnectorExecutable) {
        throw new Exception(
            "Cannot activate webhook connector. "
                + "Check whether property camunda.connector.webhook.enabled is set to true.");
      }
      if (executable instanceof PollingConnectorExecutable) {
        Long processDefinitionKey = newConnector.processDefinitionKey();
        Supplier<List<ProcessInstance>> processInstancesSupplier =
            () -> processDefinitionSearch.fetchProcessInstancesWithVariables(processDefinitionKey);
        executable.activate(
            new InboundIntermediateConnectorContextImpl(inboundContext, processInstancesSupplier));
      } else {
        executable.activate(inboundContext);
      }
      if (webhookConnectorRegistry != null
          && connector.executable() instanceof WebhookConnectorExecutable) {
        webhookConnectorRegistry.register(connector);
        LOG.trace("Registering webhook: " + newConnector.type());
      }
      inboundContext.reportHealth(Health.up());
      metricsRecorder.increase(
          Inbound.METRIC_NAME_ACTIVATIONS, Inbound.ACTION_ACTIVATED, newConnector.type());
    } catch (Exception e) {
      inboundContext.reportHealth(Health.down(e));
      // log and continue with other connectors anyway
      LOG.error("Failed to activate inbound connector " + newConnector, e);
      metricsRecorder.increase(
          Inbound.METRIC_NAME_ACTIVATIONS, Inbound.ACTION_ACTIVATION_FAILED, newConnector.type());
    }
  }

  private void addActiveConnector(ActiveInboundConnector connector) {
    activeConnectorsByProcDefKey.compute(
        connector.context().getDefinition().processDefinitionKey(),
        (bpmnId, connectors) -> {
          if (connectors == null) {
            Set<ActiveInboundConnector> set = new HashSet<>();
            set.add(connector);
            return set;
          }
          connectors.add(connector);
          return connectors;
        });
  }

  private void deactivateConnector(InboundConnectorDefinitionImpl definition) {
    findActiveConnector(definition).ifPresent(this::deactivateConnector);
    metricsRecorder.increase(
        Inbound.METRIC_NAME_ACTIVATIONS, Inbound.ACTION_DEACTIVATED, definition.type());
  }

  private void deactivateConnector(ActiveInboundConnector connector) {
    try {
      connector.executable().deactivate();
      activeConnectorsByProcDefKey
          .get(connector.context().getDefinition().processDefinitionKey())
          .remove(connector);
      if (webhookConnectorRegistry != null
          && connector.executable() instanceof WebhookConnectorExecutable) {
        webhookConnectorRegistry.deregister(connector);
        LOG.trace("Unregistering webhook: " + connector.context().getDefinition().type());
      }
      metricsRecorder.increase(
          Inbound.METRIC_NAME_ACTIVATIONS,
          Inbound.ACTION_DEACTIVATED,
          connector.context().getDefinition().type());
    } catch (Exception e) {
      // log and continue with other connectors anyway
      LOG.error("Failed to deactivate inbound connector " + connector, e);
    }
  }

  private Optional<ActiveInboundConnector> findActiveConnector(
      InboundConnectorDefinitionImpl definition) {
    return Optional.ofNullable(activeConnectorsByProcDefKey.get(definition.processDefinitionKey()))
        .flatMap(
            connectors ->
                connectors.stream()
                    .filter(c -> c.context().getDefinition().equals(definition))
                    .findFirst());
  }

  public List<ActiveInboundConnector> query(ActiveInboundConnectorQuery request) {
    var filteredByBpmnProcessId = filterByBpmnProcessId(request.bpmnProcessId());
    var filteredByType = filterByConnectorType(filteredByBpmnProcessId, request.type());
    return filterByElementId(filteredByType, request.elementId());
  }

  private List<ActiveInboundConnector> filterByBpmnProcessId(String bpmnProcessId) {
    if (bpmnProcessId != null) {
      return activeConnectorsByProcDefKey.values().stream()
          .flatMap(Collection::stream)
          .filter(
              connector ->
                  bpmnProcessId.equals(connector.context().getDefinition().bpmnProcessId()))
          .toList();
    } else {
      return activeConnectorsByProcDefKey.values().stream().flatMap(Collection::stream).toList();
    }
  }

  private List<ActiveInboundConnector> filterByConnectorType(
      List<ActiveInboundConnector> connectors, String type) {
    if (type == null) {
      return connectors;
    }
    return connectors.stream()
        .filter(props -> type.equals(props.context().getDefinition().type()))
        .collect(Collectors.toList());
  }

  private List<ActiveInboundConnector> filterByElementId(
      List<ActiveInboundConnector> connectors, String elementId) {

    if (elementId == null) {
      return connectors;
    }
    return connectors.stream()
        .filter(connector -> elementId.equals(connector.context().getDefinition().elementId()))
        .collect(Collectors.toList());
  }
}
