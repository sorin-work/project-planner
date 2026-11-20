-- The highest budget threshold already announced for a project (0, 80 or 100), kept apart
-- for hours and for money. An alert goes out once per threshold, and with one column the
-- hours reaching 100 would swallow the later message that the money passed 80.
alter table projects add column hours_alert_pct integer not null default 0;
alter table projects add column amount_alert_pct integer not null default 0;
