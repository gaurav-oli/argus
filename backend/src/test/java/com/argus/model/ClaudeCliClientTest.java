package com.argus.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClaudeCliClientTest {

	private ClaudeCliClient client(String token) {
		return new ClaudeCliClient(true, "claude", 30, 2, token);
	}

	@Test
	void unconfiguredWithoutAToken() {
		assertFalse(client("").available());
		assertTrue(client("").generate("m", null, "hi").isEmpty());
	}

	@Test
	void parsesASuccessfulAnswer() {
		var r = client("t").parse("""
				{"type":"result","subtype":"success","is_error":false,"result":"0.3","usage":{"input_tokens":555,"output_tokens":45}}""",
				"claude-haiku-4-5");
		assertEquals("0.3", r.text());
		assertEquals(555, r.inputTokens());
		assertEquals(45, r.outputTokens());
	}

	@Test
	void aUsageLimitPausesTheCliSoTheApiTakesOver() {
		ClaudeCliClient c = client("t");
		assertThrows(ModelGatewayException.class, () -> c.parse(
				"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Claude usage limit reached\"}", "m"));
		assertFalse(c.available());
	}

	@Test
	void anOrdinaryErrorDoesNotPause() {
		ClaudeCliClient c = client("t");
		assertThrows(ModelGatewayException.class, () -> c.parse(
				"{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,\"result\":\"\"}", "m"));
		assertTrue(c.available());
	}

	@Test
	void runsToolLessSessionLessAndWithoutLocalSettings() {
		List<String> cmd = client("t").commandLine("claude-haiku-4-5", "be brief");
		assertEquals(List.of("claude", "-p", "--model", "claude-haiku-4-5", "--output-format", "json", "--tools", "",
				"--no-session-persistence", "--setting-sources", "", "--strict-mcp-config", "--system-prompt", "be brief"), cmd);
	}

	@Test
	void escalationUsesTheCliFirstAndTheApiOnlyWhenItFails() {
		ClaudeCliClient cli = mock(ClaudeCliClient.class);
		HaikuFallback api = mock(HaikuFallback.class);
		when(api.generate(anyString())).thenReturn("from api");
		CliFirstHaikuFallback f = new CliFirstHaikuFallback(cli, api, "claude-haiku-4-5");

		when(cli.generate(anyString(), any(), anyString())).thenReturn(Optional.of(new ClaudeCliClient.Result("from cli", 1, 1, "m")));
		assertEquals("from cli", f.generate("q"));
		verify(api, never()).generate(anyString());

		when(cli.generate(anyString(), any(), anyString())).thenThrow(new ModelGatewayException("limit"));
		assertEquals("from api", f.generate("q"));

		org.mockito.Mockito.doReturn(Optional.empty()).when(cli).generate(anyString(), any(), anyString());
		assertEquals("from api", f.generate("q"));
	}
}
