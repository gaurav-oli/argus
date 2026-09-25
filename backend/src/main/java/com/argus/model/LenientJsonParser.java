package com.argus.model;

import java.util.Optional;
import org.slf4j.Logger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Defensive JSON extraction for LLM responses — strips code fences, locates the outermost
 * object/array span, and parses it, returning empty (never throwing) on any malformed input. The
 * exact shape previously hand-duplicated across {@code LogicReviewService.parse}, {@code
 * CauseClassificationService.parse}, {@code ResearchAgentService.parseSteps}, and {@code
 * MacroKeywordLearningService.parse}; those existing call sites are unchanged (behavior-preserving,
 * out of scope) but are candidates for a future migration onto this utility.
 */
public final class LenientJsonParser {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private LenientJsonParser() {
	}

	/** Extracts and parses the outermost {@code {...}} object span. Empty on any failure. */
	public static Optional<JsonNode> parseObject(String raw, Logger log) {
		return parse(raw, '{', '}', log);
	}

	/** Extracts and parses the outermost {@code [...]} array span. Empty on any failure. */
	public static Optional<JsonNode> parseArray(String raw, Logger log) {
		return parse(raw, '[', ']', log);
	}

	private static Optional<JsonNode> parse(String raw, char open, char close, Logger log) {
		if (raw == null) {
			return Optional.empty();
		}
		String s = raw.replace("```json", "").replace("```", "").strip();
		int lo = s.indexOf(open);
		int hi = s.lastIndexOf(close);
		if (lo < 0 || hi <= lo) {
			return Optional.empty();
		}
		try {
			return Optional.of(JSON.readTree(s.substring(lo, hi + 1)));
		}
		catch (RuntimeException ex) {
			log.warn("Lenient JSON parse failed: {}", ex.getMessage());
			return Optional.empty();
		}
	}
}
