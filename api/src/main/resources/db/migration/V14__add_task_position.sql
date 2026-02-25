-- Order of cards within a board column.
alter table tasks add column position integer not null default 0;
