-- A key the client makes up for a new entry. Sending the same create again with the
-- same key returns the entry that was made the first time instead of a second one.
-- Entries without a key are not affected: null is not equal to null.
alter table time_entries add column client_key varchar(64);
alter table time_entries add constraint uq_time_entries_client_key unique (organisation_id, user_id, client_key);
