-- Breaking-alert curation: a Gemma-written plain-English summary (what happened / why it matters /
-- market impact + a beginner glossary), the same 3-paragraph shape news_card uses, plus dedup and
-- read-tracking so the in-app carousel doesn't repeat the same underlying story or an already-read
-- card. Unlike news_card (ephemeral suggestions, hard-deleted on "done"), breaking_alert is the
-- documented permanent audit trail of what was pushed — so "done" here is a soft `read` flag, never
-- a delete. `article_id` lets the curation pass look up the original article's snippet for the
-- summary prompt; nullable since it's not backfilled onto existing rows.
ALTER TABLE breaking_alert
    ADD COLUMN article_id   bigint REFERENCES news_articles (id) ON DELETE SET NULL,
    ADD COLUMN summary      text,                              -- Gemma paragraph; NULL = pending generation
    ADD COLUMN generated_at timestamptz,
    ADD COLUMN fallback     boolean NOT NULL DEFAULT false,     -- true when the model call failed
    ADD COLUMN duplicate    boolean NOT NULL DEFAULT false,     -- true when Gemma flagged it as the same story as an earlier alert
    ADD COLUMN read         boolean NOT NULL DEFAULT false;     -- "Done Reading" — soft dismiss, keeps history

-- Curation's "next to process" query and the carousel's "ready to read" query.
CREATE INDEX breaking_alert_pending_idx ON breaking_alert (created_at ASC) WHERE summary IS NULL AND duplicate = false;
CREATE INDEX breaking_alert_ready_idx ON breaking_alert (created_at DESC) WHERE summary IS NOT NULL AND duplicate = false AND read = false;
