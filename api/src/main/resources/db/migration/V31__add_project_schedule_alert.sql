-- What the schedule alerts of a project last announced, like hours_alert_pct for the
-- budget. off_track_announced is set when the project was announced off track, and only
-- going back on track clears it, so a project on the edge is announced once. late_end_announced
-- is the forecast end that was announced as past the end date, cleared when it no longer is.
alter table projects add column off_track_announced boolean default false not null;
alter table projects add column late_end_announced date;

-- The day the daily pass over an organisation's active projects last ran, so that one
-- instance of the API takes it on once a day.
alter table organisations add column schedule_checked_on date;
