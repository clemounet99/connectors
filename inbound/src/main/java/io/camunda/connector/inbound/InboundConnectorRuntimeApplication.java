package io.camunda.connector.inbound;

import io.camunda.zeebe.spring.client.EnableZeebeClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableZeebeClient
public class InboundConnectorRuntimeApplication {

  public static void main(String[] args) {
    SpringApplication.run(InboundConnectorRuntimeApplication.class, args);
  }

}