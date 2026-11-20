-- A second project that an activity entry is about, such as the project a plan was
-- copied from. A plain value like project_id and task_id, so the entry survives it.
-- The entry's text never names that project: who may see its name is decided when
-- the entry is read.
alter table activity_log add column related_project_id bigint;
