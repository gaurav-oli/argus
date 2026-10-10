package com.argus.watchlist;

import com.argus.security.CurrentUserContext;
import com.argus.security.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Watchlist CRUD (session-gated under {@code /api/watchlist}). Adding a ticker widens the known universe
 * (via {@link CompositeKnownUniverse}), so the agents start covering it and Agent 5 recommends on it
 * alongside your holdings. Manual entries only here; the auto-discovery agent writes DISCOVERED entries
 * directly.
 */
@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

	private final WatchlistRepository repo;
	private final DiscoveryService discovery;
	private final CurrentUserService users;

	public WatchlistController(WatchlistRepository repo, DiscoveryService discovery, CurrentUserService users) {
		this.repo = repo;
		this.discovery = discovery;
		this.users = users;
	}

	/** S-C1: your own picks plus the system's discoveries — never another person's picks. */
	@GetMapping
	public List<WatchlistView> list() {
		return repo.visibleTo(CurrentUserContext.get()).stream().map(WatchlistView::from).toList();
	}

	/** Run the auto-discovery agent now: promote trending non-portfolio tickers. Returns the fresh list. */
	@PostMapping("/discover")
	public List<WatchlistView> discover(HttpServletRequest request) {
		users.requireAdmin(request); // S-C1: rewrites the shared discovered set
		discovery.discover();
		return list();
	}

	@PostMapping
	public ResponseEntity<WatchlistView> add(@RequestBody AddRequest req) {
		String ticker = req.ticker() == null ? "" : req.ticker().trim().toUpperCase();
		if (ticker.isBlank()) {
			return ResponseEntity.badRequest().build();
		}
		Long me = CurrentUserContext.get();
		WatchlistEntry entry = repo.findByTickerAndUserId(ticker, me)
				.orElseGet(() -> repo.save(WatchlistEntry.manualFor(me, ticker, req.note())));
		return ResponseEntity.status(HttpStatus.CREATED).body(WatchlistView.from(entry));
	}

	/** S-C1: removes your own pick; an admin may also drop a discovered entry. Anyone else's pick is untouched. */
	@DeleteMapping("/{ticker}")
	public ResponseEntity<Void> remove(@PathVariable String ticker, HttpServletRequest request) {
		String t = ticker.trim().toUpperCase();
		if (repo.deleteOwn(t, CurrentUserContext.get()) > 0) {
			return ResponseEntity.noContent().build();
		}
		if (repo.findByTickerAndUserIdIsNull(t).isPresent()) {
			users.requireAdmin(request);
			repo.deleteDiscovered(t);
			return ResponseEntity.noContent().build();
		}
		return ResponseEntity.notFound().build();
	}

	public record AddRequest(@NotBlank String ticker, String note) {
	}

	public record WatchlistView(String ticker, String source, String note, boolean active, Instant addedAt,
			Instant expiresAt) {

		static WatchlistView from(WatchlistEntry e) {
			return new WatchlistView(e.getTicker(), e.getSource(), e.getNote(), e.isActive(), e.getAddedAt(),
					e.getExpiresAt());
		}
	}
}
