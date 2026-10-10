package com.argus.portfolio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** S-A6 / M4: statement upload requires {@code %PDF} magic bytes. */
class PdfMagicTest {

	@Test
	void acceptsPdfMagic() {
		assertTrue(PortfolioImportController.looksLikePdf("%PDF-1.4 rest".getBytes()));
		assertTrue(PortfolioImportController.looksLikePdf(PdfFixtures.withLines(java.util.List.of("AAPL 1"))));
	}

	@Test
	void rejectsMissingOrWrongMagic() {
		assertFalse(PortfolioImportController.looksLikePdf(null));
		assertFalse(PortfolioImportController.looksLikePdf(new byte[0]));
		assertFalse(PortfolioImportController.looksLikePdf("PDF".getBytes()));
		assertFalse(PortfolioImportController.looksLikePdf("not-a-pdf".getBytes()));
	}
}
