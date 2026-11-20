-- Links get a type: finish-to-start (every link so far), start-to-start,
-- finish-to-finish and start-to-finish. And a lag may now be negative, which is a
-- lead: "QA starts two days before development ends".
alter table task_dependencies add column dependency_type varchar(2) not null default 'FS';
alter table task_dependencies add constraint ck_task_dependencies_type check (dependency_type in ('FS', 'SS', 'FF', 'SF'));
alter table task_dependencies drop constraint ck_task_dependencies_lag;
alter table task_dependencies add constraint ck_task_dependencies_lag check (lag_days between -365 and 365);
