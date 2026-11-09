package com.argus.grading;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Does a stronger model read the news better? (strategy rebuild, Phase 1 experiment.)
 *
 * <p>Each night a small random sample of the day's ticker-tagged headlines — already scored by the local model — is
 * re-scored by a stronger Claude model through the existing API key. Both scores, for the same articles, are turned
 * into two virtual agents in the grader ({@code exp-news-claude}, {@code exp-news-gemma}), so the report card grades
 * them on the same sample and the same 5-day window as the live News agent. They never vote. If the stronger model's
 * card isn't clearly better after enough clusters, the experiment has answered the question for the cost of a few
 * dozen calls a day. Off without an API key; {@code argus.experiment.enabled=false} turns it off.
 */
@Component
@Order(5)
public class ModelExperiment implements NightlyStep {

	private static final Logger log = LoggerFactory.getLogger(ModelExperiment.class);
	private static final ZoneId NY = ZoneId.of("America/New_York");
	public static final String CLAUDE_AGENT = "exp-news-claude";
	public static final String LOCAL_AGENT = "exp-news-gemma";
	/** A ticker-day's average score must clear this before the virtual agent calls a direction (as Agent 1 does). */
	static final double DEADZONE = 0.15;
	private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
	/** Published Claude Opus 5.5 pricing, USD per million tokens (input, output). */
	static final double INPUT_PER_MTOK = 4.0;
	static final double OUTPUT_PER_MTOK = 20.0;

	private final JdbcTemplate jdbc;
	private final AnthropicClient client;
	private final String model;
	private final int dailySample;
	private final boolean enabled;
	private final com.argus.model.ClaudeCliClient cli;

	public ModelExperiment(JdbcTemplate jdbc, @Value("${argus.haiku.api-key:}") String apiKey,
			@Value("${argus.experiment.model:claude-opus-5-5}") String model,
			@Value("${argus.experiment.daily-sample:20}") int dailySample,
			@Value("${argus.experiment.enabled:true}") boolean enabled, com.argus.model.ClaudeCliClient cli) {
		this.jdbc = jdbc;
		this.model = model;
		this.dailySample = dailySample;
		this.cli = cli;
		boolean hasKey = apiKey != null && !apiKey.isBlank();
		this.client = hasKey ? AnthropicOkHttpClient.builder().apiKey(apiKey).build() : null;
		this.enabled = enabled && (hasKey || cli.available());
	}

	@Override
	public String name() {
		return "model-experiment";
	}

	@Override
	public void run() {
		if (!enabled) {
			return;
		}
		LocalDate today = LocalDate.now(NY);
		List<Map<String, Object>> sample = jdbc.queryForList("""
				select a.id, t.ticker, a.headline, coalesce(a.summary, '') as summary, a.sentiment_score::float8 as score
				from news_articles a cross join lateral unnest(a.tickers) as t(ticker)
				where a.sentiment_score is not null and a.published_at >= now() - interval '1 day'
				  and not exists (select 1 from model_experiment e where e.article_id = a.id and e.ticker = t.ticker)
				order by random() limit ?""", dailySample);
		int scored = 0;
		for (Map<String, Object> a : sample) {
			String ticker = (String) a.get("ticker");
			Double claude = null;
			String note = null;
			try {
				claude = score(ticker, (String) a.get("headline"), (String) a.get("summary"));
				if (claude == null) note = "no usable score";
			}
			catch (RuntimeException ex) {
				note = "call failed: " + ex.getClass().getSimpleName();
				log.debug("Model experiment call failed: {}", ex.getMessage());
			}
			jdbc.update("""
					insert into model_experiment (article_id, ticker, call_date, model, local_score, claude_score, note)
					values (?, ?, ?, ?, ?, ?, ?) on conflict (article_id, ticker) do nothing""",
					a.get("id"), ticker, today, model, a.get("score"), claude, note);
			if (claude != null) scored++;
		}
		writePredictions(today);
		log.info("Model experiment: {} of {} sampled headline(s) re-scored by {}", scored, sample.size(), model);
	}

	static final String SYSTEM = "You rate how a news item bears on a stock's price over the next week. Reply with one number "
			+ "from -1 (clearly bad for the stock) to 1 (clearly good), 0 if it doesn't matter, and nothing else.";

	/**
	 * The stronger model's sentiment toward {@code ticker}, −1..1; null when it gave no usable answer or declined.
	 * Goes through the Claude Code CLI (subscription) first, the API key when the CLI is unavailable or fails.
	 */
	Double score(String ticker, String headline, String summary) {
		String user = "Stock: " + ticker + "\nHeadline: " + headline + (summary.isBlank() ? "" : "\nSummary: " + summary);
		try {
			var r = cli.generate("model_experiment", model, SYSTEM, user);
			if (r.isPresent()) {
				return number(r.get().text());
			}
		}
		catch (com.argus.model.ModelGatewayException ex) {
			log.debug("Experiment CLI call failed ({}); trying the API key", ex.getMessage());
		}
		if (client == null) {
			return null;
		}
		MessageCreateParams params = MessageCreateParams.builder()
				.model(model)
				.maxTokens(2000L)
				.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
				.system(SYSTEM)
				.addUserMessage(user)
				.build();
		Message response = client.messages().create(params);
		recordCost(response);
		if (response.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false)) {
			return null; // a declined sample is skipped, not guessed
		}
		return number(response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text())
				.reduce("", String::concat));
	}

	static Double number(String text) {
		Matcher m = NUMBER.matcher(text == null ? "" : text.trim());
		if (!m.find()) {
			return null;
		}
		return Math.max(-1, Math.min(1, Double.parseDouble(m.group())));
	}

	private void recordCost(Message response) {
		try {
			long in = response.usage().inputTokens();
			long out = response.usage().outputTokens();
			double usd = (in * INPUT_PER_MTOK + out * OUTPUT_PER_MTOK) / 1_000_000.0;
			jdbc.update("insert into cost_events (occurred_at, model, source, input_tokens, output_tokens, cost_usd) values (now(), ?, 'model_experiment', ?, ?, ?)",
					model, in, out, usd);
		}
		catch (RuntimeException ex) {
			log.debug("Could not record experiment cost: {}", ex.getMessage());
		}
	}

	/**
	 * Turn the day's paired scores into one prediction per virtual agent per ticker, from the same articles on both
	 * sides, so the grader and report card compare them like for like.
	 */
	void writePredictions(LocalDate day) {
		for (String[] side : new String[][] {{CLAUDE_AGENT, "claude_score"}, {LOCAL_AGENT, "local_score"}}) {
			jdbc.update("""
					insert into signal_grade (agent, ticker, call_date, direction, strength, sector)
					select ?, e.ticker, e.call_date,
					       case when avg(e.%1$s) > %2$s then 'BULLISH' when avg(e.%1$s) < -%2$s then 'BEARISH' else 'NEUTRAL' end,
					       abs(avg(e.%1$s)),
					       (select r.sector from recommendations r where r.ticker = e.ticker order by r.created_at desc limit 1)
					from model_experiment e
					where e.call_date = ? and e.claude_score is not null
					group by e.ticker, e.call_date
					on conflict (agent, ticker, call_date) do update set direction = excluded.direction,
					    strength = excluded.strength where signal_grade.excess_5 is null"""
					.formatted(side[1], String.format(Locale.ROOT, "%.2f", DEADZONE)), side[0], day);
		}
	}
}
