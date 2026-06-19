package com.argus.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.argus.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the Model Gateway wires under the {@code dev} profile (mock model, no Ollama)
 * and returns the canned response. Boots the full context, so it uses Testcontainers
 * for the data layer (Docker required), consistent with the Story 1.2 tests.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class ModelGatewayWiringTest {

	@Autowired
	ModelGateway modelGateway;

	@Test
	void devProfileGatewayReturnsMockResponse() {
		assertNotNull(modelGateway, "ModelGateway bean should be wired under the dev profile");
		assertEquals("[dev-mock] Argus Model Gateway is alive.", modelGateway.generate("ping"));
	}
}
