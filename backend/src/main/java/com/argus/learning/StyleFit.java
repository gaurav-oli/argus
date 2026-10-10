package com.argus.learning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * S-B6 — which playbooks win on which kinds of names. A <em>playbook</em> is the evidence family a call led
 * with ({@code lead=NEWS}, {@code lead=DEEP}, {@code lead=TECHNICAL}, ...); a <em>style bucket</em> is one value
 * of one style dimension of the name at entry, read from the same feature tokens the trade was fingerprinted
 * with (S-B4): volatility, sector, price band, market regime, and trend. Pure.
 *
 * <p>Sample-size guard: a family × bucket cell with fewer than {@value #MIN_SAMPLE} closed trades is reported
 * but marked not {@code enough}, and never moves a trade. A new trade's size is tilted only when at least one
 * of its buckets has enough sample for its playbook: by the average gap between the playbook's win rate in
 * those buckets and its win rate overall — at least {@value #TILT_POINTS} points better → ×{@value #UP};
 * that much worse → ×{@value #DOWN}.
 */
public final class StyleFit {

	static final int MIN_SAMPLE = 10;
	static final int TILT_POINTS = 10;
	static final double UP = 1.25;
	static final double DOWN = 0.75;

	/** The style dimensions, by token key, in display order. */
	public static final Map<String, String> DIMENSIONS = dims();

	private static Map<String, String> dims() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("vol", "Volatility");
		m.put("sector", "Sector");
		m.put("price", "Price band");
		m.put("regime", "Market regime");
		m.put("trend", "Trend");
		return java.util.Collections.unmodifiableMap(m);
	}

	private StyleFit() {
	}

	/** One closed paper trade: its setup tokens and how it ended. */
	public record ClosedTrade(Set<String> tokens, boolean won, BigDecimal returnPct) {
	}

	/** One playbook × style-bucket cell. */
	public record Cell(String family, String dimension, String bucket, int trades, int wins, Integer winRatePct,
			BigDecimal avgReturnPct, boolean enough) {
	}

	/** One playbook's overall record. */
	public record Family(String family, int trades, int wins, Integer winRatePct) {
	}

	public record Matrix(List<Family> families, List<Cell> cells, int minSample) {
	}

	/** The playbook a trade led with, or null when its tokens don't say. */
	public static String family(Set<String> tokens) {
		if (tokens == null) return null;
		return tokens.stream().filter(t -> t.startsWith("lead=")).map(t -> t.substring(5)).findFirst().orElse(null);
	}

	/** The value of one style dimension in a token set, or null. */
	public static String bucket(Set<String> tokens, String dimension) {
		if (tokens == null) return null;
		String prefix = dimension + "=";
		return tokens.stream().filter(t -> t.startsWith(prefix)).map(t -> t.substring(prefix.length())).findFirst().orElse(null);
	}

	public static Matrix matrix(List<ClosedTrade> trades) {
		Map<String, List<ClosedTrade>> byFamily = new LinkedHashMap<>();
		Map<String, List<ClosedTrade>> byCell = new LinkedHashMap<>();
		for (ClosedTrade t : trades) {
			String fam = family(t.tokens());
			if (fam == null) continue;
			byFamily.computeIfAbsent(fam, k -> new ArrayList<>()).add(t);
			for (String dim : DIMENSIONS.keySet()) {
				String b = bucket(t.tokens(), dim);
				if (b != null) byCell.computeIfAbsent(fam + "|" + dim + "|" + b, k -> new ArrayList<>()).add(t);
			}
		}
		List<Family> families = byFamily.entrySet().stream()
				.map(e -> new Family(e.getKey(), e.getValue().size(), wins(e.getValue()), rate(e.getValue())))
				.sorted(Comparator.comparingInt(Family::trades).reversed().thenComparing(Family::family)).toList();
		List<Cell> cells = new ArrayList<>();
		for (var e : byCell.entrySet()) {
			String[] k = e.getKey().split("\\|", 3);
			List<ClosedTrade> ts = e.getValue();
			cells.add(new Cell(k[0], k[1], k[2], ts.size(), wins(ts), rate(ts), avg(ts), ts.size() >= MIN_SAMPLE));
		}
		cells.sort(Comparator.comparing(Cell::dimension).thenComparing(Cell::family).thenComparing(Cell::bucket));
		return new Matrix(families, cells, MIN_SAMPLE);
	}

	/**
	 * @param multiplier size multiplier for the new trade (1.0 = no tilt)
	 * @param note       why, e.g. "NEWS-led calls win 68% on vol=high names vs 52% overall (14 trades) → ×1.25"
	 */
	public record Fit(String family, double multiplier, Integer gapPoints, String note) {
		public static Fit none(String family, String why) {
			return new Fit(family, 1.0, null, why);
		}
	}

	public static Fit fitFor(Set<String> tokens, Matrix m) {
		String fam = family(tokens);
		if (fam == null) return Fit.none(null, "No style fit — the call has no lead playbook.");
		Family overall = m.families().stream().filter(f -> f.family().equals(fam)).findFirst().orElse(null);
		if (overall == null || overall.winRatePct() == null) {
			return Fit.none(fam, "No style fit yet — no closed %s-led trades.".formatted(fam));
		}
		List<Cell> usable = new ArrayList<>();
		for (String dim : DIMENSIONS.keySet()) {
			String b = bucket(tokens, dim);
			if (b == null) continue;
			m.cells().stream().filter(c -> c.enough() && c.family().equals(fam) && c.dimension().equals(dim) && c.bucket().equals(b))
					.findFirst().ifPresent(usable::add);
		}
		if (usable.isEmpty()) {
			return Fit.none(fam, "No style fit yet — fewer than %d closed %s-led trades in this name's buckets."
					.formatted(MIN_SAMPLE, fam));
		}
		int gap = (int) Math.round(usable.stream().mapToInt(c -> c.winRatePct() - overall.winRatePct()).average().orElse(0));
		double mult = gap >= TILT_POINTS ? UP : gap <= -TILT_POINTS ? DOWN : 1.0;
		Cell strongest = usable.stream().max(Comparator.comparingInt(c -> Math.abs(c.winRatePct() - overall.winRatePct()))).orElseThrow();
		String evidence = "%s-led calls win %d%% on %s=%s names vs %d%% overall (%d trades)".formatted(fam, strongest.winRatePct(),
				strongest.dimension(), strongest.bucket(), overall.winRatePct(), strongest.trades());
		String verdict = mult > 1 ? " → good fit, ×" + UP : mult < 1 ? " → poor fit, ×" + DOWN : " → no tilt";
		String avgGap = usable.size() > 1 ? " (avg gap %+d pts over %d buckets)".formatted(gap, usable.size()) : "";
		return new Fit(fam, mult, gap, evidence + avgGap + verdict + ".");
	}

	private static int wins(List<ClosedTrade> ts) {
		return (int) ts.stream().filter(ClosedTrade::won).count();
	}

	private static Integer rate(List<ClosedTrade> ts) {
		return ts.isEmpty() ? null : Math.round(100f * wins(ts) / ts.size());
	}

	private static BigDecimal avg(List<ClosedTrade> ts) {
		List<BigDecimal> r = ts.stream().map(ClosedTrade::returnPct).filter(Objects::nonNull).toList();
		return r.isEmpty() ? null
				: r.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(r.size()), 2, RoundingMode.HALF_UP);
	}
}
