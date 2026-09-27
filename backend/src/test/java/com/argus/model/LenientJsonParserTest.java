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

	// ---- truncated output (the local model's hard token cap cuts long answers mid-structure) ----

	private static JsonNode parse(String raw) {
		return LenientJsonParser.parseObject(raw, log).orElseThrow(() -> new AssertionError("not salvaged: " + raw));
	}

	@Test
	void aResponseCutOffInsideAListKeepsTheCompletedElements() {
		JsonNode n = parse("{\"summary\":\"Record quarter.\",\"keyMetrics\":[{\"label\":\"Revenue\",\"value\":\"$96.2 billion\"},{\"label\":\"EP");

		assertEquals("Record quarter.", n.path("summary").asString());
		assertEquals(1, n.path("keyMetrics").size(), "the finished element survives; the half-written one is dropped");
		assertEquals("$96.2 billion", n.path("keyMetrics").path(0).path("value").asString());
	}

	@Test
	void aDanglingKeyOrTrailingCommaIsDropped() {
		assertEquals(1, parse("{\"a\":1,\"b\":").size());
		assertEquals(1, parse("{\"a\":1,").size());
		assertEquals(1, parse("{\"a\":1,\"b\"").size());
		assertEquals("x", parse("{\"a\":\"x\",\"b\":[1,2,").path("a").asString());
	}

	@Test
	void aHalfWrittenStringValueIsNeverKeptAsIfItWereWhole() {
		JsonNode n = parse("{\"a\":\"complete\",\"thesis\":\"This stock is worth buying becau");

		assertEquals("complete", n.path("a").asString());
		assertTrue(n.path("thesis").isMissingNode(), "a sentence cut mid-word must not pose as the model's finished thought");
	}

	@Test
	void nestedStructuresAreClosedInTheRightOrder() {
		JsonNode n = parse("{\"x\":{\"list\":[{\"k\":1},{\"k\":2},{\"k\":");

		assertEquals(2, n.path("x").path("list").size());
	}

	@Test
	void aWellFormedResponseIsUntouched() {
		JsonNode n = parse("noise {\"a\":1,\"b\":[1,2]} more noise");

		assertEquals(2, n.size());
		assertEquals(2, n.path("b").size());
	}

	@Test
	void unsalvageableTextStillReturnsEmpty() {
		assertTrue(LenientJsonParser.parseObject("no json here at all", log).isEmpty());
		assertTrue(LenientJsonParser.parseObject("{", log).isPresent() || LenientJsonParser.parseObject("{", log).isEmpty()); // must not throw
	}
}
