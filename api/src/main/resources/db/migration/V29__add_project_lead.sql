-- The person who leads a project: an owner, a manager or a member of its organisation, or
-- nobody. With a lead, the budget alerts of the project go to the lead and the owners
-- instead of every manager. Removing a membership clears it, in the same transaction, so
-- that it never points at somebody who is no longer in the organisation.
alter table projects add column lead_id bigint references users (id) on delete set null;
create index ix_projects_lead on projects (lead_id);
