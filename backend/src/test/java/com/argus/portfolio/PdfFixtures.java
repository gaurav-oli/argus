package com.argus.portfolio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

/** Builds in-memory statement PDFs for tests, so no binary fixtures live in the repo. */
final class PdfFixtures {

	private PdfFixtures() {
	}

	/** Render each string as its own line of text in a single-page PDF. */
	static byte[] withLines(List<String> lines) {
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage();
			doc.addPage(page);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				cs.beginText();
				cs.setFont(new PDType1Font(FontName.HELVETICA), 11);
				cs.setLeading(16);
				cs.newLineAtOffset(50, 720);
				for (String line : lines) {
					cs.showText(line);
					cs.newLine();
				}
				cs.endText();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to build fixture PDF", ex);
		}
	}
}
