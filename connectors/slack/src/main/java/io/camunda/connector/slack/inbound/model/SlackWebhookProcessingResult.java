/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a proprietary license.
 * See the License.txt file for more information. You may not use this file
 * except in compliance with the proprietary license.
 */
package io.camunda.connector.slack.inbound.model;

import io.camunda.connector.api.inbound.webhook.WebhookProcessingResult;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

public class SlackWebhookProcessingResult implements WebhookProcessingResult {

  private final Map<String, Object> body;
  private final Map<String, String> headers;
  private final int statusCode;

  public SlackWebhookProcessingResult(
      Map<String, Object> body, Map<String, String> headers, int statusCode) {
    this.body = body;
    this.headers = headers;
    this.statusCode = statusCode;
  }

  @Override
  public Object body() {
    return Optional.ofNullable(body).orElse(Collections.emptyMap());
  }

  @Override
  public Map<String, String> headers() {
    return Optional.ofNullable(headers).orElse(Collections.emptyMap());
  }

  @Override
  public int statusCode() {
    return statusCode;
  }

  @Override
  public boolean strict() {
    return true;
  }
}