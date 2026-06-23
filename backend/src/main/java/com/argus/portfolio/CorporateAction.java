package com.argus.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A corporate action recorded against a holding (Story 3.3, FR-1c). Unambiguous actions are
 * auto-applied (status {@code applied}); ambiguous ones stay {@code pending} (🟡) for manual
 * confirmation and touch no position until confirmed. {@code type} is the lowercase
 * {@link CorporateActionType#code()}.
 */
@Entity
@Table(name = "corporate_actions")
public class CorporateAction {

	public static final String PENDING = "pending";
	public static final String APPLIED = "applied";
	public static final String DISMISSED = "dismissed";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String ticker;

	@Column(name = "position_id")
	private Long positionId;

	@Column(nullable = false)
	private String type;

	private BigDecimal ratio;

	@Column(name = "new_ticker")
	private String newTicker;

	@Column(name = "ex_date")
	private LocalDate exDate;

	@Column(nullable = false)
	private String status = PENDING;

	private String note;

	@Column(nullable = false)
	private String source = "manual";

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "applied_at")
	private Instant appliedAt;

	protected CorporateAction() {
		// JPA
	}

	public CorporateAction(String ticker, Long positionId, CorporateActionType type, BigDecimal ratio,
			String newTicker, LocalDate exDate, String note, String source) {
		this.ticker = ticker;
		this.positionId = positionId;
		this.type = type.code();
		this.ratio = ratio;
		this.newTicker = newTicker;
		this.exDate = exDate;
		this.note = note;
		this.source = source != null ? source : "manual";
	}

	public void markApplied(Long positionId) {
		this.positionId = positionId;
		this.status = APPLIED;
		this.appliedAt = Instant.now();
	}

	public void markDismissed() {
		this.status = DISMISSED;
	}

	public CorporateActionType typeEnum() {
		return CorporateActionType.fromCode(type);
	}

	public Long getId() {
		return id;
	}

	public String getTicker() {
		return ticker;
	}

	public Long getPositionId() {
		return positionId;
	}

	public String getType() {
		return type;
	}

	public BigDecimal getRatio() {
		return ratio;
	}

	public String getNewTicker() {
		return newTicker;
	}

	public LocalDate getExDate() {
		return exDate;
	}

	public String getStatus() {
		return status;
	}

	public String getNote() {
		return note;
	}

	public String getSource() {
		return source;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getAppliedAt() {
		return appliedAt;
	}
}
