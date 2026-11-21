-- Wrong passwords in a row for an account, and when the last one was typed. A few pass,
-- then each further try waits longer. In the database rather than in memory, so that the
-- count holds across a restart and across several copies of the API. A sign-in with the
-- right password and a new password both set it back to zero.
alter table users add column failed_logins integer default 0 not null;
alter table users add column last_failed_login_at timestamp with time zone;
