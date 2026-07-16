-- Transparency: mark AI-generated content that was actually produced by the deterministic FALLBACK
-- (the local model call failed or returned unusable output) rather than the model. The fallback text
-- reuses real underlying data so it's never "fake", but the UI should tell the user it wasn't
-- model-written. Defaults false (the normal model-generated path); back-filled rows are treated as
-- model-generated, which is the safe assumption for anything already shown.
ALTER TABLE briefings ADD COLUMN fallback boolean NOT NULL DEFAULT false;
ALTER TABLE news_card ADD COLUMN fallback boolean NOT NULL DEFAULT false;
