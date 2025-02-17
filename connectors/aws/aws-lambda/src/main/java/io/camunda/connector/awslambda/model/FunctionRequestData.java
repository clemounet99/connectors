/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a proprietary license.
 * See the License.txt file for more information. You may not use this file
 * except in compliance with the proprietary license.
 */
package io.camunda.connector.awslambda.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;

public class FunctionRequestData {

  @NotEmpty private String functionName;
  @NotNull private Object payload;
  private OperationType operationType; // this is not use and not implemented yet

  @Deprecated private String region;

  public String getFunctionName() {
    return functionName;
  }

  public void setFunctionName(final String functionName) {
    this.functionName = functionName;
  }

  public Object getPayload() {
    return payload;
  }

  public void setPayload(final Object payload) {
    this.payload = payload;
  }

  public OperationType getOperationType() {
    return operationType;
  }

  public void setOperationType(final OperationType operationType) {
    this.operationType = operationType;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    FunctionRequestData that = (FunctionRequestData) o;
    return Objects.equals(functionName, that.functionName)
        && Objects.equals(payload, that.payload)
        && operationType == that.operationType
        && Objects.equals(region, that.region);
  }

  @Override
  public int hashCode() {
    return Objects.hash(functionName, payload, operationType, region);
  }

  @Override
  public String toString() {
    return "FunctionRequestData{"
        + "functionName='"
        + functionName
        + '\''
        + ", payload="
        + payload
        + ", operationType="
        + operationType
        + ", region='"
        + region
        + '\''
        + '}';
  }

  @Deprecated
  public String getRegion() {
    return region;
  }

  @Deprecated
  public void setRegion(String region) {
    this.region = region;
  }
}
