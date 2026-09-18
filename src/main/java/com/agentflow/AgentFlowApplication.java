package com.agentflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentFlowApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgentFlowApplication.class, args);
	}

}
