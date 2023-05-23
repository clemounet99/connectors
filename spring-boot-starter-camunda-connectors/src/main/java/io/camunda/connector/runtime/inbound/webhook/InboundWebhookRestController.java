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
package io.camunda.connector.runtime.inbound.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.api.inbound.InboundConnectorContext;
import io.camunda.connector.api.inbound.InboundConnectorResult;
import io.camunda.connector.runtime.inbound.webhook.signature.HMACAlgoCustomerChoice;
import io.camunda.connector.runtime.inbound.webhook.signature.HMACSignatureValidator;
import io.camunda.connector.runtime.inbound.webhook.signature.HMACSwitchCustomerChoice;
import io.camunda.connector.runtime.util.feel.FeelEngineWrapper;
import io.camunda.zeebe.spring.client.metrics.MetricsRecorder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty("camunda.connector.webhook.enabled")
public class InboundWebhookRestController {

  private static final Logger LOG = LoggerFactory.getLogger(InboundWebhookRestController.class);

  private final FeelEngineWrapper feelEngine;
  private final WebhookConnectorRegistry webhookConnectorRegistry;
  private final ObjectMapper jsonMapper;
  private final MetricsRecorder metricsRecorder;

  @Autowired
  public InboundWebhookRestController(
      final FeelEngineWrapper feelEngine,
      final WebhookConnectorRegistry webhookConnectorRegistry,
      final ObjectMapper jsonMapper,
      MetricsRecorder metricsRecorder) {
    this.feelEngine = feelEngine;
    this.webhookConnectorRegistry = webhookConnectorRegistry;
    this.jsonMapper = jsonMapper;
    this.metricsRecorder = metricsRecorder;
  }

  @PostMapping("/inbound/{context}")
  public ResponseEntity<WebhookResponse> inbound(
      @PathVariable String context,
      @RequestBody(required = false) byte[] bodyAsByteArray, // raw form required to calculate HMAC
      @RequestHeader Map<String, String> headers,
      @RequestHeader(value = "Content-type", required = false) String contentType)
      throws IOException {

    LOG.debug("Received inbound hook on {}", context);

    if (!webhookConnectorRegistry.containsContextPath(context)) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND.value(), "No webhook found for context: " + context, null);
    }
    metricsRecorder.increase(
        MetricsRecorder.METRIC_NAME_INBOUND_CONNECTOR,
        MetricsRecorder.ACTION_ACTIVATED,
        WebhookConnectorRegistry.TYPE_WEBHOOK);

    // TODO(nikku): what context do we expose?
    // TODO(igpetrov): handling exceptions? Throw or fail? Maybe spring controller advice?
    boolean isURLFormContentType =
        Optional.ofNullable(contentType)
            .map(
                contentHeaderType ->
                    contentHeaderType.equalsIgnoreCase("application/x-www-form-urlencoded"))
            .orElse(false);

    Map<String, String> bodyAsMap;
    if (isURLFormContentType && bodyAsByteArray != null) {
      String bodyAsString = new String(bodyAsByteArray, StandardCharsets.UTF_8);
      bodyAsMap =
          Arrays.stream(bodyAsString.split("&"))
              .filter(Objects::nonNull)
              .map(param -> param.split("="))
              .filter(param -> param.length > 1)
              .collect(Collectors.toMap(param -> param[0], param -> param[1]));
    } else {
      bodyAsMap =
          bodyAsByteArray == null
              ? Collections.emptyMap()
              : jsonMapper.readValue(bodyAsByteArray, Map.class);
    }

    HashMap<String, Object> request = new HashMap<>();
    request.put("body", bodyAsMap);
    request.put("headers", headers);
    final Map<String, Object> webhookContext = Collections.singletonMap("request", request);

    WebhookResponse response = new WebhookResponse();
    Collection<InboundConnectorContext> connectors =
        webhookConnectorRegistry.getWebhookConnectorByContextPath(context);
    for (InboundConnectorContext connectorContext : connectors) {
      WebhookConnectorProperties connectorProperties =
          new WebhookConnectorProperties(connectorContext.getProperties());

      connectorContext.replaceSecrets(connectorProperties);

      try {
        if (!isValidHmac(connectorProperties, bodyAsByteArray, headers)) {
          LOG.debug("HMAC validation failed {} :: {}", context, webhookContext);
          response.addUnauthorizedConnector(connectorProperties);
        } else { // Authorized
          if (!activationConditionTriggered(connectorProperties, webhookContext)) {
            LOG.debug("Should not activate {} :: {}", context, webhookContext);
            response.addUnactivatedConnector(connectorProperties);
          } else {
            Map<String, Object> variables = extractVariables(connectorProperties, webhookContext);
            InboundConnectorResult<?> result = connectorContext.correlate(variables);

            LOG.debug("Webhook {} created process instance {}", connectorProperties, result);

            response.addExecutedConnector(connectorProperties, result);
          }
        }
      } catch (Exception exception) {
        LOG.error("Webhook {} failed to create process instance", connectorProperties, exception);
        metricsRecorder.increase(
            MetricsRecorder.METRIC_NAME_INBOUND_CONNECTOR,
            MetricsRecorder.ACTION_FAILED,
            WebhookConnectorRegistry.TYPE_WEBHOOK);
        response.addException(connectorProperties, exception);
      }
    }

    metricsRecorder.increase(
        MetricsRecorder.METRIC_NAME_INBOUND_CONNECTOR,
        MetricsRecorder.ACTION_COMPLETED,
        WebhookConnectorRegistry.TYPE_WEBHOOK);
    return ResponseEntity.ok(response);
  }

  private boolean isValidHmac(
      final WebhookConnectorProperties connectorProperties,
      final byte[] bodyAsByteArray,
      final Map<String, String> headers)
      throws NoSuchAlgorithmException, InvalidKeyException {
    if (HMACSwitchCustomerChoice.disabled
        .name()
        .equals(connectorProperties.getShouldValidateHmac())) {
      return true;
    }

    HMACSignatureValidator validator =
        new HMACSignatureValidator(
            bodyAsByteArray,
            headers,
            connectorProperties.getHmacHeader(),
            connectorProperties.getHmacSecret(),
            HMACAlgoCustomerChoice.valueOf(connectorProperties.getHmacAlgorithm()));

    return validator.isRequestValid();
  }

  @Deprecated
  private Map<String, Object> extractVariables(
      WebhookConnectorProperties connectorProperties, Map<String, Object> context) {

    String variableMapping = connectorProperties.getVariableMapping();
    if (variableMapping == null) {
      return context;
    }
    // Variable mapping is now supported on the Connector SDK level (see
    // InboundConnectorProperties).
    // We still support the old property for backwards compatibility.
    LOG.warn(
        "Usage of deprecated property `inbound.variableMapping`. "
            + "Use `resultVariable` and `resultExpression` properties instead.");

    return feelEngine.evaluate(variableMapping, context);
  }

  @Deprecated
  private boolean activationConditionTriggered(
      WebhookConnectorProperties connectorProperties, Map<String, Object> context) {

    // at this point we assume secrets exist / had been specified
    String activationCondition = connectorProperties.getActivationCondition();
    if (activationCondition == null || activationCondition.trim().length() == 0) {
      return true;
    }
    // Activation condition is now supported on the Connector SDK level (see
    // InboundConnectorProperties).
    // We still support the old property for backwards compatibility.
    LOG.warn(
        "Usage of deprecated property `inbound.activationCondition`. Use `activationCondition` instead.");
    Object shouldActivate = feelEngine.evaluate(activationCondition, context);
    return Boolean.TRUE.equals(shouldActivate);
  }
}