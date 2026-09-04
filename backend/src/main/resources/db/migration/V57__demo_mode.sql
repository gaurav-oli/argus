-- Demo Mode (FR: hide Portfolio + mask $ amounts for showing the product to someone else) — one
-- more column on the shared single-row app_settings table (V4), exactly as its own comment
-- anticipated: "future stories add columns to this row."
ALTER TABLE app_settings ADD COLUMN demo_mode boolean NOT NULL DEFAULT false;
