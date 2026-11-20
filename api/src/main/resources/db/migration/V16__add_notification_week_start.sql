-- The week a timesheet notification is about, so the app can open that week.
alter table notifications add column week_start date;
