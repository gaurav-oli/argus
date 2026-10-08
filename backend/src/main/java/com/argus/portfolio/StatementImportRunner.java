package com.argus.portfolio;

import com.argus.email.EmailSendException;
import com.argus.email.EmailSender;
import com.argus.push.PushService;
import com.argus.security.AppUserRepository;
import com.argus.security.CurrentUserContext;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs the automatic PDF-statement import in the background, so an upload returns immediately
 * ("we're processing it") instead of blocking the request on several sequential local-model calls
 * ({@link AdaptiveStatementParser}'s self-verification loop, and possibly a Haiku fallback).
 *
 * <p>When the parse is confident, the result is staged AND auto-confirmed — the portfolio updates
 * itself, no manual "confirm" click — because that is what the user asked this feature to do. When
 * it isn't confident, the result is only staged, left {@code pending} for a manual look: the user's
 * own "make sure no information is skipped" goal is better served by a flagged review than by
 * force-applying a guess. Either way the uploader is told what happened, by push AND by email — a
 * background job that could silently fail is exactly what "this must never fail" is meant to rule out.
 */
@Component
public class StatementImportRunner {

	private static final Logger log = LoggerFactory.getLogger(StatementImportRunner.class);

	private final AdaptiveStatementParser parser;
	private final PortfolioImportService imports;
	private final PushService push;
	private final EmailSender email;
	private final AppUserRepository users;
	private final String appUrl;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "statement-import");
		t.setDaemon(true);
		return t;
	});

	public StatementImportRunner(AdaptiveStatementParser parser, PortfolioImportService imports, PushService push,
			EmailSender email, AppUserRepository users, @Value("${argus.app-url:http://localhost:3000}") String appUrl) {
		this.parser = parser;
		this.imports = imports;
		this.push = push;
		this.email = email;
		this.users = users;
		this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
		try {
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** Queue a statement for automatic parsing. Returns immediately; the real work happens on the
	 * background executor, scoped to {@code userId} so the parsed holdings land in THEIR portfolio —
	 * not whoever's session happens to be live when the executor thread eventually picks the job up. */
	public void submit(Long userId, String filename, byte[] pdfBytes, String institution) {
		executor.submit(() -> CurrentUserContext.runAs(userId, () -> run(userId, filename, pdfBytes, institution)));
	}

	private void run(Long userId, String filename, byte[] pdfBytes, String institution) {
		try {
			AdaptiveStatementParser.Outcome outcome = parser.parse(pdfBytes);
			ImportPreview preview = imports.stage(filename, outcome.result(), institution);
			if (outcome.confident() && preview.checksPassed()) {
				imports.confirmImport(preview.importId());
				log.info("Statement import {}: confident — auto-applied to the portfolio", preview.importId());
				notify(userId, "Portfolio updated", "\"" + filename + "\" was read and applied automatically — "
						+ "your portfolio is up to date.");
			}
			else {
				String why = preview.checksPassed() ? outcome.uncertainty()
						: "some lines didn't look like real holdings (" + preview.message() + ")";
				log.info("Statement import {}: not applied automatically ({}) — left pending for review", preview.importId(), why);
				notify(userId, "A statement needs a quick look", "\"" + filename + "\" was parsed, but "
						+ describe(why) + " — please review it in Argus before it's applied.");
			}
		}
		catch (RuntimeException ex) {
			log.warn("Statement import for user {} ({}) failed entirely: {}", userId, filename, ex.getMessage());
			notify(userId, "Statement import failed", "We couldn't read \"" + filename + "\" — please try "
					+ "uploading it again, or check that the file isn't corrupted or password-protected.");
		}
	}

	private static String describe(String uncertainty) {
		return uncertainty == null || uncertainty.isBlank() ? "it couldn't be fully verified" : uncertainty;
	}

	/** Push first (near-instant), then email (the durable record) — a failure of either never blocks
	 * the other, and neither failure is allowed to look like the import itself failed. */
	private void notify(Long userId, String title, String body) {
		try {
			push.sendToUser(userId, title, body, "/portfolio");
		}
		catch (RuntimeException ex) {
			log.warn("Statement import push notification to user {} failed: {}", userId, ex.getMessage());
		}
		users.findById(userId).ifPresent(user -> {
			if (user.getEmail() == null || user.getEmail().isBlank()) {
				return;
			}
			try {
				email.send(user.getEmail(), "Argus — " + title, html(title, body));
			}
			catch (EmailSendException ex) {
				log.warn("Statement import notification email to user {} failed: {}", userId, ex.getMessage());
			}
		});
	}

	/** Plain, email-client-safe HTML matching the invite email's look (inline styles, no external assets). */
	private String html(String title, String body) {
		return """
				<div style="background:#0b0f1a;padding:40px 20px;font-family:Georgia,'Times New Roman',serif;">
				  <div style="max-width:420px;margin:0 auto;background:#12161f;border:1px solid #2a2f3a;border-radius:8px;padding:36px 32px;text-align:center;">
				    <p style="margin:0 0 6px;color:#c9a24b;font-size:11px;letter-spacing:3px;text-transform:uppercase;">Argus</p>
				    <h1 style="margin:0 0 18px;color:#f5f1e8;font-size:24px;font-weight:400;">%s</h1>
				    <p style="margin:0 0 28px;color:#a8adb8;font-size:14px;line-height:1.6;">%s</p>
				    <a href="%s/portfolio" style="display:inline-block;padding:14px 32px;background:#c9a24b;color:#0b0f1a;text-decoration:none;font-size:14px;font-weight:bold;border-radius:4px;">
				      Open Argus
				    </a>
				  </div>
				</div>
				""".formatted(title, body, appUrl);
	}
}
