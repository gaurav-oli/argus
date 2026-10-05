package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import com.argus.security.AppUser;
import com.argus.security.AppUserRepository;
import com.argus.security.CurrentUserContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Proves the Phase 2 multi-user mechanism against REAL Postgres before trusting it with real
 * financial data: {@code @TenantId} + {@link PortfolioTenantResolver} + {@link CurrentUserContext}
 * must (1) stamp the signed-in user onto every insert with zero service-layer code, (2) restrict
 * every read — including a direct {@code findById} — to that same person, (3) fail CLOSED (empty, not
 * everyone's rows, not an error) when nobody is signed in, and (4) still let the one deliberate
 * shared read ({@link PositionRepository#allTickersAcrossAllUsers()}) see across everybody.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PortfolioTenantIsolationIntegrationTest {

	@Autowired
	PositionRepository positions;

	@Autowired
	AppUserRepository users;

	private Long alice;
	private Long bob;

	// Deliberately not @Transactional: a shared test-method transaction would reuse one Hibernate
	// Session (and so one resolved tenant) across both callAs(alice,...) and callAs(bob,...) — each
	// call below must open its OWN Session and genuinely re-consult the resolver, exactly like the
	// real app's short-lived, open-in-view:false transactions do. Rows persist across test methods in
	// this shared container, so each run gets its own unique accounts.
	@BeforeEach
	void twoRealUsers() {
		String run = UUID.randomUUID().toString();
		alice = users.save(new AppUser("sub-alice-" + run, "alice-" + run + "@example.com", "Alice", null, false)).getId();
		bob = users.save(new AppUser("sub-bob-" + run, "bob-" + run + "@example.com", "Bob", null, false)).getId();
	}

	@Test
	void savingAsOnePersonStampsTheirUserIdAutomatically() {
		Position saved = CurrentUserContext.callAs(alice,
				() -> positions.save(new Position("AAPL", "Apple", BigDecimal.TEN, BigDecimal.ONE, "USD",
						LocalDate.now(), false, "manual")));

		assertEquals(alice, saved.getUserId(), "no service code set this — Hibernate's @TenantId must");
	}

	@Test
	void eachPersonSeesOnlyTheirOwnPositionsThroughTheNormalRepositoryMethods() {
		CurrentUserContext.runAs(alice, () -> positions.save(newPosition("AAPL")));
		CurrentUserContext.runAs(bob, () -> positions.save(newPosition("MSFT")));

		List<String> aliceSees = CurrentUserContext.callAs(alice,
				() -> positions.findAllByOrderByTickerAsc().stream().map(Position::getTicker).toList());
		List<String> bobSees = CurrentUserContext.callAs(bob,
				() -> positions.findAllByOrderByTickerAsc().stream().map(Position::getTicker).toList());

		assertEquals(List.of("AAPL"), aliceSees);
		assertEquals(List.of("MSFT"), bobSees);
	}

	@Test
	void findByIdCannotLoadSomeoneElsesPositionEitherNotJustTheListFinders() {
		Long bobsPositionId = CurrentUserContext.callAs(bob, () -> positions.save(newPosition("MSFT"))).getId();

		Optional<Position> aliceTriesToLoadIt = CurrentUserContext.callAs(alice, () -> positions.findById(bobsPositionId));

		assertTrue(aliceTriesToLoadIt.isEmpty(), "a direct findById by a guessed/leaked id must not cross the tenant boundary");
	}

	@Test
	void noSignedInUserMeansZeroRowsNotAnErrorAndNotEveryonesRows() {
		CurrentUserContext.runAs(alice, () -> positions.save(newPosition("AAPL")));
		CurrentUserContext.runAs(bob, () -> positions.save(newPosition("MSFT")));

		// Deliberately not wrapped in runAs — simulates a stray call with nobody signed in.
		List<Position> seenByNobody = positions.findAllByOrderByTickerAsc();

		assertTrue(seenByNobody.isEmpty(), "fail CLOSED: no tenant context must never mean \"see everything\"");
	}

	@Test
	void theSharedTickerUniverseDeliberatelySeesAcrossEveryone() {
		CurrentUserContext.runAs(alice, () -> positions.save(newPosition("AAPL")));
		CurrentUserContext.runAs(bob, () -> positions.save(newPosition("MSFT")));

		// No signed-in user here either — this is the one query that must still see both, by design.
		Set<String> allTickers = Set.copyOf(positions.allTickersAcrossAllUsers());

		assertEquals(Set.of("AAPL", "MSFT"), allTickers);
	}

	private static Position newPosition(String ticker) {
		return new Position(ticker, ticker + " Inc.", BigDecimal.TEN, BigDecimal.ONE, "USD", LocalDate.now(), false, "manual");
	}
}
