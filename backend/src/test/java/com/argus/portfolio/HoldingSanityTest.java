package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.marketdata.ListingResolver;
import com.argus.marketdata.YahooChartClient;
import com.argus.security.AppUser;
import com.argus.security.AppUserRepository;
import com.argus.security.CurrentUserContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The 2026-10-07 import that wrote 57 bad rows, replayed: none of it may reach the portfolio unreviewed. */
class HoldingSanityTest {

	private final PositionRepository positions = mock(PositionRepository.class);
	private final AppUserRepository users = mock(AppUserRepository.class);
	private final YahooChartClient yahoo = mock(YahooChartClient.class);
	private final HoldingSanity sanity = new HoldingSanity(positions, users, yahoo, new ListingResolver("DOL"));

	private static ParsedHolding h(String ticker, String shares, String account) {
		return new ParsedHolding(ticker, null, new BigDecimal(shares), new BigDecimal("100"), "USD", LocalDate.of(2026, 3, 31),
				account, false, List.of());
	}

	private static YahooChartClient.Series quote(double p) {
		return new YahooChartClient.Series("X", "USD", BigDecimal.valueOf(p), LocalDate.now(), List.of());
	}

	@BeforeEach
	void setUp() {
		when(users.findById(1L)).thenReturn(Optional.of(new AppUser("sub", "g@example.com", "Gaurav Oli", null, true)));
		Position realNio = new Position("NIO", null, new BigDecimal("81"), new BigDecimal("4086.02"), "USD", null, false, "pdf_import");
		realNio.setBankAccount("National Bank", "687WQS-6 USD TFSA");
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(realNio));
		when(yahoo.fetch(anyString(), anyString())).thenReturn(Optional.empty()); // unknown symbols
		when(yahoo.fetch("AMD", "5d")).thenReturn(Optional.of(quote(160)));
	}

	/** Runs the check as the signed-in holder (user 1, "Gaurav Oli"), as the import does. */
	private HoldingSanity.Checked checkAsHolder(StatementParser.ParseResult r, String institution) {
		java.util.concurrent.atomic.AtomicReference<HoldingSanity.Checked> out = new java.util.concurrent.atomic.AtomicReference<>();
		CurrentUserContext.runAs(1L, () -> out.set(sanity.check(r, institution)));
		return out.get();
	}

	@Test
	void currenciesTaxWordsAndTheHoldersNameAreDroppedOutright() {
		StatementParser.ParseResult r = new StatementParser.ParseResult(
				List.of(h("CAD", "687", null), h("USD", "1", null), h("GST", "143203982", null), h("OLI", "100", null)),
				List.of(), null);

		HoldingSanity.Checked c = checkAsHolder(r, null);

		assertTrue(c.result().holdings().isEmpty());
		assertEquals(4, c.problems().size());
		assertFalse(c.clean());
	}

	@Test
	void aCompanyNameForATickerAndAnUnattributedDuplicateAreHeldForReview() {
		StatementParser.ParseResult r = new StatementParser.ParseResult(
				List.of(h("TESLA", "200", null), h("NIO", "81", null)), List.of(), null);

		HoldingSanity.Checked c = checkAsHolder(r, null);

		assertEquals(2, c.result().holdings().size());
		assertTrue(c.result().holdings().stream().allMatch(ParsedHolding::needsReview), "neither may be auto-applied");
		ParsedHolding tesla = c.result().holdings().get(0);
		assertTrue(tesla.issues().stream().anyMatch(i -> i.contains("no market quote")));
		ParsedHolding nio = c.result().holdings().get(1);
		assertTrue(nio.issues().stream().anyMatch(i -> i.contains("possibly a duplicate")));
		assertTrue(nio.issues().stream().anyMatch(i -> i.contains("no bank or account")));
	}

	@Test
	void aRealHoldingWithItsAccountPassesUntouched() {
		StatementParser.ParseResult r = new StatementParser.ParseResult(
				List.of(h("NIO", "81", "687WQS-6 USD TFSA"), h("AMD", "10", "687WQS-6 USD TFSA")), List.of(), null);

		HoldingSanity.Checked c = checkAsHolder(r, "National Bank");

		assertTrue(c.clean(), "the same lot under its own account is a re-import, and AMD is a real quoted symbol: " + c.problems());
		assertTrue(c.result().holdings().stream().noneMatch(ParsedHolding::needsReview));
	}
}
