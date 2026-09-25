package com.argus.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

class LenientJsonParserTest {

	private static final Logger log = LoggerFactory.getLogger(LenientJsonParserTest.class);

	@Test
	void parsesAWellFormedObject() {
		Optional<JsonNode> result = LenientJsonParser.parseObject("{\"a\":1}", log);

		assertTrue(result.isPresent());
		assertEquals(1, result.get().path("a").asInt());
	}

	@Test
	void parsesAWellFormedArray() {
		Optional<JsonNode> result = LenientJsonParser.parseArray("[1,2,3]", log);

		assertTrue(result.isPresent());
		assertEquals(3, result.get().size());
	}

	@Test
	void toleratesCodeFencesAndSurroundingProse() {
		Optional<JsonNode> result =
				LenientJsonParser.parseObject("Here you go:\n```json\n{\"a\":1}\n```\nDone.", log);

		assertTrue(result.isPresent());
		assertEquals(1, result.get().path("a").asInt());
	}

	@Test
	void garbageInputReturnsEmpty() {
		assertTrue(LenientJsonParser.parseObject("not json at all", log).isEmpty());
	}

	@Test
	void emptyStringReturnsEmpty() {
		assertTrue(LenientJsonParser.parseObject("", log).isEmpty());
	}

	@Test
	void nullInputReturnsEmpty() {
		assertTrue(LenientJsonParser.parseObject(null, log).isEmpty());
	}
}
