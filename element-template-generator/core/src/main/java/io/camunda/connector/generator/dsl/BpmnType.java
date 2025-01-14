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
package io.camunda.connector.generator.dsl;

import com.fasterxml.jackson.annotation.JsonValue;

public enum BpmnType {
  TASK("bpmn:Task"),
  SERVICE_TASK("bpmn:ServiceTask"),
  RECEIVE_TASK("bpmn:ReceiveTask"),
  SCRIPT_TASK("bpmn:ScriptTask"),
  START_EVENT("bpmn:StartEvent"),
  INTERMEDIATE_CATCH_EVENT("bpmn:IntermediateCatchEvent"),
  INTERMEDIATE_THROW_EVENT("bpmn:IntermediateThrowEvent"),
  MESSAGE_START_EVENT("bpmn:MessageStartEvent"),
  END_EVENT("bpmn:EndEvent"),
  MESSAGE_END_EVENT("bpmn:MessageEndEvent");

  private final String name;

  BpmnType(String name) {
    this.name = name;
  }

  @JsonValue
  public String getName() {
    return name;
  }

  public static BpmnType fromName(String name) {
    for (BpmnType type : values()) {
      if (type.getName().equals(name)) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown BPMN type: " + name);
  }
}
