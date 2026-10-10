package com.argus.learning;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * S-B6 — the playbook × style matrix over closed paper trades, and the size tilt a new entry gets from it.
 * Trades are read with the same fingerprint the pattern library uses (stored at entry, else the
 * recommendation's features). Fails open: any error means no tilt.
 */
@Service
public class StyleFitService {

	private static final Logger log = LoggerFactory.getLogger(StyleFitService.class);
	static final int LOOKBACK = 2000;

	private final JdbcTemplate jdbc;

	public StyleFitService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public StyleFit.Matrix matrix() {
		return StyleFit.matrix(jdbc.query("""
				select t.won, t.return_pct, coalesce(t.setup_fingerprint, r.features) as fp
				  from simulated_trades t
				  left join recommendations r on r.id = t.recommendation_id
				 where t.status = 'CLOSED' and t.won is not null
				   and coalesce(t.setup_fingerprint, r.features) is not null
				 order by t.closed_at desc
				 limit ?
				""", (rs, i) -> new StyleFit.ClosedTrade(FeatureTokens.fromJson(rs.getString("fp")), rs.getBoolean("won"),
				rs.getBigDecimal("return_pct")), LOOKBACK));
	}

	/** The tilt for a new entry with these tokens; {@link StyleFit.Fit#none} on any failure. */
	public StyleFit.Fit fitFor(Set<String> tokens) {
		try {
			return StyleFit.fitFor(tokens, matrix());
		}
		catch (RuntimeException ex) {
			log.warn("Style fit: lookup failed — no tilt: {}", ex.getMessage());
			return StyleFit.Fit.none(StyleFit.family(tokens), "No style fit — the lookup failed.");
		}
	}

	/** The report for the Agents page: dimension labels plus the matrix. */
	public record Report(java.util.Map<String, String> dimensions, List<StyleFit.Family> families, List<StyleFit.Cell> cells,
			int minSample) {
	}

	public Report report() {
		StyleFit.Matrix m = matrix();
		return new Report(StyleFit.DIMENSIONS, m.families(), m.cells(), m.minSample());
	}
}
