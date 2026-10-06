package com.argus.ops;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

/**
 * Re-runs any clock-scheduled job whose last slot passed while the host was down (or before a deploy
 * finished). A cron job only fires at its time; before this, a Mini that was off at 08:00 simply had
 * no morning briefing that day, no health score, no learning report — and nothing said so. Shortly
 * after startup, each {@code @Scheduled(cron)} job whose most recent expected run is newer than its
 * last recorded completion ({@link ScheduledRunLedger}) runs once, oldest-missed first.
 *
 * <ul>
 *   <li>Jobs that fire hourly or faster are skipped — their next slot is the catch-up.</li>
 *   <li>A slot missed longer ago than the job's own period (capped at 72h) is not chased: the next
 *       regular run is closer than any catch-up would be useful. The morning briefing is held to a
 *       tighter window so a late-night boot doesn't send a "morning" briefing at midnight.</li>
 *   <li>A job never seen before is stamped, not run — no surprise first-boot runs.</li>
 *   <li>Jobs with their own staleness-aware startup logic are left to it (listed in {@link #OWN_CATCH_UP}).</li>
 * </ul>
 */
@Component
public class MissedRunCatchUp {

	private static final Logger log = LoggerFactory.getLogger(MissedRunCatchUp.class);

	/** Have their own startup catch-up that already decides on staleness; running them twice could collide. */
	static final Set<String> OWN_CATCH_UP = Set.of(
			"com.argus.calendar.Agent7CalendarService", "com.argus.recommendation.RecommendationTrigger",
			"com.argus.technical.CandleIngestionService", "com.argus.fundamentals.FundamentalsService",
			"com.argus.strategy.StrategyCandleIngestion");

	static final Duration MAX_LATENESS = Duration.ofHours(72);
	/** Per-job tighter windows: a briefing more than this late is no longer a morning briefing. */
	static final Map<String, Duration> LATENESS_OVERRIDES = Map.of(
			"com.argus.briefing.BriefingService.scheduledBriefing", Duration.ofHours(10),
			"com.argus.notification.DigestService.weeklyDigest", Duration.ofHours(24));
	private static final Duration START_DELAY = Duration.ofMinutes(3);

	private final ApplicationContext context;
	private final Environment environment;
	private final ScheduledRunLedger ledger;

	@Value("${argus.boot-catch-up.enabled:true}")
	private boolean enabled = true;

	public MissedRunCatchUp(ApplicationContext context, Environment environment, ScheduledRunLedger ledger) {
		this.context = context;
		this.environment = environment;
		this.ledger = ledger;
	}

	/** One cron job: the bean, its method, and its schedule. */
	record Job(String key, Object bean, Method method, CronExpression cron, ZoneId zone) {
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (!enabled) {
			return;
		}
		Thread.startVirtualThread(() -> {
			try {
				Thread.sleep(START_DELAY); // let price feeds, the regime and agents warm up first
				runMissed(Instant.now());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			catch (RuntimeException ex) {
				log.warn("Missed-run catch-up failed: {}", ex.getMessage());
			}
		});
	}

	void runMissed(Instant now) {
		record Due(Job job, Instant missed) {
		}
		List<Due> due = new ArrayList<>();
		for (Job job : cronJobs()) {
			Optional<Instant> missed = missedSlot(job, now);
			missed.ifPresent(m -> due.add(new Due(job, m)));
		}
		due.sort(Comparator.comparing(Due::missed));
		for (Due d : due) {
			log.info("Catching up {} — its {} run was missed", d.job().key(), d.missed());
			try {
				ReflectionUtils.makeAccessible(d.job().method());
				d.job().method().invoke(d.job().bean());
				ledger.recordRun(d.job().key());
			}
			catch (ReflectiveOperationException | RuntimeException ex) {
				log.warn("Catch-up run of {} failed: {}", d.job().key(), ex.getMessage());
			}
		}
	}

	/** The slot to catch up for {@code job}, or empty when it isn't due (see the class rules). */
	Optional<Instant> missedSlot(Job job, Instant now) {
		ZonedDateTime at = now.atZone(job.zone());
		ZonedDateTime n1 = job.cron().next(at);
		ZonedDateTime n2 = n1 == null ? null : job.cron().next(n1);
		if (n1 == null || n2 == null) {
			return Optional.empty();
		}
		Duration period = Duration.between(n1, n2);
		if (period.compareTo(Duration.ofHours(1)) <= 0) {
			return Optional.empty();
		}
		Instant lastExpected = lastFireAtOrBefore(job.cron(), at);
		if (lastExpected == null) {
			return Optional.empty();
		}
		Optional<Instant> lastRun = ledger.lastRun(job.key());
		if (lastRun.isEmpty()) {
			ledger.recordRun(job.key()); // first sighting: start tracking, don't run
			return Optional.empty();
		}
		if (!lastRun.get().isBefore(lastExpected)) {
			return Optional.empty();
		}
		Duration window = LATENESS_OVERRIDES.getOrDefault(job.key(), period.compareTo(MAX_LATENESS) < 0 ? period : MAX_LATENESS);
		return Duration.between(lastExpected, now).compareTo(window) <= 0 ? Optional.of(lastExpected) : Optional.empty();
	}

	static Instant lastFireAtOrBefore(CronExpression cron, ZonedDateTime at) {
		ZonedDateTime t = cron.next(at.minusDays(35));
		Instant last = null;
		while (t != null && !t.isAfter(at)) {
			last = t.toInstant();
			t = cron.next(t);
		}
		return last;
	}

	List<Job> cronJobs() {
		List<Job> jobs = new ArrayList<>();
		for (String name : context.getBeanDefinitionNames()) {
			Object bean;
			try {
				bean = context.getBean(name);
			}
			catch (RuntimeException ex) {
				continue; // a lazy/prototype bean that can't be created here isn't a scheduled singleton
			}
			Class<?> type = AopUtils.getTargetClass(bean);
			if (!type.getName().startsWith("com.argus.") || OWN_CATCH_UP.contains(type.getName())) {
				continue;
			}
			for (Method m : ReflectionUtils.getUniqueDeclaredMethods(type)) {
				Scheduled s = AnnotatedElementUtils.findMergedAnnotation(m, Scheduled.class);
				if (s == null || s.cron().isBlank()) {
					continue;
				}
				String expr = environment.resolvePlaceholders(s.cron());
				if (expr.isBlank() || Scheduled.CRON_DISABLED.equals(expr)) {
					continue;
				}
				ZoneId zone = s.zone().isBlank() ? ZoneId.systemDefault() : ZoneId.of(environment.resolvePlaceholders(s.zone()));
				Method invocable = AopUtils.selectInvocableMethod(m, bean.getClass());
				jobs.add(new Job(m.getDeclaringClass().getName() + "." + m.getName(), bean, invocable,
						CronExpression.parse(expr), zone));
			}
		}
		return jobs;
	}
}
