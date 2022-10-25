package io.camunda.connector.inbound;

import io.camunda.connector.inbound.registry.InboundConnectorRegistry;
import io.camunda.connector.inbound.webhook.InboundWebhookRestController;
import io.camunda.connector.inbound.webhook.WebhookResponse;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.spring.test.ZeebeSpringTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.HashMap;
import java.util.Set;

import static io.camunda.connector.inbound.WebhookControllerPlainJavaTests.webhookProperties;
import static io.camunda.zeebe.process.test.assertions.BpmnAssert.assertThat;
import static io.camunda.zeebe.spring.test.ZeebeTestThreadSupport.waitForProcessInstanceCompleted;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true"})
@ZeebeSpringTest
@ExtendWith(MockitoExtension.class)
class WebhookControllerTestZeebeTests {

	@Test
	public void contextLoaded() {
	}

	@Autowired
	private InboundConnectorRegistry registry;

	@Autowired
	private ZeebeClient zeebeClient;

	@Autowired
	@InjectMocks
	private InboundWebhookRestController controller;

	// This test is wired by Spring - but this is not really giving us any advantage
	// Better move to plain Java as shown in InboundWebhookRestControllerTests
	@Test
	public void multipleWebhooksOnSameContextPath() throws IOException {
		deployProcess("processA");
		deployProcess("processB");

		registry.reset();
		registry.registerWebhookConnector(webhookProperties("processA", "myPath"));
		registry.registerWebhookConnector(webhookProperties("processB", "myPath"));;

		ResponseEntity<WebhookResponse> responseEntity = controller.inbound("myPath", "{}".getBytes(), new HashMap<>());

		assertEquals(200, responseEntity.getStatusCode().value());
		assertTrue(responseEntity.getBody().getUnauthorizedConnectors().isEmpty());
		assertTrue(responseEntity.getBody().getUnactivatedConnectors().isEmpty());
		assertEquals(2,
				responseEntity.getBody().getExecutedConnectors().size());
		assertEquals(Set.of("webhook-myPath-processA-1", "webhook-myPath-processB-1"),
				responseEntity.getBody().getExecutedConnectors().keySet());


		ProcessInstanceEvent piA = responseEntity.getBody().getExecutedConnectors().get("webhook-myPath-processA-1");
		waitForProcessInstanceCompleted(piA);
		assertThat(piA).isCompleted();

		ProcessInstanceEvent piB = responseEntity.getBody().getExecutedConnectors().get("webhook-myPath-processB-1");
		waitForProcessInstanceCompleted(piB);
		assertThat(piB).isCompleted();

	}

	public void deployProcess(String bpmnProcessId) {
		zeebeClient.newDeployResourceCommand().addProcessModel(
				Bpmn.createExecutableProcess(bpmnProcessId)
						.startEvent()
						.endEvent()
						.done(),
				bpmnProcessId + ".bpmn")
				.send().join();

	}

}