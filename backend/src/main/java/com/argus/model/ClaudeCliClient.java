package com.argus.model;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude through the Claude Code CLI ({@code claude -p}) on the owner's subscription, instead of the API key.
 *
 * <p>The CLI is installed in the backend image and authenticated with a long-lived token from {@code claude
 * setup-token} ({@code CLAUDE_CODE_OAUTH_TOKEN}). Each call is a one-shot, tool-less, session-less print-mode run:
 * no built-in tools, no MCP servers, no settings files, nothing persisted. Calls are capped at
 * {@code argus.claude-cli.max-concurrent} and time out after {@code argus.claude-cli.timeout-seconds}.
 *
 * <p>Subscription usage is limited and shared with the owner's own Claude Code sessions, so a usage-limit failure
 * puts the CLI on a {@link #LIMIT_COOLDOWN} pause; callers fall back to the API key meanwhile rather than stop.
 */
@Component
public class ClaudeCliClient {

	private static final Logger log = LoggerFactory.getLogger(ClaudeCliClient.class);
	static final Duration LIMIT_COOLDOWN = Duration.ofMinutes(15);

	private final boolean enabled;
	private final String command;
	private final Duration timeout;
	private final Semaphore permits;
	private final ObjectMapper json = JsonMapper.builder().build();
	private volatile Instant pausedUntil = Instant.EPOCH;
	/** Records each call in {@code claude_cli_calls}; null in unit tests. */
	private JdbcTemplate jdbc;

	/** One CLI answer and what it used. */
	public record Result(String text, long inputTokens, long outputTokens, String model) {
	}

	public ClaudeCliClient(@Value("${argus.claude-cli.enabled:true}") boolean enabled,
			@Value("${argus.claude-cli.command:claude}") String command,
			@Value("${argus.claude-cli.timeout-seconds:120}") int timeoutSeconds,
			@Value("${argus.claude-cli.max-concurrent:2}") int maxConcurrent,
			@Value("${CLAUDE_CODE_OAUTH_TOKEN:}") String token) {
		this.enabled = enabled && token != null && !token.isBlank();
		this.command = command;
		this.timeout = Duration.ofSeconds(timeoutSeconds);
		this.permits = new Semaphore(Math.max(1, maxConcurrent));
		log.info(this.enabled ? "Claude CLI enabled — Claude calls go through the subscription, API key as fallback"
				: "Claude CLI not configured (no CLAUDE_CODE_OAUTH_TOKEN) — Claude calls use the API key");
	}

	@org.springframework.beans.factory.annotation.Autowired
	void setJdbc(ObjectProvider<JdbcTemplate> jdbc) {
		this.jdbc = jdbc.getIfAvailable();
	}

	/** Whether the CLI is configured and not paused after hitting a usage limit. */
	public boolean available() {
		return enabled && Instant.now().isAfter(pausedUntil);
	}

	/**
	 * Ask {@code model} once. Empty when the CLI isn't available (unconfigured or paused); throws
	 * {@link ModelGatewayException} when a call is attempted and fails, so the caller can fall back.
	 */
	public Optional<Result> generate(String model, String system, String prompt) {
		return generate("escalation", model, system, prompt);
	}

	/** As {@link #generate(String, String, String)}, recording the call under {@code source}. */
	public Optional<Result> generate(String source, String model, String system, String prompt) {
		if (!available()) {
			return Optional.empty();
		}
		boolean acquired;
		try {
			acquired = permits.tryAcquire(timeout.toSeconds(), TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ModelGatewayException("Interrupted waiting for a Claude CLI slot", ex);
		}
		if (!acquired) {
			throw new ModelGatewayException("Claude CLI busy — no slot within " + timeout.toSeconds() + "s");
		}
		try {
			Result r = run(model, system, prompt);
			record(source, model, r.inputTokens(), r.outputTokens(), "ok");
			return Optional.of(r);
		}
		catch (ModelGatewayException ex) {
			record(source, model, 0, 0, "failed");
			throw ex;
		}
		finally {
			permits.release();
		}
	}

	private void record(String source, String model, long in, long out, String outcome) {
		if (jdbc == null) {
			return;
		}
		try {
			jdbc.update("insert into claude_cli_calls (model, source, input_tokens, output_tokens, outcome) values (?, ?, ?, ?, ?)",
					model, source, in, out, outcome);
		}
		catch (RuntimeException ex) {
			log.warn("Could not record Claude CLI call: {}", ex.getMessage());
		}
	}

	List<String> commandLine(String model, String system) {
		List<String> cmd = new ArrayList<>(List.of(command, "-p", "--model", model, "--output-format", "json", "--tools", "",
				"--no-session-persistence", "--setting-sources", "", "--strict-mcp-config"));
		if (system != null && !system.isBlank()) {
			cmd.add("--system-prompt");
			cmd.add(system);
		}
		return cmd;
	}

	private Result run(String model, String system, String prompt) {
		Path out = null;
		Process p = null;
		try {
			out = Files.createTempFile("claude-cli", ".json");
			ProcessBuilder pb = new ProcessBuilder(commandLine(model, system)).redirectOutput(out.toFile())
					.redirectError(ProcessBuilder.Redirect.DISCARD);
			pb.environment().put("DISABLE_AUTOUPDATER", "1");
			p = pb.start();
			try (OutputStream in = p.getOutputStream()) {
				in.write(prompt.getBytes(StandardCharsets.UTF_8));
			}
			if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
				p.destroyForcibly();
				throw new ModelGatewayException("Claude CLI timed out after " + timeout.toSeconds() + "s");
			}
			return parse(Files.readString(out), model);
		}
		catch (IOException ex) {
			throw new ModelGatewayException("Claude CLI could not run: " + ex.getMessage(), ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			if (p != null) p.destroyForcibly();
			throw new ModelGatewayException("Interrupted during a Claude CLI call", ex);
		}
		finally {
			if (out != null) {
				try {
					Files.deleteIfExists(out);
				}
				catch (IOException ignored) {
					// temp file cleanup is best-effort
				}
			}
		}
	}

	/** Read {@code claude -p --output-format json}; a usage-limit error pauses the CLI. */
	Result parse(String raw, String model) {
		JsonNode node;
		try {
			node = json.readTree(raw);
		}
		catch (RuntimeException ex) {
			throw new ModelGatewayException("Claude CLI returned unreadable output");
		}
		String result = node.path("result").asString("");
		if (node.path("is_error").asBoolean(false) || !"success".equals(node.path("subtype").asString("success"))) {
			String lower = result.toLowerCase(Locale.ROOT);
			if (lower.contains("limit") || lower.contains("quota") || lower.contains("rate")) {
				pausedUntil = Instant.now().plus(LIMIT_COOLDOWN);
				log.warn("Claude CLI hit a usage limit — pausing it for {} min; the API key covers calls meanwhile",
						LIMIT_COOLDOWN.toMinutes());
			}
			throw new ModelGatewayException("Claude CLI error: " + (result.isBlank() ? node.path("subtype").asString("unknown") : result));
		}
		JsonNode usage = node.path("usage");
		return new Result(result, usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0), model);
	}
}
