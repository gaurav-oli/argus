create table app_user (
    id            bigserial primary key,
    google_sub    varchar(64)  not null unique,
    email         varchar(255) not null unique,
    name          varchar(255) not null,
    picture_url   text,
    is_admin      boolean      not null default false,
    created_at    timestamptz  not null default now(),
    last_login_at timestamptz,
    login_count   integer      not null default 0
);

-- The Google-account allowlist: only an email on this list may ever create an app_user row. The
-- admin's own email is seeded here so there's no chicken-and-egg problem (can't invite yourself
-- from inside an app you aren't yet allowed into) — GoogleAuthService promotes it to is_admin=true
-- on first login (ARGUS_ADMIN_EMAIL), this row only grants entry.
create table invited_email (
    email      varchar(255) primary key,
    invited_at timestamptz not null default now(),
    invited_by varchar(255)
);
insert into invited_email (email, invited_by) values ('gauravoli16@gmail.com', 'system');

-- One row per (user, day) — a lightweight, honest proxy for "time spent": the span between the
-- first and last authenticated request seen that day. Not true continuous attention, just the
-- cheapest signal that's actually real (no client-side tracking, nothing invented).
create table user_activity_day (
    user_id       bigint      not null references app_user (id) on delete cascade,
    activity_date date        not null,
    first_seen_at timestamptz not null,
    last_seen_at  timestamptz not null,
    primary key (user_id, activity_date)
);
