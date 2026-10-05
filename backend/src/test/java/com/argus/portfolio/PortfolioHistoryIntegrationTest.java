package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.argus.TestcontainersConfiguration;
import com.argus.marketdata.FxRateClient;
import com.argus.security.AppUser;
import com.argus.security.CurrentUserContext;
import com.argus.security.TestUserSessions;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Portfolio value history capture + range query + endpoint (Story 3.6, FR-4). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PortfolioHistoryIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	PortfolioValuePointRepository pointsRepo;

	@Autowired
	PortfolioHistoryService history;

	@Autowired
	PositionRepository positions;

	@Autowired
	PositionLotRepository lots;

	@Autowired
	PositionAcbService acbService;

	@Autowired
	LivePortfolioService live;

	@Autowired
	com.argus.security.AppCredentialRepository pinCredentials;

	@Autowired
	com.argus.security.AppUserRepository appUsers;

	@Autowired
	com.argus.security.SessionStore sessions;

	@MockitoBean
	FxRateClient fxRateClient;

	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Toronto"));

	@BeforeEach
	void reset() {
		pointsRepo.deleteAll();
		lots.deleteAll();
		positions.deleteAll();
		pinCredentials.deleteAll();
		Set<String> keys = redis.keys("argus:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
		when(fxRateClient.sourceName()).thenReturn("test-fx");
		when(fxRateClient.usdCadOn(any())).thenReturn(Optional.of(new BigDecimal("1.35")));
	}

	/** A real signed-in {@link com.argus.security.AppUser} (Phase 2: every portfolio row needs one). */
	private Cookie login() {
		return TestUserSessions.loginAsNewUser(appUsers, sessions);
	}

	@Test
	void valueHistoryFiltersToTheRangeAscending() throws Exception {
		Cookie session = login();
		// Direct repo writes, not through a request — must be done as the SAME signed-in user as `session`.
		CurrentUserContext.runAs(sessions.userId(session.getValue()).orElseThrow(), () -> {
			pointsRepo.save(new PortfolioValuePoint(TODAY.minusDays(100), new BigDecimal("50.00")));
			pointsRepo.save(new PortfolioValuePoint(TODAY.minusDays(10), new BigDecimal("90.00")));
			pointsRepo.save(new PortfolioValuePoint(TODAY, new BigDecimal("100.00")));
		});

		mockMvc.perform(get("/api/portfolio/value-history").param("range", "1M").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))                 // the 100-day-old point is excluded
				.andExpect(jsonPath("$[0].totalValueCad").value(90.00))     // ascending by date
				.andExpect(jsonPath("$[1].totalValueCad").value(100.00));
	}

	@Test
	void captureIsIdempotentPerDay() {
		// No MockMvc/session here — a real AppUser + CurrentUserContext stands in for one, since every
		// write below is @TenantId-scoped (Phase 2) and nothing else would set it in this path.
		Long userId = appUsers.save(new AppUser("test-sub-" + java.util.UUID.randomUUID(),
				"test-" + java.util.UUID.randomUUID() + "@example.com", "Test User", null, false)).getId();

		CurrentUserContext.runAs(userId, () -> {
			// Seed one priced holding so the snapshot has a non-zero CAD value worth capturing.
			Position p = positions.save(new Position("T", null, new BigDecimal("10"), new BigDecimal("100"), "USD",
					LocalDate.of(2023, 1, 15), false, "manual"));
			lots.save(new PositionLot(p.getId(), new BigDecimal("10"), new BigDecimal("100"), "USD",
					LocalDate.of(2023, 1, 15), new BigDecimal("1.35"), false));
			acbService.recompute(p);
			live.onPriceTick("T", new BigDecimal("50"), java.time.Instant.now());

			history.capture();
			history.capture(); // same day → updates, not a second row

			// Reading back is tenant-scoped too — must happen as the same user, still inside runAs.
			assertEquals(1, pointsRepo.count());
			assertEquals(TODAY, pointsRepo.findAll().get(0).getCapturedOn());
		});
	}

	@Test
	void captureSkipsWhenNothingIsPriced() {
		history.capture(); // empty/unpriced portfolio → no misleading 0 point stored
		assertEquals(0, pointsRepo.count());
	}

	@Test
	void valueHistoryRequiresASession() throws Exception {
		mockMvc.perform(get("/api/portfolio/value-history")).andExpect(status().isUnauthorized());
	}
}
