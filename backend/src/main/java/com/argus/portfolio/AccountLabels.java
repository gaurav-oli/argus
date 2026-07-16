package com.argus.portfolio;

/**
 * Derives a friendly display name, currency, account type and short id from a stored account label
 * like {@code "687WQD-7 USD RRSP"} or {@code "687WK3-A CAD Cash"} — the label the LLM parser emits
 * and that stays the import reconcile key. Deterministic, no LLM. Display format is
 * {@code "{Type} ({CUR}: {last4})"} — e.g. {@code "RRSP (USD: WQD7)"}, {@code "Cash Account (CAD: WK3A)"}
 * — where {@code last4} is the last four alphanumerics of the account id. Falls back to the raw
 * label when it doesn't match the expected shape.
 */
final class AccountLabels {

	private AccountLabels() {
	}

	/** Parsed pieces of an account label; {@code displayName} is never null (falls back to the raw label). */
	record Parsed(String displayName, String currency, String accountType, String last4) {
	}

	static Parsed parse(String label) {
		if (label == null || label.isBlank()) {
			return new Parsed(label == null ? "" : label, null, null, null);
		}
		String[] tokens = label.trim().split("\\s+");
		String id = tokens[0];
		String currency = null;
		StringBuilder typeWords = new StringBuilder();
		for (int i = 1; i < tokens.length; i++) {
			String t = tokens[i];
			if (currency == null && (t.equalsIgnoreCase("CAD") || t.equalsIgnoreCase("USD"))) {
				currency = t.toUpperCase();
			}
			else {
				if (typeWords.length() > 0) {
					typeWords.append(' ');
				}
				typeWords.append(t);
			}
		}
		String last4 = last4(id);
		String typeName = typeName(typeWords.toString());
		if (currency == null || last4 == null) {
			return new Parsed(label.trim(), currency, typeName, last4);
		}
		return new Parsed(typeName + " (" + currency + ": " + last4 + ")", currency, typeName, last4);
	}

	private static String typeName(String rawType) {
		String t = rawType == null ? "" : rawType.trim();
		if (t.isEmpty()) {
			return "Account";
		}
		if (t.toLowerCase().contains("cash")) {
			return "Cash Account";
		}
		return t; // RRSP, TFSA, Family RESP, RESP, …
	}

	/**
	 * Normalize a raw account-type string (from a label or a statement header) to a canonical grouping
	 * key so the same registration groups across banks regardless of wording — e.g. "Cash Account",
	 * "Cdn. Dollar", "Non-Registered" → {@code Cash}; "Family RESP" → {@code RESP}; a corporate/business
	 * account → {@code Corporate}. Returns null for null/blank input; passes through an unrecognized
	 * (already-clean) type unchanged so nothing is silently dropped.
	 */
	static String canonicalType(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String t = raw.trim().toLowerCase();
		if (t.contains("tfsa")) {
			return "TFSA";
		}
		if (t.contains("rrif")) {
			return "RRIF";
		}
		if (t.contains("rrsp") || t.contains("retirement savings")) {
			return "RRSP";
		}
		if (t.contains("resp")) {
			return "RESP";
		}
		if (t.contains("lira") || t.contains("locked-in") || t.contains("locked in")) {
			return "LIRA";
		}
		if (t.contains("margin")) {
			return "Margin";
		}
		if (t.contains("corp") || t.contains("business") || t.contains(" inc") || t.endsWith(" inc")
				|| t.contains(" ltd") || t.contains("limited") || t.contains("incorporat")) {
			return "Corporate";
		}
		if (t.contains("cash") || t.contains("non-registered") || t.contains("non registered")
				|| t.contains("dollar")) {
			return "Cash";
		}
		return raw.trim(); // already a clean type we don't specifically map — keep as-is
	}

	/**
	 * Currency-agnostic key for an account label — the label with any standalone {@code CAD}/{@code USD}
	 * token dropped, uppercased and whitespace-collapsed. Two currency SIDES of one account share a base
	 * key (RBC splits an account into "64079 CAD RRSP" + "64079 USD RRSP" — both → {@code "64079 RRSP"}),
	 * while genuinely separate accounts whose ids differ (e.g. NBDB "687WK3-A CAD Cash" vs
	 * "687WK3-B USD Cash") do not. Used to detect a currency side missing from a re-import.
	 */
	static String baseKey(String label) {
		if (label == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (String t : label.trim().split("\\s+")) {
			if (t.equalsIgnoreCase("CAD") || t.equalsIgnoreCase("USD")) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(t.toUpperCase());
		}
		return sb.toString();
	}

	/** Last four alphanumeric characters of the account id, e.g. {@code 687WQD-7 → WQD7}. */
	private static String last4(String id) {
		if (id == null) {
			return null;
		}
		String alnum = id.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
		if (alnum.length() < 4) {
			return alnum.isEmpty() ? null : alnum;
		}
		return alnum.substring(alnum.length() - 4);
	}
}
