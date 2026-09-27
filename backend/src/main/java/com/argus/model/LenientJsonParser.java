package com.argus.model;

import java.util.ArrayList;
import java.util.List;
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
		if (lo < 0) {
			return Optional.empty();
		}
		if (hi <= lo) {
			// An opening bracket but no closing one: the answer was cut off before it ever finished a structure.
			Optional<JsonNode> repaired = repairTruncated(s.substring(lo), open);
			if (repaired.isPresent()) {
				log.info("Lenient JSON parse: response was truncated — salvaged the completed part");
			}
			return repaired;
		}
		try {
			return Optional.of(JSON.readTree(s.substring(lo, hi + 1)));
		}
		catch (RuntimeException ex) {
			// The local model has a hard output cap; a long answer is often cut off mid-structure. Salvage what was
			// completed rather than discarding an otherwise good response.
			Optional<JsonNode> repaired = repairTruncated(s.substring(lo), open);
			if (repaired.isPresent()) {
				log.info("Lenient JSON parse: response was truncated — salvaged the completed part");
				return repaired;
			}
			log.warn("Lenient JSON parse failed: {}", ex.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Repairs a JSON value cut off by an output-token cap. Tries the response cut back to each earlier comma boundary (outside
	 * strings), longest first, closes every open object/array, and lets the real parser decide which candidate is valid — so a
	 * dangling key, a half-written string or a trailing comma simply fail to parse and the next-shorter cut is tried. What was fully
	 * written is kept; nothing is invented. Package-visible for tests.
	 */
	static Optional<JsonNode> repairTruncated(String s, char open) {
		List<Integer> cuts = new ArrayList<>();
		boolean inString = false, escaped = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (inString) {
				if (escaped) escaped = false;
				else if (c == '\\') escaped = true;
				else if (c == '"') inString = false;
			}
			else if (c == '"') inString = true;
			else if (c == ',') cuts.add(i);
		}
		if (!inString) cuts.add(s.length()); // the whole text is a candidate only if it did not end mid-string
		for (int k = cuts.size() - 1; k >= 0; k--) {
			String head = s.substring(0, cuts.get(k));
			String candidate = head + closersFor(head);
			try {
				return Optional.of(JSON.readTree(candidate));
			}
			catch (RuntimeException ex) {
				// try the next-shorter cut
			}
		}
		return Optional.empty();
	}

	/** The brackets still open at the end of {@code head}, closed in the right order. */
	private static String closersFor(String head) {
		java.util.ArrayDeque<Character> stack = new java.util.ArrayDeque<>();
		boolean inString = false, escaped = false;
		for (int i = 0; i < head.length(); i++) {
			char c = head.charAt(i);
			if (inString) {
				if (escaped) escaped = false;
				else if (c == '\\') escaped = true;
				else if (c == '"') inString = false;
			}
			else if (c == '"') inString = true;
			else if (c == '{' || c == '[') stack.push(c);
			else if ((c == '}' || c == ']') && !stack.isEmpty()) stack.pop();
		}
		StringBuilder sb = new StringBuilder();
		while (!stack.isEmpty()) sb.append(stack.pop() == '{' ? '}' : ']');
		return sb.toString();
	}
}
