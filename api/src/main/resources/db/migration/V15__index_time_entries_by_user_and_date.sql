create index ix_time_entries_user_date on time_entries (user_id, entry_date);
create index ix_time_entries_org_date on time_entries (organisation_id, entry_date);
