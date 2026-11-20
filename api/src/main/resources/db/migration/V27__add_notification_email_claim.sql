-- A notification that a run of the mail job has taken on, until this time. Another run, on
-- another instance or after a restart, leaves it alone until then, and takes it on again
-- after, so that a run that died in the middle of a mail does not lose it.
alter table notifications add column email_claimed_until timestamp with time zone;
