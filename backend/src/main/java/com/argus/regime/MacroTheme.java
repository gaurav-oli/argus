package com.argus.regime;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The kinds of macro/political story that move markets, each with a per-{@link Sector} sensitivity.
 * A sensitivity is the sector's beta to the story's <em>market-wide</em> sentiment: positive moves with
 * it (bad macro news → this sector falls), negative moves against it (a supply-shock headline that is
 * bad for the market is good for energy names and gold), near zero barely cares. This replaces the old
 * behaviour of stamping one identical macro number on every ticker — the reason a tariff headline hit
 * a utility and a chipmaker the same, and a yield spike could not favour banks over growth stocks.
 *
 * <p>Deterministic keyword matching, same philosophy as {@code MacroRelevanceTagger}: no LLM decides
 * exposure. The matrices are judgement calls grounded in standard sector sensitivities; the
 * reliability/tuning loop will judge whether they earn weight.
 */
public enum MacroTheme {

	RATES_YIELDS(0.5, "rates & yields",
			"treasury yield", "yields", "bond sell-off", "bond selloff", "rate hike", "rate cut", "rate-hike",
			"federal reserve", "fomc", "interest rate", "inflation", "powell", "warsh", "jackson hole",
			"bank of canada", "bank of japan", "central bank", "10-year", "boj", "ecb") {
		@Override
		Map<Sector, Double> overrides() {
			return map(Sector.TECHNOLOGY, 0.9, Sector.SEMICONDUCTORS, 0.9, Sector.AI_INFRA, 1.0,
					Sector.COMMUNICATION, 0.7, Sector.CONSUMER_DISCRETIONARY, 0.8, Sector.AUTOS_EV, 0.9,
					Sector.BROAD_GROWTH, 0.9, Sector.BROAD_MARKET, 0.7, Sector.UTILITIES, 0.6,
					Sector.REAL_ESTATE, 1.0, Sector.FINANCIALS, -0.3, Sector.GOLD, 0.6, Sector.ENERGY, 0.2,
					Sector.CONSUMER_STAPLES, 0.3, Sector.HEALTHCARE, 0.4, Sector.CANADIAN_EQUITY, 0.4);
		}
	},
	ENERGY_OIL(0.4, "oil & energy",
			"oil", "crude", "opec", "hormuz", "diesel", "gasoline", "strategic petroleum reserve", "refinery",
			"natural gas") {
		@Override
		Map<Sector, Double> overrides() {
			return map(Sector.ENERGY, -0.8, Sector.CANADIAN_EQUITY, -0.3, Sector.GOLD, -0.3,
					Sector.INDUSTRIALS_DEFENSE, 0.5, Sector.CONSUMER_DISCRETIONARY, 0.6, Sector.AUTOS_EV, 0.2,
					Sector.CONSUMER_STAPLES, 0.3, Sector.FINANCIALS, 0.4);
		}
	},
	TRADE_TARIFFS(0.5, "trade & tariffs",
			"tariff", "tariffs", "trade war", "trade deal", "trade talks", "trade truce", "embargo",
			"export ban", "export control", "export controls", "china", "beijing", "xi jinping", "trump-xi") {
		@Override
		Map<Sector, Double> overrides() {
			return map(Sector.SEMICONDUCTORS, 1.0, Sector.TECHNOLOGY, 0.8, Sector.AI_INFRA, 0.8,
					Sector.AUTOS_EV, 0.9, Sector.CONSUMER_DISCRETIONARY, 0.8, Sector.INDUSTRIALS_DEFENSE, 0.7,
					Sector.CANADIAN_EQUITY, 0.8, Sector.BROAD_MARKET, 0.6, Sector.ENERGY, 0.4,
					Sector.HEALTHCARE, 0.3, Sector.COMMUNICATION, 0.3, Sector.GOLD, -0.3, Sector.UTILITIES, 0.1,
					Sector.REAL_ESTATE, 0.2);
		}
	},
	GEOPOLITICS(0.6, "geopolitical conflict",
			"war", "invasion", "ceasefire", "iran", "israel", "ukraine", "russia", "taiwan", "nato", "coup",
			"missile", "sanction", "sanctions", "martial law", "hormuz") {
		@Override
		Map<Sector, Double> overrides() {
			return map(Sector.INDUSTRIALS_DEFENSE, -0.6, Sector.ENERGY, -0.5, Sector.GOLD, -0.8,
					Sector.UTILITIES, 0.2, Sector.CONSUMER_STAPLES, 0.3, Sector.HEALTHCARE, 0.3);
		}
	},
	AI_TECH_POLICY(0.1, "AI & chip policy",
			"ai chip", "chip stocks", "semiconductor", "nvidia", "data center", "ai slowdown", "ai safety",
			"antitrust", "huawei") {
		@Override
		Map<Sector, Double> overrides() {
			return map(Sector.SEMICONDUCTORS, 1.0, Sector.AI_INFRA, 1.0, Sector.TECHNOLOGY, 0.8,
					Sector.COMMUNICATION, 0.6, Sector.BROAD_GROWTH, 0.6, Sector.BROAD_MARKET, 0.3);
		}
	},
	FISCAL_POLITICS(0.5, "US politics & fiscal policy",
			"trump", "white house", "executive order", "government shutdown", "budget deficit", "congress",
			"senate", "big beautiful bill") {
		@Override
		Map<Sector, Double> overrides() {
			return map(Sector.GOLD, -0.3, Sector.UTILITIES, 0.3, Sector.HEALTHCARE, 0.4);
		}
	},
	CURRENCY_FX(0.3, "currency & FX",
			"dollar", "yen", "euro", "yuan", "renminbi", "sterling", "currency", "forex", "devaluation") {
		@Override
		Map<Sector, Double> overrides() {
			return map(Sector.TECHNOLOGY, 0.5, Sector.CONSUMER_STAPLES, 0.4, Sector.GOLD, -0.4,
					Sector.CANADIAN_EQUITY, 0.5);
		}
	},
	/** Macro-tagged by some other keyword but matching no specific theme — a mild, uniform market beta. */
	GENERAL(0.5, "macro", new String[0]) {
		@Override
		Map<Sector, Double> overrides() {
			return map(Sector.GOLD, -0.3);
		}
	};

