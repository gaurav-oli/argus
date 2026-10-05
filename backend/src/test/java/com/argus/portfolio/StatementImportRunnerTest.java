package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.email.EmailSendException;
import com.argus.email.EmailSender;
import com.argus.push.PushService;
import com.argus.security.AppUser;
import com.argus.security.AppUserRepository;
import com.argus.security.CurrentUserContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The background orchestrator (one upload = one queued job): parse via {@link AdaptiveStatementParser},
 * stage, auto-confirm only when confident, and always tell the uploader what happened — by push AND
 * email — regardless of which of the three outcomes (applied / needs review / failed) occurred. */
class StatementImportRunnerTest {

	private final AdaptiveStatementParser parser = mock(AdaptiveStatementParser.class);
	private final PortfolioImportService imports = mock(PortfolioImportService.class);
	private final PushService push = mock(PushService.class);
	private final EmailSender email = mock(EmailSender.class);
	private final AppUserRepository users = mock(AppUserRepository.class);
	private final StatementImportRunner runner = new StatementImportRunner(parser, imports, push, email, users,
			"https://argus.example");

	private static final long USER_ID = 42L;
	private static final byte[] PDF = {1, 2, 3};

	private AppUser someUser() {
		return new AppUser("sub", "person@example.com", "Person", null, false);
	}

	@Test
	void aConfidentParseIsStagedAndAutoConfirmedThenNotifiesSuccess() {
		StatementParser.ParseResult result = new StatementParser.ParseResult(List.of(), List.of(), List.of(), null);
		when(parser.parse(PDF)).thenReturn(new AdaptiveStatementParser.Outcome(result, true, null));
		when(imports.stage("statement.pdf", result, "RBC"))
				.thenReturn(new ImportPreview(7L, "statement.pdf", PortfolioImport.PENDING, null, List.of()));
		when(users.findById(USER_ID)).thenReturn(Optional.of(someUser()));

		runner.submit(USER_ID, "statement.pdf", PDF, "RBC");

		verify(imports, timeout(2000)).confirmImport(7L);
		verify(push, timeout(2000)).sendToUser(eq(USER_ID), contains("updated"), anyString(), eq("/portfolio"));
		verify(email, timeout(2000)).send(eq("person@example.com"), contains("Argus"), anyString());
	}

	@Test
	void aNotConfidentParseIsStagedButNotConfirmedThenNotifiesReview() {
		StatementParser.ParseResult result = new StatementParser.ParseResult(List.of(), List.of(), List.of(),
				"Parsed automatically, but needs review.");
		when(parser.parse(PDF)).thenReturn(new AdaptiveStatementParser.Outcome(result, false, "the total didn't match"));
		when(imports.stage("statement.pdf", result, null))
				.thenReturn(new ImportPreview(9L, "statement.pdf", PortfolioImport.PENDING, result.message(), List.of()));
		when(users.findById(USER_ID)).thenReturn(Optional.of(someUser()));

		runner.submit(USER_ID, "statement.pdf", PDF, null);

		verify(push, timeout(2000)).sendToUser(eq(USER_ID), contains("look"), anyString(), eq("/portfolio"));
		verify(imports, timeout(2000).times(0)).confirmImport(anyLong());
	}

	@Test
	void whenTheParserThrowsEntirelyTheUploaderIsStillToldSomethingWentWrong() {
		when(parser.parse(PDF)).thenThrow(new RuntimeException("nothing extracted"));
		when(users.findById(USER_ID)).thenReturn(Optional.of(someUser()));

		runner.submit(USER_ID, "unreadable.pdf", PDF, null);

		verify(push, timeout(2000)).sendToUser(eq(USER_ID), contains("failed"), anyString(), eq("/portfolio"));
		verify(imports, timeout(2000).times(0)).stage(anyString(), any(), any());
	}

	@Test
	void aFailedEmailSendDoesNotPreventOrFailThePushNotification() {
		StatementParser.ParseResult result = new StatementParser.ParseResult(List.of(), List.of(), List.of(), null);
		when(parser.parse(PDF)).thenReturn(new AdaptiveStatementParser.Outcome(result, true, null));
		when(imports.stage("statement.pdf", result, null))
				.thenReturn(new ImportPreview(3L, "statement.pdf", PortfolioImport.PENDING, null, List.of()));
		when(users.findById(USER_ID)).thenReturn(Optional.of(someUser()));
		org.mockito.Mockito.doThrow(new EmailSendException("Gmail down")).when(email).send(anyString(), anyString(), anyString());

		runner.submit(USER_ID, "statement.pdf", PDF, null);

		verify(push, timeout(2000)).sendToUser(eq(USER_ID), anyString(), anyString(), eq("/portfolio"));
	}

	@Test
	void theBackgroundWorkRunsAsTheUploaderNotWhoeverIsOnTheExecutorThread() {
		StatementParser.ParseResult result = new StatementParser.ParseResult(List.of(), List.of(), List.of(), null);
		when(parser.parse(PDF)).thenAnswer(inv -> {
			assertEquals(USER_ID, CurrentUserContext.get(), "the parse must run scoped to the uploader's own user id");
			return new AdaptiveStatementParser.Outcome(result, true, null);
		});
		when(imports.stage(anyString(), any(), any()))
				.thenReturn(new ImportPreview(1L, "statement.pdf", PortfolioImport.PENDING, null, List.of()));

		runner.submit(USER_ID, "statement.pdf", PDF, null);

		// The assertion inside parser.parse() above is the real check; reaching confirmImport proves
		// it ran (an assertion failure there surfaces as the whole background task never completing).
		verify(imports, timeout(2000)).confirmImport(1L);
	}
}
