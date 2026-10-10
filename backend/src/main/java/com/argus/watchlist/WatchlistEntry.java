package com.argus.watchlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A watchlist ticker — a name outside your portfolio that the agents still cover and Agent 5 recommends
 * on. {@code source} is MANUAL (you added it) or DISCOVERED (an auto-discovery agent promoted it, with an
 * {@code expiresAt}). Composed with holdings by {@link CompositeKnownUniverse}.
 */
@Entity
@Table(name = "watchlist")
public class WatchlistEntry {

	public enum Source {
		MANUAL, DISCOVERED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String ticker;

	@Column(nullable = false)
	private String source = Source.MANUAL.name();

	private String note;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "added_at", nullable = false)
	private Instant addedAt = Instant.now();

	@Column(name = "expires_at")
	private Instant expiresAt;

	/** S-C1: who added a MANUAL pick (each person sees and edits only their own); null for DISCOVERED entries. */
	@Column(name = "user_id")
	private Long userId;

	protected WatchlistEntry() {
		// JPA
	}

	public WatchlistEntry(String ticker, Source source, String note, Instant expiresAt) {
		this.ticker = ticker == null ? null : ticker.trim().toUpperCase();
		this.source = (source == null ? Source.MANUAL : source).name();
		this.note = note;
		this.expiresAt = expiresAt;
	}

	/** S-C1: a MANUAL pick owned by {@code userId}. */
	public static WatchlistEntry manualFor(Long userId, String ticker, String note) {
		WatchlistEntry e = new WatchlistEntry(ticker, Source.MANUAL, note, null);
		e.userId = userId;
		return e;
	}

	public Long getId() {
		return id;
	}

	public String getTicker() {
		return ticker;
	}

	public String getSource() {
		return source;
	}

	public String getNote() {
		return note;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getAddedAt() {
		return addedAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Long getUserId() {
		return userId;
	}
}
