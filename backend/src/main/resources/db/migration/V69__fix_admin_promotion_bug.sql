-- Bug fix: ARGUS_ADMIN_EMAIL was read as argus.google-oauth.admin-email (wrong Spring property
-- path — AdminProperties now owns it correctly, under argus.admin.email), so the admin's first
-- real Google sign-in never actually got promoted. One-time data fix for the row already affected;
-- the code bug itself is fixed in the same commit, so this never needs to run again.
update app_user set is_admin = true where lower(email) = 'gauravoli16@gmail.com';
