/** Split "<paragraph(s)> … KEY TERMS: <term — def> …" into the plain paragraphs (what happened / why
 * it matters / market impact — rendered as separate blocks whether or not the model labels them) and
 * a beginner glossary. Shared by every Gemma-summarized carousel (Market News, Breaking Alerts). */
export function parseSummary(raw: string): { paragraphs: string[]; terms: { term: string; def: string }[] } {
  const marker = raw.search(/key\s*terms\s*:/i);
  const body = marker === -1 ? raw.trim() : raw.slice(0, marker).trim();
  const paragraphs = body.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
  if (marker === -1) return { paragraphs, terms: [] };
  const rest = raw.slice(marker).replace(/^key\s*terms\s*:/i, "").trim();
  if (/^none\.?$/i.test(rest)) return { paragraphs, terms: [] };
  const terms = rest
    .split(/\n+/)
    .map((line) => line.replace(/^\s*[-*•\d.]+\s*/, "").trim())
    .filter((line) => line.length > 0 && !/^none\.?$/i.test(line))
    .map((line) => {
      const parts = line.split(/\s+[—–-]\s+/);
      const term = parts[0].trim();
      const def = line.slice(term.length).replace(/^\s*[—–-]\s*/, "").trim();
      return { term, def };
    })
    .filter((t) => t.term.length > 0);
  return { paragraphs, terms };
}