	private final double defaultSensitivity;
	private final String label;
	private final Pattern pattern;
	private volatile Map<Sector, Double> resolved;

	MacroTheme(double defaultSensitivity, String label, String... keywords) {
		this.defaultSensitivity = defaultSensitivity;
		this.label = label;
		this.pattern = keywords.length == 0 ? null : Pattern.compile("\\b("
				+ String.join("|", java.util.Arrays.stream(keywords).map(Pattern::quote).toList()) + ")\\b",
				Pattern.CASE_INSENSITIVE);
	}

	abstract Map<Sector, Double> overrides();

	public String label() {
		return label;
	}

	/** This theme's beta for {@code sector} — see the class doc for the sign convention. */
	public double sensitivity(Sector sector) {
		Map<Sector, Double> r = resolved;
		if (r == null) {
			r = overrides();
			resolved = r;
		}
		return r.getOrDefault(sector, defaultSensitivity);
	}

	/** Themes a piece of text matches; {@link #GENERAL} when it matches none. */
	public static List<MacroTheme> classify(String text) {
		String t = text == null ? "" : text;
		List<MacroTheme> hits = java.util.Arrays.stream(values())
				.filter(m -> m.pattern != null && m.pattern.matcher(t).find()).toList();
		return hits.isEmpty() ? List.of(GENERAL) : hits;
	}

	/** Mean sensitivity of {@code sector} across every theme in {@code themes}. */
	public static double meanSensitivity(List<MacroTheme> themes, Sector sector) {
		return themes.stream().mapToDouble(m -> m.sensitivity(sector)).average().orElse(0.5);
	}

	private static Map<Sector, Double> map(Object... kv) {
		Map<Sector, Double> m = new EnumMap<>(Sector.class);
		for (int i = 0; i < kv.length; i += 2) {
			m.put((Sector) kv[i], (Double) kv[i + 1]);
		}
		return m;
	}
}
