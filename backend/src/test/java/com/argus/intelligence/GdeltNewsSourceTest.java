package com.argus.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GdeltNewsSourceTest {

	@Test
	void firstRunReadsOnlyTheLatestFile() {
		assertThat(GdeltNewsSource.stampsToRead(null, "20261015020000", 4)).containsExactly("20261015020000");
	}

	@Test
	void readsEveryMissedSlotOldestFirst() {
		assertThat(GdeltNewsSource.stampsToRead("20261015013000", "20261015020000", 4))
				.containsExactly("20261015014500", "20261015020000");
	}

	@Test
	void aLongGapCatchesUpOnlyTheNewestFiles() {
		assertThat(GdeltNewsSource.stampsToRead("20261014120000", "20261015020000", 4))
				.containsExactly("20261015011500", "20261015013000", "20261015014500", "20261015020000");
	}

	@Test
	void keepsTopicHeadlinesOncePerStoryMarketsFirstAndCapped() throws IOException {
		String gkg = String.join("\n",
				row("https://a.example/1", "Russia says ceasefire talks stall"),
				row("https://b.example/1", "Asian stocks slide after hot US inflation data"),
				row("https://c.example/1", "Asian Stocks Slide After Hot US Inflation Data!"), // syndicated copy
				row("https://d.example/1", "Local bakery wins county prize"), // off topic
				row("https://e.example/1", "Civil War ship modelled in 3D"), // history, not news
				row("https://f.example/1", "Dollar jumps on Fed rate hike bets &amp; yields"),
				row("https://g.example/1", "US lifts sanctions on Venezuela official"),
				"too\tfew\tcolumns");

		List<RawArticle> picked = GdeltNewsSource.pick(new BufferedReader(new StringReader(gkg)), 3);

		assertThat(picked).extracting(RawArticle::headline).containsExactly(
				"Asian stocks slide after hot US inflation data",
				"Dollar jumps on Fed rate hike bets & yields",
				"Russia says ceasefire talks stall");
		assertThat(picked.get(0).source()).isEqualTo("gdelt");
		assertThat(picked.get(0).externalId()).isEqualTo("https://b.example/1");
		assertThat(picked.get(0).publishedAt()).isEqualTo(Instant.parse("2026-10-15T02:00:00Z"));
	}

	@Test
	void dropsRowsWithoutATitleOrDate() throws IOException {
		String[] noTitle = row("https://a.example/1", "Stocks rally").split("\t", -1);
		noTitle[26] = "<PAGE_LINKS>x</PAGE_LINKS>";
		String[] badDate = row("https://b.example/1", "Stocks rally again").split("\t", -1);
		badDate[1] = "yesterday";
		String gkg = String.join("\t", noTitle) + "\n" + String.join("\t", badDate);

		assertThat(GdeltNewsSource.pick(new BufferedReader(new StringReader(gkg)), 10)).isEmpty();
	}

	/** A 27-column GKG 2.1 row: DATE in column 1, URL in 4, PAGE_TITLE inside the extras XML in 26. */
	private static String row(String url, String title) {
		String[] cols = new String[27];
		java.util.Arrays.fill(cols, "");
		cols[0] = "20261015020000-1";
		cols[1] = "20261015020000";
		cols[4] = url;
		cols[26] = "<PAGE_LINKS>x</PAGE_LINKS><PAGE_TITLE>" + title + "</PAGE_TITLE>";
		return String.join("\t", cols);
	}
}
