package com.argus.filings;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTML → plain text for SEC documents: drop scripts/styles/tags, decode entities, collapse whitespace. Pure. */
public final class HtmlText {

	private static final Pattern BLOCKS = Pattern.compile("(?is)<(script|style|head)[^>]*>.*?</\\1>");
	private static final Pattern BREAKS = Pattern.compile("(?i)</(p|div|tr|li|h[1-6]|table)>|<br\\s*/?>");
	private static final Pattern CELLS = Pattern.compile("(?i)</t[dh]>");
	private static final Pattern TAGS = Pattern.compile("(?s)<[^>]+>");
	private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
	private static final Pattern SPACES = Pattern.compile("[ \\t\\u00a0\\u2007\\u202f]+");

	private HtmlText() {
	}

	public static String toText(String html) {
		if (html == null || html.isEmpty()) {
			return "";
		}
		String t = BLOCKS.matcher(html).replaceAll(" ");
		t = BREAKS.matcher(t).replaceAll("\n");
		t = CELLS.matcher(t).replaceAll(" ");   // table cells need a separator...
		t = TAGS.matcher(t).replaceAll("");     // ...but inline tags (<b>, <span>) must vanish without leaving "billion ," gaps
		t = decode(t);
		t = SPACES.matcher(t).replaceAll(" ");
		t = t.replaceAll("[ ]*\\n[ \\n]*", "\n").strip();
		return t;
	}

	static String decode(String s) {
		String t = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
				.replace("&apos;", "'").replace("&rsquo;", "’").replace("&lsquo;", "‘").replace("&ldquo;", "“")
				.replace("&rdquo;", "”").replace("&ndash;", "–").replace("&mdash;", "—");
		Matcher m = NUMERIC_ENTITY.matcher(t);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			try {
				int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
				m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
			}
			catch (RuntimeException ex) {
				m.appendReplacement(sb, " ");
			}
		}
		m.appendTail(sb);
		return sb.toString();
	}
}
