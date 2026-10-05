-- V74__invite_email_tracking.sql — admin can send a real invite email (Resend) with a unique link
-- per person, and see whether they've opened it (visited the link) separately from whether they've
-- actually joined (which was already visible via app_user). token is the unique link id; null means
-- "not sent yet" for the two timestamp columns.

alter table invited_email add column token varchar(43);
alter table invited_email add column email_sent_at timestamptz;
alter table invited_email add column opened_at timestamptz;

create unique index idx_invited_email_token on invited_email (token) where token is not null;
