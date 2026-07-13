package com.argus.watchlist;

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

	public WatchlistController(WatchlistRepository repo, DiscoveryService discovery) {
		this.repo = repo;
		this.discovery = discovery;
	}

	@GetMapping
	public List<WatchlistView> list() {
		return repo.findAllByOrderByAddedAtDesc().stream().map(WatchlistView::from).toList();
	}

	/** Run the auto-discovery agent now: promote trending non-portfolio tickers. Returns the fresh list. */
	@PostMapping("/discover")
	public List<WatchlistView> discover() {
		discovery.discover();
		return list();
	}

	@PostMapping
	public ResponseEntity<WatchlistView> add(@RequestBody AddRequest req) {
		String ticker = req.ticker() == null ? "" : req.ticker().trim().toUpperCase();
		if (ticker.isBlank()) {
			return ResponseEntity.badRequest().build();
		}
		WatchlistEntry entry = repo.findByTicker(ticker)
				.orElseGet(() -> repo.save(new WatchlistEntry(ticker, WatchlistEntry.Source.MANUAL, req.note(), null)));
		return ResponseEntity.status(HttpStatus.CREATED).body(WatchlistView.from(entry));
	}

	@DeleteMapping("/{ticker}")
	public ResponseEntity<Void> remove(@PathVariable String ticker) {
		repo.deleteByTicker(ticker.trim().toUpperCase());
		return ResponseEntity.noContent().build();
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
