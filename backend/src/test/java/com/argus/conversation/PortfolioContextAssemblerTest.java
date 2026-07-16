package com.argus.conversation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.calendar.CalendarEvent;
import com.argus.calendar.CalendarEventType;
import com.argus.portfolio.HealthDeduction;
import com.argus.portfolio.HealthScoreResult;
import com.argus.portfolio.PortfolioSnapshot;
import com.argus.portfolio.PositionValue;
import com.argus.recommendation.AgentSignal;
import com.argus.recommendation.ProbabilityScore;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.SignalDirection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The portfolio grounding block carries holdings + health + calendar + recent recs + profile (Story 7.2). */
class PortfolioContextAssemblerTest {

	private final PortfolioContextAssembler assembler = new PortfolioContextAssembler();
	private static final LocalDate TODAY = LocalDate.of(2026, 6, 23);

	private static PortfolioSnapshot portfolio() {
		PositionValue aapl = new PositionValue("AAPL", "Apple Inc.", new BigDecimal("10"),
				new BigDecimal("190"), new BigDecimal("1900"), new BigDecimal("1500"), new BigDecimal("400"),
				new BigDecimal("26.67"), null, null, null, "USD", new BigDecimal("2600"), new BigDecimal("550"),
				new BigDecimal("40"), false, Instant.now(), "National Bank", "Cash USD", 1L, null, null, false,
				"Cash Account (USD: WK3B)", "USD", "Joint", "Gaurav Oli & Varsha Gupta", "Cash");
		return new PortfolioSnapshot(new BigDecimal("6500"), new BigDecimal("5000"), new BigDecimal("1500"),
				new BigDecimal("4577"), false, Instant.now(), List.of(aapl));
	}

	private static HealthScoreResult health() {
		return new HealthScoreResult(82, List.of(new HealthDeduction("concentration_single",
				"Single-name concentration", 12, "AAPL is 40% of your portfolio", "Trim AAPL")), Instant.now());
	}

	private static List<CalendarEvent> events() {
		return List.of(new CalendarEvent(CalendarEventType.FED, null, "FOMC rate decision",
				TODAY.plusDays(3), "FED_CALENDAR", "fed-1"));
	}

	private static List<Recommendation> recentRecs() {
		ProbabilityScore score = new ProbabilityScore(0.70, 0.30, 0.65, 4.0, 1.0, List.of());
		return List.of(new Recommendation("AAPL", score,
				List.of(new AgentSignal("agent-1-news", SignalDirection.BULLISH, 3, "Strong sentiment")),
				null, "3 months"));
	}

	@Test
	void includesAllFiveContextSections() {
		String block = assembler.assemble(portfolio(), health(), events(), recentRecs(),
				"Canadian solo investor; CAD home currency.", TODAY);

		assertTrue(block.contains("INVESTOR PROFILE"), block);
		assertTrue(block.contains("Canadian solo investor"), block);
		assertTrue(block.contains("Apple Inc."), block);
		assertTrue(block.contains("40% of portfolio"), block);
		assertTrue(block.contains("Single-name concentration"), block);
		assertTrue(block.contains("FOMC rate decision"), block);
		assertTrue(block.contains("in 3 days"), block);
		assertTrue(block.contains("AAPL: BULLISH"), block);
		assertTrue(block.contains("confidence 65%"), block);
	}

	@Test
	void emptyPortfolioStillRenders() {
		PortfolioSnapshot empty = new PortfolioSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
				null, false, Instant.now(), List.of());
		String block = assembler.assemble(empty, new HealthScoreResult(100, List.of(), Instant.now()),
				List.of(), List.of(), "profile", TODAY);

		assertTrue(block.contains("none imported yet"), block);
		assertTrue(block.contains("100/100"), block);
		assertTrue(block.contains("(none in the window)"), block);
		assertTrue(block.contains("(none yet)"), block);
	}
}
