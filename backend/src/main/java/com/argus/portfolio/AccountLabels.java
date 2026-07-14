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
