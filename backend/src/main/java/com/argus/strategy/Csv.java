package com.argus.strategy;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal RFC-4180 CSV reader. The strategy corpus needs one because its definition column contains the
 * papers' own prose — commas, quoted phrases and newlines inside a single field — which a {@code split(",")}
 * would shred into nonsense. Pure and dependency-free; no CSV library is on the classpath for one import path.
 */
public final class Csv {

	private Csv() {
	}

	/** Parse a whole CSV document into rows of raw fields. Quotes are unwrapped, {@code ""} becomes {@code "}. */
	public static List<List<String>> parse(Reader reader) {
		List<List<String>> rows = new ArrayList<>();
		List<String> row = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean inQuotes = false;
		boolean fieldStarted = false;
		try {
			int ch;
			while ((ch = reader.read()) != -1) {
				char c = (char) ch;
				if (inQuotes) {
					if (c == '"') {
						int next = reader.read();
						if (next == '"') {
							field.append('"'); // an escaped quote inside a quoted field
						}
						else {
							inQuotes = false;
							if (next == -1) {
								break;
							}
							// re-handle the character that ended the quoted section
							if (next == ',') {
								row.add(field.toString());
								field.setLength(0);
								fieldStarted = false;
							}
							else if (next == '\n' || next == '\r') {
								row.add(field.toString());
								field.setLength(0);
								fieldStarted = false;
								rows.add(row);
								row = new ArrayList<>();
							}
							else {
								field.append((char) next);
							}
						}
					}
					else {
						field.append(c);
					}
					continue;
				}
				switch (c) {
					case '"' -> {
						if (!fieldStarted && field.isEmpty()) {
							inQuotes = true;
						}
						else {
							field.append(c);
						}
						fieldStarted = true;
					}
					case ',' -> {
						row.add(field.toString());
						field.setLength(0);
						fieldStarted = false;
					}
					case '\r' -> {
						// swallowed; the \n that follows (or a bare \r) ends the record
					}
					case '\n' -> {
						row.add(field.toString());
						field.setLength(0);
						fieldStarted = false;
						rows.add(row);
						row = new ArrayList<>();
					}
					default -> {
						field.append(c);
						fieldStarted = true;
					}
				}
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not read CSV: " + ex.getMessage(), ex);
		}
		if (!field.isEmpty() || !row.isEmpty()) {
			row.add(field.toString());
			rows.add(row);
		}
		return rows;
	}
}
