# Roadmap

Things I might add to project-planner. It already does what I built it for:
plan a project, log time against it and see whether it will land on time and
on budget. So nothing here is promised or scheduled. These are ideas, written
down with enough detail that I (or anyone else) can pick one up later without
starting from zero. Items may change, move between groups or be dropped.

What the app does today is in the [README](README.md).

- **Next**: small or well understood. I would start with these.
- **Later**: worth doing, but bigger or waiting on a decision.
- **Maybe**: not sure yet that they are worth the extra complexity.
- **Shipped**: built. What was done, and how it differs from the plan, is at
  the end.

Sizes are rough: **S** is an evening or two, **M** a few evenings up to a
couple of weekends, **L** several weeks of spare time.

## Overview

| #   | Item                                       | Group | Area     | Size | Depends on |
| --- | ------------------------------------------ | ----- | -------- | ---- | ---------- |
| 1   | Warnings for broken dependencies           | Shipped | both     | S    | -          |
| 2   | Type hours into the weekly grid            | Shipped | both     | M    | -          |
| 3   | Missing timesheets and reminders           | Shipped | both     | S    | -          |
| 4   | Search and quick switcher                  | Shipped | both     | M    | -          |
| 5   | Accessibility pass                         | Shipped | frontend | M    | -          |
| 6   | Scheduled backups and a tested restore     | Shipped | infra    | S    | -          |
| 7   | API reference built from the code          | Shipped | backend  | S    | -          |
| 8   | Resize bars and draw links on the timeline | Shipped | both     | M    | -          |
| 9   | Time and utilisation reports               | Shipped | both     | M    | -          |
| 10  | Budget alerts                              | Shipped | both     | S    | -          |
| 11  | Invitation and password reset emails       | Shipped | both     | M    | -          |
| 12  | Notification emails and preferences        | Shipped | both     | M    | 11         |
| 13  | Duplicate a project as a template          | Shipped | both     | M    | -          |
| 14  | Performance on large projects              | Shipped | both     | M    | -          |
| 15  | Public holidays and time off               | Shipped | both     | L    | -          |
| 16  | Baselines                                  | Shipped | both     | M    | -          |
| 17  | Dependency types and lead time             | Shipped | both     | L    | 1          |
| 18  | Attachments on tasks                       | Shipped | both     | L    | -          |
| 19  | My tasks page                              | Shipped | both     | S    | -          |
| 20  | Approve weeks in one go                    | Shipped | both     | S    | -          |
| 21  | Schedule issues beyond the timeline        | Shipped | both     | S    | -          |
| 22  | Sign-in hardening                          | Shipped | both     | S    | -          |
| 23  | Project lead                               | Shipped | both     | S    | -          |
| 24  | Daily summary and automatic reminders      | Shipped | both     | M    | 19         |
| 25  | Schedule alerts                            | Shipped | both     | S    | 23         |
| 26  | Encrypted backups off the machine          | Later | infra    | M    | -          |
| 27  | Invoices from approved time                | Later | both     | L    | -          |
| 28  | Keyboard and drag on the timeline          | Later | frontend | M    | -          |
| 29  | Calendar feed                              | Later | both     | M    | -          |
| 30  | Browser tests                              | Later | frontend | M    | -          |
| 31  | Attachments on comments and pasted images  | Maybe | both     | M    | -          |
| 32  | Placeholder people in the plan             | Maybe | both     | L    | -          |
| 33  | Search for a large organisation            | Maybe | backend  | M    | -          |

## Before starting on one

A few things hold for every item:

- The API is organised by feature under
  `api/src/main/java/com/projectplanner`. Resources stay thin, and every
  permission check goes through `OrgAccess` (`member`, `team`, `manager`,
  `viewProject`, `editProject`, `manageProject`). An organisation you are not
  in answers 404, client viewers (`Role.CLIENT`) only reach their own client's
  projects, and money fields are only filled in when `role.canManage()`.
- Errors are an `ApiException` with a stable `code`. `ErrorMappers` turns it
  into the `ApiError` JSON and the web app shows it through `messageOf`.
- Never call `LocalDate.now()`. Inject the `Clock` and ask
  `Organisation.today(clock)`, so that "today" is the organisation's day.
- Schema changes are Flyway files in `api/src/main/resources/db/migration`
  (the latest is `V15`). The API tests run the same files on H2 in PostgreSQL
  mode, so the SQL has to be plain enough for both: no `tsvector`, `jsonb`,
  partial indexes or Postgres-only functions. Where an item needs a migration
  I have written down a version number for it, so that two items in progress
  at the same time do not both take `V16`. Gaps are fine. The numbers run up
  to `V24`. Item 2 had none and took `V25` for the key on time entries,
  item 13 took `V26` for the second project of an activity entry, and the
  claim on notification mails took `V27`. Items 19 to 33 have
  `V28` to `V36` written down in the same way, so the next free one is `V37`.
- The items can land in any order, so the migrations can too. Flyway runs with
  `quarkus.flyway.out-of-order=true` for that reason: a database that already
  ran `V18` still gets `V16` applied when it shows up. With the default,
  Flyway would refuse to start there, and the only way out would be to throw
  the data away. This only works while every migration stands on its own, so
  build on the schema up to `V15` and on your own item's file, never on
  another item's.
- Tests go where the existing ones are: plain JUnit for the rule classes
  (`schedule`, `report`, `workload`), `@QuarkusTest` with REST Assured and the
  `support/Api` helper for endpoints (`support/TestClock` fixes the clock for a
  test that depends on the hour or the day), and Vitest specs next to the file
  on the web side, with the logic pulled into a plain function first (as in
  `gantt-layout.ts` and `timesheet-grid.ts`). There are no browser tests, only
  jsdom, so layout, real drag and drop and colours cannot be asserted.
- CI (`.github/workflows/ci.yml`) runs `./mvnw -B verify`, `npm run lint`
  (which includes the angular-eslint template accessibility rules),
  `npm test`, `npm run build`, `docker compose build` and
  `scripts/check-restore.sh`. The last one is the only place where the stack
  is started: `db` and `api` with the demo data. A migration that H2 accepts
  and PostgreSQL does not, or a seed that no longer runs, shows up there. The
  production build has bundle budgets in `angular.json` (a warning at 700 kB
  and an error at 1 MB for the initial bundle), which is a reason to think
  twice before adding a library.
- An endpoint is in the API reference from the moment it exists, and
  `ApiReferenceTest` holds the two together. It asks three things of a change
  to an endpoint, and says which one is missing, with the file and the line:
  - a resource method has `@Operation(summary = ...)` and a `@Tag`, on the
    class or on the method. The status of a successful call is read from
    `@ResponseStatus` and needs nothing;
  - a resource method names every error code it can answer with for a reason
    of its own, and no other: one
    `@Fails(status = 409, code = "code_taken", when = "...")` line per code.
    The test finds the codes in the sources (`ApiException.badRequest`,
    `ApiException.conflict`, `new ApiException`) and wants each one named, with
    the status it is thrown with, on a method of a resource that reaches the
    class it is thrown in. Write the code as a string literal or a string
    constant: one that is put together at run time cannot be read and fails
    the test;
  - when a path, a parameter, a response status, a field of a record or an
    enum value changes, the pinned shape of the document changes with it.
    Refresh `api/src/test/resources/api-shape.txt` with
    `./mvnw -q test -Dtest=ApiReferenceTest -Dapi.shape=update` (in `api/`) and
    commit it with the change. After a merge that conflicts in that file, take
    either side and run the command again.

  Two records with the same class name cannot both be in the API: the build
  stops and names them. Give one a name of its own in `SCHEMA_NAMES` in
  `ApiReferenceFilter`.
- On the web side `core/models.ts` mirrors the API's JSON by hand, the HTTP
  clients are the `core/*.api.ts` files, and everything inside a project page
  shares one `ProjectStore`.
- A new kind of notification is a new `NotificationType` value (the column has
  no check constraint, so no migration), a call to `Notifier.notify`, and an
  icon and a target in `notifications.page.ts` (`ICONS` and
  `notificationTarget`). A notification about a week of time goes through
  `Notifier.notifyAboutWeek` instead and opens `/time?week=`.

## Next

Items 19 to 23 were small and closed something a shipped item left open. All of
them have shipped, so nothing waits here for now.

## Later

### 26. Encrypted backups off the machine

Improvement of item 6, which left three things open: the dumps are not
encrypted, they stay on the machine unless `BACKUP_DIR` points elsewhere,
and nothing warns when a dump fails apart from the service log. A backup
that has quietly failed for a month is found on the day it is needed.

- Encryption: with `BACKUP_ENCRYPT_TO` set to an `age` public key, each dump
  and each attachments archive is encrypted as it is written, and only the
  encrypted file ever touches the disk. `backup restore` takes the private
  key from a file named in `BACKUP_DECRYPT_KEY`. Without the setting nothing
  changes.
- Tricky: `age` is not in `postgres:17-alpine`, and installing it at start
  needs the network. Build a small image of our own on top of it
  (`backup/Dockerfile`), which keeps `pg_dump` matching the server.
  `docker compose build` already runs in CI.
- Off the machine: `BACKUP_COPY_DIR`, a second folder (a mounted share or a
  disk) that every finished dump is copied to and pruned in the same way.
  Copying to an S3-compatible bucket is optional and only to one that can be
  self-hosted (MinIO, Garage): `rclone` in the same image, configured by the
  operator. A failed copy does not fail the dump, but counts as a failure.
- A warning: the script writes a small status file after each run (time,
  outcome, file), and the compose service gets a `healthcheck` that is
  unhealthy when the newest good dump is older than twice the interval.
  `docker compose ps` then shows it. The owners should hear about it too: the
  API reads the status file from a read-only mount and shows a line on the
  Settings page for owners, and mails them once when it turns bad, if mail
  is set up.
- Tricky: the clocks. Item 6 reads the age of a dump from its name, never
  from modification times. The status file has the same rule.
- `scripts/check-restore.sh` gets an encrypted round trip: a dump with a test
  key, a restore with the private key, the row counts, and a restore with a
  wrong key that leaves the database alone.
- Upgrades: the README gets a section on moving to a new major version of
  PostgreSQL, which is a dump with the old image and a restore with the new
  one, the path the backup script already has.
- Undecided: whether the API should know about backups at all. It makes the
  warning visible to the people who care, at the cost of a mount that ties
  the two services together. I lean towards yes, behind a setting.

### 27. Invoices from approved time

The app knows who worked how long on what, at which rate, and whether the
week was approved. What an agency does next is send the client an invoice,
and that happens in a spreadsheet built from the CSV export. Harvest and
Productive both turn tracked time into invoices, and it is the step that
makes the hours worth tracking.

- First, on its own: an "approved time only" filter on the reports and the
  CSV export, which item 9 left open. It needs the week's status next to
  each entry in the grouped query (a join to `timesheets` on person and week
  start), and it is useful without invoices.
- Data (`V32__create_invoices.sql`): `invoices` (organisation, client,
  number, period from and to, currency, status `DRAFT` or `ISSUED` or
  `VOID`, issued at, totals), `invoice_lines` (invoice, project, task or
  person, minutes, rate, amount, description) and a nullable `invoice_id` on
  `time_entries`.
- A draft is made for a client and a period from the billable entries of
  approved weeks that are not on another invoice, grouped per project and
  task or per person. Lines can be edited and removed in a draft, which
  releases their entries.
- Tricky: amounts change after the fact. Rates can change, and so can the
  organisation's currency (`OrgResource` allows it). An issued invoice keeps
  the rate, the amount and the currency it was issued with, copied onto its
  lines. Pricing still goes through `RateResolver` and `Money.cost` per
  entry, so a draft adds up to the cent to the report of the same entries,
  and a test holds the two together as item 9 did.
- Tricky: entries on an issued invoice must not change. Approved weeks are
  locked for everybody already (`TimeService.checkUnlocked`), and nothing
  takes an approval back today, but deletion still reaches them: a project's
  time entries go with it (`on delete cascade` in `V11`), and a task's lose
  their task (`on delete set null`). Refuse to delete a project that has an
  issued invoice (`409 invoiced`), and copy the task's title and the
  person's name onto each line so that a deleted task still reads. If a way
  to reopen an approved week is ever added, it has to refuse invoiced weeks.
  Voiding the invoice releases its entries.
- Numbers: one sequence per organisation without gaps, which tax offices in
  several countries want. Take the number when the invoice is issued, not
  when the draft is made, under a lock on the organisation's row (uploads in
  item 18 lock it the same way).
- Output: a print page styled for paper, so the browser makes the PDF, and a
  CSV of the lines. No PDF library: the bundle budget and the API's size are
  reasons enough.
- Budget alerts and reports are unchanged: invoicing does not change what
  was spent.
- Tricky: fixed-price projects (a money budget and no rate) do not fit an
  invoice built from hours. Leave them out, and say so on the page.
- Undecided: tax. A single tax rate per invoice, typed in, covers most small
  agencies; anything more is accounting software's job. Also undecided:
  whether client viewers see issued invoices of their client. I lean no for
  now.

### 28. Keyboard and drag on the timeline

Improvement of items 5 and 8, which left a list of things the timeline cannot
do yet. Each is small; together they are what makes the timeline the place
where plans are edited instead of the task panel.

- Draw a link from the keyboard. Today only the task panel's form does it.
  A key on a focused bar (say `L`) starts a link, the arrow keys move
  through the other bars as targets, Enter makes it and Escape gives up,
  each step read out through the `Announcer`. The targets are the same as a
  drag's drop targets in `gantt-edit.ts`.
- Move the start date with a key: `Shift` and the arrows move the due date,
  so `Alt` and the arrows for the start, with the same 300 ms grouping as
  the other moves. Check that `Alt` with an arrow does not go Back in the
  browser, as item 5 had to on the board.
- Scroll while dragging: near the left or right edge the timeline scrolls,
  so that two bars that are not on screen together can be linked at day
  zoom. Pull the speed from the distance to the edge into a plain function
  with a spec.
- A bar for a task without dates: dragging across an empty row gives the
  task a start and a due date, snapped to working days as any drag is.
- A type for a drawn link. It is finish-to-start and can be changed in the
  panel afterwards. Dropping on the left half of the target bar could make
  it start-to-start; I would rather show a small menu after the drop, which
  is clearer and works from the keyboard too.
- Tricky: none of this can be asserted in jsdom beyond the arithmetic, which
  is why the logic goes into `gantt-edit.ts` first. Item 30 would cover the
  rest.
- Undecided: whether resizing a parent moves its subtasks. A schedule issue
  already reports a subtask outside its parent. I lean towards leaving them
  and letting the issue say it.

### 29. Calendar feed

My due dates, my time off and the organisation's holidays only exist inside
the app. Every comparable tool (OpenProject, Teamwork, Float) offers a
calendar subscription, so that a due date shows up next to meetings.

- `GET /api/calendar/{token}.ics`: an iCalendar feed of the tasks assigned to
  me that have a due date, the milestones of the projects I can see, my time
  off and the organisation's holidays, as all-day events. Calendar apps
  cannot send a bearer token, so the token in the address is the
  credential.
- Data (`V33__create_calendar_feeds.sql`): one row per person and
  organisation with a hash of the token, never the token itself, as the
  reset links of item 11 do. Settings shows the address once when it is made,
  with "Make a new address", which ends the old one.
- Access is checked on every request, not when the address was made: a
  person who left the organisation gets `404`, and a client viewer only gets
  milestones of their client's projects, through `OrgAccess`.
- Tricky: the format. It is small enough to write by hand rather than add a
  library: lines end in CRLF and are folded at 75 octets, commas, semicolons
  and backslashes are escaped, an all-day event's `DTEND` is the day after,
  and each event has a stable `UID` (`task-123@` plus the host of `APP_URL`)
  so that a moved task moves in the calendar instead of appearing twice. A
  plain class with its own test, like `CsvWriter`.
- Tricky: what leaves the app. A subscribed calendar copies task titles into
  whichever calendar service the person uses, which is the thing item 12
  avoided for mail. Offer "titles" and "only project codes" when the address
  is made, and default to codes.
- Calendar apps poll every few minutes to every few hours. Answer with an
  `ETag` and `304` when nothing changed, and keep the queries to a fixed
  number, with a `QueryCountTest` bound.
- Undecided: a feed for a whole project (all its milestones) for a client.
  It would be a second kind of token, owned by a project rather than a
  person. Not here.

### 30. Browser tests

Improvement of items 5, 8 and 2. There are no browser tests, only jsdom, so
the roadmap's own rules say that layout, real drag and drop and colours
cannot be asserted. The timeline drag, the board drop and the grid's keys are
the parts most likely to break quietly, and item 5 had to work contrast out
from `styles.css` because axe could not check it in jsdom.

- Playwright in `web/`, as a dev dependency, with its own
  `npm run e2e`, separate from `npm test`. Nothing reaches the production
  bundle.
- Against a real API, not a mocked one: the point is that what the browser
  draws and what the API saves agree. The stack with the demo data is
  already started in CI by `scripts/check-restore.sh`; run the browser tests
  after it against the same stack, with the web container added.
- A handful of journeys, not a suite: drag a bar and see the saved date, drop
  a card on another column, type into the weekly grid and Tab through it,
  open the search with `Ctrl+K`, and axe with colour contrast on over the
  main pages in both themes.
- Tricky: the demo data's dates are relative to the day it runs, so the
  tests find tasks by title and read dates from the page, never hard-code
  them.
- Tricky: time in CI. Cache the browser download, run Chromium only, and keep
  the whole run under a few minutes.
- Undecided: on every pull request or once a night. I lean towards every pull
  request while it stays short.

### Follow-ups of 14. Performance on large projects

These were left out of item 14 on purpose, each small enough for a pull
request of its own.

- Older entries in the two lists that only take a limit. `ActivityResource`
  (at most 200) and `NotificationResource` cannot be asked for anything
  older. A "before this id" parameter, and "Load more" in `ActivityTab` and
  `NotificationsPage`.
- The timeline with many tasks. It draws every row and every bar.
  `layoutGantt` is recomputed when tasks, zoom or collapsed phases change and
  not during a drag, which is right, so with a thousand tasks it is the size
  of the DOM that hurts. Open with the phases collapsed above some task count
  first, and only then think about drawing just the visible rows.
- Smaller things on the web side, and only with a measurement that says they
  matter: `groupTasks` copies the child array for every subtask and scans the
  phases for every task, `ProjectStore.changed` fetches the critical path
  again after each change, and the board reloads the whole task list after
  each drop (`reloadTasks`).

## Maybe

### 31. Attachments on comments and pasted images

Improvement of item 18, which left both open and predicted that they would
be asked for next. A screenshot of a bug is pasted, not saved and uploaded.

- Data (`V34__add_attachment_comment.sql`): a nullable `comment_id` on
  `attachments`. Keep `task_id` filled for a comment's files too, so that
  who may see them, the per-organisation limit, the sweep and the clean-up
  when a task or a project is deleted all work as they do.
- Upload with the comment: `CommentBox` takes files by button, drop or paste,
  uploads them first through the existing endpoint, and the comment is saved
  with their ids. A file uploaded for a comment that is never posted is
  removed by the sweep after a day, which needs the sweep to know about
  files with a row and no comment yet.
- Pasted images in the task description go the same way, as files of the
  task.
- Comments are plain text, so an image is shown under the comment it
  belongs to, as a small preview, not inside the text. Markdown in comments
  would be an item of its own.
- Tricky: previews come through links that work for two minutes (item 18's
  signed links). A long comment thread opened for ten minutes has dead
  images. Ask for the links when the comments are drawn, and again when an
  image fails to load, rather than making the links last longer.
- Tricky: deleting a comment removes its files after the commit, and editing
  a comment can remove one of its files; both through `AttachmentService`,
  never by the foreign key alone.
- Undecided: whether a client viewer may paste into comments. Item 18 lets
  them attach files to tasks, so I would say yes.

### 32. Placeholder people in the plan

Float has "placeholders": plan a task for "a designer, three days a week"
before you know who it will be. Agencies quote plans with people they have
not hired or picked yet. Today a task is either assigned to a
member or to nobody, and nobody does not show up in the workload.

- Data (`V35__create_placeholders.sql`): `placeholders` (organisation, name
  such as "Designer", weekly capacity), and on `tasks` an
  `assignee_placeholder_id` that is set only when `assignee_id` is not, with
  a check constraint that allows at most one of the two.
- Workload: placeholders get rows of their own below the people, so a plan's
  demand for a role is visible before anybody is named. They have no
  holidays or time off of their own, only the organisation's.
- "Replace with a person": every task of a placeholder in a project is given
  to a member in one step, which sends the usual `ASSIGNED` notifications.
- Nothing else knows about them: no time can be logged by a placeholder, no
  timesheets, no reports, no mentions. A duplicated project keeps its
  placeholders, which is what makes a template useful.
- Tricky: every place that reads `assigneeId` (the list filter, "Only mine",
  the board, search, item 19's page, the critical path's names) has to treat a
  placeholder as "not a person" without breaking. That is the real size of
  this item.
- Undecided: whether this is worth it for small teams, which is why it is
  under Maybe. A member called "Designer (to hire)" with no login does half
  of it today, badly.

### 33. Search for a large organisation

Improvement of item 4, which left it open: search reads every task and
comment of the organisation on each request, and no portable index helps a
search for text in the middle. With item 14's large organisation it is
fine. With a few hundred thousand comments it will not be.

- First a measurement: a test or a script that fills a PostgreSQL database
  (not H2) with that many rows and times the three queries. Nothing below
  needs doing unless that says so.
- On PostgreSQL alone, `pg_trgm` GIN indexes on `lower(title)`,
  `lower(description)`, `lower(body)` and the project name make
  `lower(...) like '%text%'` use an index. `pg_trgm` is a trusted extension,
  so the database owner can create it without being a superuser.
- Tricky: the rule that every migration runs on H2 as well. Put this one in
  a folder of its own for PostgreSQL (`db/migration/postgresql`,
  `V36__search_trigram_indexes.sql`) that `quarkus.flyway.locations` names
  outside the test profile. The version number still has to be unique
  across both folders. The H2 tests never run it; `scripts/check-restore.sh`
  is where CI sees it, and an older dump restored onto it has to migrate
  too.
- The queries do not change, which is the point of choosing trigrams over
  `tsvector`: the H2 tests stay valid for what the queries return.
- Undecided: whether to rank hits. Trigram similarity could order them, but
  that is PostgreSQL only and would make the answer differ between the tests
  and production. I would not.

## Shipped

### 1. Warnings for broken dependencies

`ScheduleCheck` in `schedule/` reports four kinds of issue through
`GET /api/projects/{projectId}/schedule-issues`: a task that starts before a
task it waits for allows, a link to a task without a due date, a task due
after the project's end date and a subtask outside its parent's dates. The
timeline draws the reported links red and dashed, counts the issues in the
toolbar, lists them, and has a line under the toolbar whenever "Plan ends" is
not the latest due date, naming the cause it can see. The task panel shows
each issue on the link or the dates it is about, with a "Move to" button that
sends back the two dates the API suggested.
Different from the design: finished tasks are not reported, everything is
counted in working days (a start on the Saturday before the allowed Monday is
fine, as it is on the critical path, although the `Rescheduler` would still
snap such a task to Monday when its predecessor moves), and only the
too-early issue carries suggested dates. Those dates only look at the
dependencies, so the issue also says what the move would cause
(`suggestionCauses`: after the project's end, outside the parent) and the
button shows it. The check shares the link rule (`Rescheduler.earliestStart`
for one link) and the move (`Rescheduler.startingOn`), which is where item 17
has to change them. A move is still never rejected, and the phase dates were
left alone. Still open: the critical path still moves finished tasks. A
finished task that overlapped a task it waited for has no issue, but
`CriticalPath` counts it from the day its predecessor allows, so "Plan ends"
stays later than the latest due date for every overlap that was kept on
purpose. The line under the toolbar says so; whether `CriticalPath` should
leave finished tasks where they are is not decided. Also open: the list is
only on the timeline tab, so the overview and the project list do not show
that a plan has issues.

### 2. Type hours into the weekly grid

The cells of the weekly grid are inputs. They take what `parseDuration` takes
and refuse the rest, save when they are left or on Enter and then show hours
and minutes (`1:18`), and Tab, Enter and the arrow keys move between them.
`parseDuration` no longer rounds: decimal hours have to be whole minutes.
What a typed cell means is decided in
`timesheet-grid.ts` (`parseCell`, `cellAction`): create, update or delete
through the existing `/time-entries` endpoints, with the note and the billable
flag sent back on an update. Rows without time can be added, are kept per
person, organisation and week in `localStorage` (`timesheet-rows.ts`, removed
on sign-out) and can be copied from last week. Saved entries are put into the week on screen instead
of loading it again, also by the form and the list. Without `?week=` the page
asks the API for the current week, and `TimesheetResource.week` now works that
out from the organisation's date. Different from the design: a cell with
several entries is a button that points at them in the list, as planned, and
a cleared cell does not delete an entry that has a note either, because the
note can not be seen in the grid. One save runs per cell at a time
(`save-queue.ts`), the latest typed value wins, and "Submit week" waits for
saves that are still on their way and is held back when one was refused. A
save that fails marks its cell, says why under the grid and makes the page
read the week again, as does coming back to the tab. On the API, stopping a
timer now checks the week's lock and a running timer keeps its day
(`TimeService.finish`, `update`). A row that loses its last entry is kept,
and rows of weeks more than twelve weeks back are forgotten. Left and right
only leave a cell once the caret is at the end of its text. A create carries
a key that the client makes up (`client_key` on `time_entries`, `V25`), and
the API answers a repeat of it with the entry it already made, so a create
whose answer got lost can be sent again without making a second entry. The
form does the same. Still open: the bulk endpoint, which I left out.

### 3. Missing timesheets and reminders

The approvals page has a "Not submitted" tab. For a week that is over it
lists who has not submitted (`GET /api/orgs/{orgId}/timesheets/missing`,
drafts and weeks that were sent back) with the hours logged against capacity,
and "Remind" and "Remind all" send a `TIMESHEET_REMINDER`
(`POST .../timesheets/remind`). People without any time in the week are
listed apart and "Remind all" skips them. The dashboard counts last week's
missing timesheets for managers and now shows them their own last week too.
`V16` adds `week_start` to `notifications`, every timesheet notification
fills it, and the Timesheet page keeps its week in `?week=`, so a
notification opens the week it is about. Different from the design: the rules
are a plain class, `MissingTimesheets`. A reminder request answers with who
was reminded and who was left out and why, instead of failing as a whole. On
top of "once per person, week and day" nobody gets more than five reminders a
day, because every old week without a timesheet counts as missing and could
otherwise be chased one by one. The dashboard count leaves out weeks without
time, as "Remind all" does. Still open: automatic reminders (they belong to
the daily summary in item 12), "Approve all", and people who were away, who
stay under "No time logged" until item 15 knows about time off.

### 4. Search and quick switcher

`GET /api/orgs/{orgId}/search?q=&limit=` finds projects (name, code, client),
tasks (title, description) and comments, a bounded number of each, with plain
`lower(...) like` and the wildcards in the text escaped. Who may see what is in
the queries themselves: tasks and comments are reached through the projects of
the organisation, and for a client viewer only through those of their client,
so neither a hit nor a count tells of anything else. `Ctrl+K`, `Cmd+K` on Apple
platforms, or the Search button in the top bar opens a native `<dialog>` with a
text field over a listbox. The highlight is followed through
`aria-activedescendant`, Tab stays inside, and closing gives the focus back.
Enter waits for the answer to the text as typed and never opens a row that is
left from earlier text, and an answer to earlier text is dropped. The last
five picks are kept in `localStorage` under the person's key, so that signing
out removes them, and only by kind and id: their titles are fetched fresh from
`POST /api/orgs/{orgId}/search/recent`, which leaves out what the person can no
longer open. The List
tab has text, assignee, status and priority filters next to "Hide done" and
"Only mine", all in the address, and a parent whose subtask matches stays,
greyed out. Different from the design: the dialog is not loaded lazily (it
costs a few kB of a bundle that is far below its budget, and so cannot fail to
load), jsdom has no `showModal()`, so the dialog also opens without it, and
the arrow-key handling is shared with the mention suggestions as
`moveHighlight`. Still open: search reads every task
and comment of the organisation on each request, no portable index helps a
search for text in the middle, and on PostgreSQL alone a trigram index would.
After a pick the focus goes back to where it was, not into the page that
opens. When a kind is cut off at the limit, nothing says that there are more
hits than the list shows.

Follow-up, not in this pull request: a "My tasks" page. The dashboard lists
my overdue tasks, the ones due within seven days (`DUE_SOON_DAYS`) and the
ones in progress, so a to-do task due in three weeks shows up nowhere
outside its project. The page would call the search endpoint with new
`assigneeId` and `status` parameters and no text. Neither the page nor the
parameters are built here.

### 5. Accessibility pass

The gaps the item listed are closed. The shell has a skip link, `aria-expanded` on the
menu, `aria-current` on the navigation and the project tabs, a bell that says how many
notifications are unread, and no live region on the ticking timer (starting and
stopping it is read out instead). Every route has a title, and a project page puts the
project's name in it. The task panel takes the focus, closes on Escape and gives the
focus back to the row, card or bar that opened it. Board cards move with Alt and the
arrow keys through the same update as a drag. The timeline is a group of controls
instead of an image, and every move made with the keyboard is read out. The comment
box points at its suggestions and at the highlighted person. Two live regions in the
shell come from a small `Announcer` service, not from `@angular/cdk`: saves, moves and
errors in the timeline, the board, the weekly grid and the settings pages go through
it. axe runs over every page rendered inside the shell, with only colour contrast off,
and contrast is worked out from `styles.css` for every text colour and background in
both themes, which darkened six colours of the light theme and gave the dark theme dark
text on primary and danger. `prefers-reduced-motion` stops the timer's pulse.
Differences from the design: contrast is computed, not checked by hand; presses of the
arrow keys on the timeline within 300 ms are added up and saved and read out as one
move. The mention box is a combobox on a wrapper, since a textarea may carry no role. Still open: a session with a real screen reader, and drawing a
link on the timeline from the keyboard (the task panel's form does it today).

### 6. Scheduled backups and a tested restore

A `backup` service in `docker-compose.yml`, behind the `backup` profile, runs
`scripts/backup.sh` in the `postgres:17-alpine` image: a `pg_dump` in custom
format into `./backups` once a day, keeping the newest seven, and
`backup once` for a dump on demand. It is a shell loop with `sleep`, not cron.
It reads the age of the newest dump from the file names, never from
modification times, and looks at the clock every minute. Different from the
design: a restore is `backup restore <file>` and not `pg_restore --clean`. It
loads the dump into a new database that replaces the old one only when the
load worked. Cleaning the old one in place would keep the tables of
migrations newer than the dump, and the API would fail on them at its next
start. The script runs as root and gives each dump to the owner of the
folder. CI runs `scripts/check-restore.sh`: the loop and the pruning against
clocks that are wrong, row counts of a restored copy, and an older dump (with
`V15` and without `V14`) restored onto a new volume and migrated by the API.
The risky direction turned out safer than I thought: Flyway does not just
warn, the API refuses to start on a database with a migration it has no file
for, and the check pins that. Still open: the dumps are not encrypted, they
stay on the same machine unless `BACKUP_DIR` points elsewhere, nothing warns
when a dump fails apart from the service log, and the files of item 18 would
not be in them. The check undoes `V14` by dropping `tasks.position`, so it
needs another migration to undo if a later one builds on that column.

### 7. API reference built from the code

`quarkus-smallrye-openapi` builds an OpenAPI document from the resources and
their records when the API is built. It is served at `/api/openapi`, and as a
Swagger UI page at `/api/docs` when `API_DOCS=true`, which is off unless
asked for, in Compose too. Every operation has a summary, a tag and the bearer
requirement, and every error is an `ApiError`: one shared response, plus the
codes of the operation, written as `@Fails` on the method. Different from the
design: not all of it came free. The scanner does not read `@ResponseStatus`,
so it documented a created resource as 200 and a POST without an answer as
201. `ApiReferenceFilter` puts that right at build time, from the annotation
that sets the real status, and adds the errors. The document is not
committed, but, against the design, a copy of its shape is: paths,
parameters, statuses and schemas, one fact per line, without the prose
(`api-shape.txt`). A test compares the two, and a second one compares the
error codes in the reference with the ones in the sources, per resource. That
is the conflict the design warned about, kept small: an item that changes an
endpoint refreshes the file with one command. The README table shrank to the rules
that hold everywhere. Still open: generating `models.ts` from the document,
descriptions for the plain fields and most query parameters, and which
response fields can be missing. Who may call an operation is prose in its
description, and nothing checks it.

### 8. Resize bars and draw links on the timeline

At day and week zoom a bar has a grab strip at each end and a dot after it.
Dragging an end saves the new start or due date through `store.updateTask`.
Dragging the dot onto another bar calls `store.addDependency`, the same
endpoint and loop check as the form in the task panel. An arrow can be clicked
or focused and removed with a button or the Delete key, and `Shift` plus the
arrow keys moves the due date of the focused bar, one working day per press.
`POST /api/tasks/{id}/shift` now moves by working days in either direction and
keeps the length in working days (`Rescheduler.shift`), and the timeline sends
working days. A dragged bar is drawn where it will land, with the length the
API will give it. A refused edit leaves the bar where it was, because the
timeline only draws what the API answered. Different from the design: the
arithmetic of a drag is in its own file, `gantt-edit.ts`, next to
`gantt-layout.ts`; a press only becomes a drag after four pixels, and one
that does not opens the task, on a handle too; Escape gives up a drag; and Tab
goes from a bar to the arrows that leave it, through stand-ins that follow the
bar in the page while the arrows themselves stay drawn under the bars. At
month zoom the timeline is read-only, keys included. Still open: the start
date has no key of its own on the bar (the Start field in the task panel does
that), linking by keyboard stays in the task panel, the timeline does not
scroll while something is dragged (two bars that are not on screen together
are linked at week zoom or in the task panel), a parent's subtasks do not
follow when it is resized, and drawing a bar for a task without dates is the
follow-up it was.

### 9. Time and utilisation reports

The Reports page has three tabs. Summary shows logged time per project,
client, person or task (`GET /api/orgs/{orgId}/reports/time`) with bars drawn
by `ProgressBar`, and a click on a project shows its tasks. Utilisation shows
billable time against capacity per person (`.../reports/utilisation`, for
managers). Export is the form that was there. The grouping is the plain class
`TimeReport`, the share `Utilisation`, and the range `ReportRange`. Different
from the design: the database does the adding up after all. Entries are
grouped by project, person, billable flag and length, with a count, and one
entry of each group is priced, which is the way item 14 describes and keeps
the totals equal to the CSV export of entries to the cent. A test runs the
report over the seeded year against that export. The presets (this and last
week or month) are worked out by the API from the organisation's date and not
by `preset()` in the browser. There is a `clientId` filter as well, and a
project, client or person that is not in the organisation is a `404`.
Capacity is not a second definition: it is
`WorkloadCalculator.capacityMinutes`, the weekly hours over the working days,
which is where item 15 has to change it. Time and capacity are both counted
only up to today, since time can be logged a week ahead. Both
reports also come as CSV with the rows on screen, in whole minutes and plain
decimals. Amounts are shown to the cent so that a column adds up to its
total. Still open: the filter for approved time only, which needs the week's
status next to each entry in the grouped query. Somebody who has left the
organisation still has their time in the summary, and can be filtered by, but
is not in utilisation, which the page says.
The summary has no filters of its own on the page beyond the grouping and
the project click, although the API takes them.

### 10. Budget alerts

`BudgetAlerts` in `report/` observes three CDI events fired inside the
transaction of the change: `TimeChanged` (`TimeService.create`, `update`,
`delete`, `finish`, and both projects when an entry moves), `BudgetChanged`
(`ProjectService.update` when the hours budget, the money budget or the
project's rate changes) and `RatesChanged` (the organisation's default rate,
a role's rate, a member's rate or role, a member removed). The thresholds are
80 and 100, the same for hours and money, in `BudgetThresholds`. The burn is
compared as the overview shows it, a whole percentage, so a project that
reads 80% there has had its alert. `hours_alert_pct` and `amount_alert_pct`
(`V17`) hold the highest threshold announced per burn. Going from under 80
straight past 100 is one alert, for 100. A burn that falls back takes its
level down with it, so a later real crossing alerts again. Every owner and
manager gets a `BUDGET_ALERT` with the project and no actor, one message for
hours (no amounts) and one for money. The numbers come from
`BudgetCalculator` through `ProjectHealthService.budget`, which now groups
the entries in the database by person, billable flag and length with a count,
so money is still rounded per entry and the overview reads the same way.
Changes to a project's time lock its row first (two projects lowest id
first, each checked to be in the organisation before it is locked), so two
entries saved at the same moment give one alert. Rows are always taken
memberships first, then projects, which a rate change follows too. A replayed
create with a client key returns before anything is saved and does not
alert. A project without a budget and nothing to take back costs no query.
The demo projects start at the level of their burn, and only the patient
portal, which is over its hours budget, has told the managers.
Different from the design: the thresholds are not next to
`HealthStatus.BURN_AHEAD_TOLERANCE` but in a class of their own with the
rule for when an alert goes out. A changed rate is looked at the moment it
changes, for every project of the organisation with a money budget in one
grouped query, instead of waiting for the next entry. Saving the same rate
again changes nothing and looks at nothing. Still open: every manager hears about every
project, since there is no project lead, and there are no alerts for the
schedule. Email is item 12.

### 11. Invitation and password reset emails

Mail goes out by plain SMTP with `quarkus-mailer` when `MAIL_HOST` is set, and then
`MAIL_FROM` and `APP_URL` are required, links being built from `APP_URL` and never
from the Host header. TLS is required unless `MAIL_STARTTLS=DISABLED`, which
`.env.example` sets for Mailpit, the optional `mail` service (page on 127.0.0.1, SMTP
not published). Without mail the app behaves as before, the reset endpoints answer
`mail_off` and the web app hides the links. `POST /api/auth/forgot` always answers
204, does its work on a mail thread after the answer, is limited per client and per
account (one a minute, three live links, never cancelling an earlier one), and
`POST /api/auth/reset` uses a link with one conditional update. Invitations get a
hashed, single-use link for seven days, a "resend" that replaces it and
`POST /api/invitations/accept` for an existing account with the invited address.
`V18` adds `password_resets`, the link columns on `invitations`, and on `users` a
`token_version` that every token carries, the database counts up on a reset or a
change, and `CurrentUser.id()` checks. Different from the design: a version instead of
comparing the change with the token's issued-at time, since whole seconds would let a
token from the same second survive a reset; the mails carry no names, not "Maya
invited you to Brightline Studio"; the token travels after the `#` instead of
`/signup?email=`, and the links go to new `/invite` and `/reset` pages. With mail on, a
mailed invitation is only accepted with its link: signing up with the address alone
makes an account and leaves the invitation waiting for its link.
`INVITE_LINK_REQUIRED`, off by default, makes every sign-up need one. A password change now answers with a new token,
since it ends the session it was made in too. Still open: verifying the
address at sign-up (not done, as the roadmap leaned), and the per-client limit is kept
in memory, so it resets on restart and is per API instance.

### 12. Notification emails and preferences

`V19` adds `emailed_at`, `email_attempts` and `email_retry_at` to `notifications`
(stamping the old rows) and `notification_preferences`. `Notifier.notify` sends
nothing and leaves a notification that is to be mailed without `emailed_at`;
`NotificationMailer`, on `quarkus-scheduler` once a minute, mails what waits, one mail
per person, after looking at each notification again: read, no longer mailed to the
person, out of the organisation or out of their sight means it is marked done without a
mail. Failures are retried after 1, 2, 4, 8 and 16 minutes. `GET` and
`PUT /api/me/notification-preferences` and a card on the settings page choose per type
between mail, the app only and not at all. `OrgAccess.canSee` takes any membership, and
`mentionedUsers` and the suggestions in `CommentBox` leave out client viewers of other
clients. `DemoSeeder` marks everything it creates as mailed. Different from the design:
three choices instead of mail on or off; the mails carry no titles or comment text,
only a fixed sentence per type and a link; every mail has a signed stop link per type
that works without signing in, through a page at `/unsubscribe` that asks first.
Still open: the daily summary and the automatic timesheet reminders, which stay the
follow-up the roadmap planned; links in a mail open in whichever organisation the
browser has selected. In a follow-up each person's rows are claimed just before
their mail, with `FOR UPDATE SKIP LOCKED` and a claim token (`V27`), people are taken
oldest waiting first and once a round, so several API instances can run the job, and
the job writes its outcome with bulk updates of its own columns.

### 13. Duplicate a project as a template

`POST /api/projects/{projectId}/duplicate` copies phases, tasks, subtasks,
milestones and links into a new planned project, in one transaction, and
`GET` on the same path proposes a name, a free code (`ProjectCodes.next`) and
a start date. `PlanShift` in `schedule/` moves the dates: the first day of the
plan lands on the chosen day and everything keeps its distance from it in
working days. The "New project" form has a "Copy the plan from" select, and
the Settings tab a "Duplicate" card, both with the same small form. Different
from the design: the client is not copied unless it is picked, like the
assignees; the first day of the plan is the earliest of the task dates and
the project's own start, not only the earliest task start, so a project that
begins a week before its first task still does; and the rows are not made one
entity at a time but by one `insert ... select` per table
(`ProjectDuplicator`), because the ids are identity columns and Hibernate
cannot batch those. A plan of 3,000 tasks takes the same twenty statements as
one of twenty, and a table only needs a second statement above 5,000 rows.
The copy has the schedule issues of the source's dates and no
others, with two things to know: tasks finished in the source are open again,
so an overlap the source had stopped reporting is reported, and a task that
lies on a weekend entirely becomes a one-day task on the next working day (since
item 17 the scheduling rules read it that way in the source as well). The copy's one activity entry does not
name the source, because a copy can be made for another client: the source's
id is kept next to the entry (`related_project_id`, `V26`) and its name is
added for readers who may see it. A code that two requests take at the same
moment is `409 code_taken` for the second, from the unique constraint, for a
plain create as well, and a source that is changed while it is copied is
`409 source_changed`. Still open: real templates (projects
flagged as such and hidden elsewhere), a choice of which phases to copy, and
the organisation's calendar in `ProjectDuplicator` once item 15 has one.

### 14. Performance on large projects

`QueryCountTest` counts statements with Hibernate's statistics (on in the
test profile) on an organisation built by the test, not by `DemoSeeder`:
fifty projects, one with 1,000 tasks and 20,000 time entries, five people,
thirty-six submitted weeks and an owner in five organisations. Each endpoint
has a fixed bound, and a small organisation sends as many statements as the
large one. The project list is nine statements for every role (eight before
item 15 added the holidays), the target
was under ten. `ProjectHealthService.summaries` takes the tasks of all listed
projects in one query, their time in one query grouped by project, person,
billable flag and minutes with a count, and one `RateBook`, and
`ProjectService.toResponses` the client names in one. The dashboard uses the
same and maps its three task lists in one pass (22 statements). The light
list is `GET /orgs/{org}/projects/options` (id, code, name, status, client),
and `TimesheetPage` and `ReportsPage` fill their selects from it. People are
looked up in one `id in` query in `TimesheetService.summaries` (which also
takes the entries of all its weeks at once), `OrgResource.members`,
`CommentResource.mentionedUsers` and `MeResource.me` (the organisations).
Money is still priced and rounded per entry, which
`BudgetCalculatorTest.roundsEveryEntryOnItsOwn` pins. Different from the
design: no index was needed, so `V20` is unused. Still open: the three
follow-ups listed under Later.

### 15. Public holidays and time off

`V21` adds `holidays` and `time_off`, and the `calendar` package has their
endpoints: every member reads the holidays (client viewers too, since their
timeline shades them), managers write them; the team reads time off, people
manage their own and managers anybody's. `WorkCalendar` takes the holidays
that fall on weekdays and takes them out of `workingDaysBetween` after the
fast path for whole weeks. `OrgCalendars`, request-scoped, reads them once per
request, and every place that counted working days with
`WorkCalendar.DEFAULT` now asks it: the rescheduler in `TaskService` (moves,
shifts and the cascade, through item 17's `LinkRule` for all four link types,
whose start-to-finish takes the working day before with the holidays), the critical path and the schedule check in
`ScheduleResource`, progress and the three counts in `ProjectHealthService`,
`ProjectDuplicator` with `PlanShift`, the slips of item 16 in `BaselineService`,
`WorkloadResource` and the capacity in `TimeReportService`. `DEFAULT` is left for the tests and the seeder. Capacity
is per week (`WeekLoad.holidays`, `daysOff`): every working day is a fifth of
the weekly hours, so a holiday takes a fifth off for everybody and a day off a
fifth off for one person, and a task is still spread over its own working
days. On the web, `shared/dates.ts` takes the holidays in every working-day
function, and `gantt-edit.ts` and `gantt-layout.ts` pass them on, so a drag
lands where the API will put it and holidays are shaded like weekends.
Settings has a holidays card, the Team page a time off card, the workload
marks weeks with less capacity, and the timesheet header names a holiday
(the week's days carry it). Plans are not rewritten when a holiday is added,
and time can still be logged on one. The demo team's holidays are time off
now, and Tom is away three days next week. Different from the design: the
holidays are readable by client viewers. Overlapping time off for one person
is refused, under a lock on their membership, and holiday changes are in the
activity log. Left as the roadmap leaned: no approval, no half days, no import,
one calendar per organisation. Still open: the missing timesheets list still
shows the plain weekly hours as capacity, and the demo has no public holidays,
since one inside a seeded plan would change the schedule issues the demo is
built to show.

### 16. Baselines

A baseline is saved on the project's Settings tab with a name, by owners and
managers, and holds the dates of every task and phase and the project's own start
and end at that moment (`V22`: `baselines`, `baseline_tasks` and
`baseline_phases`, plain ids and copied titles, so a deleted task shows up as
removed). It never changes afterwards; only its name and the reference mark do.
Each table is filled by one `insert ... select`, so a plan of thousands of tasks
takes as many statements as a small one. A project keeps up to ten, and one is the
reference the timeline compares with when it opens. `BaselineComparison` in
`schedule/` works out per task and per phase the slip of start and finish in
working days, with `WorkCalendar`, and the slip of the project's end and the plan's
end. The timeline draws a thin grey bar under each bar, has a "Compare with" select
and a list of what changed, and reads each bar's change out with it. The task panel
says it in words, and the Overview has the ends and a table of the milestones.
Client viewers of the project's client see the comparison, read-only, since they
see the plan's dates already. Schedule issues and the critical path ignore
baselines, and duplicating a project does not copy them. Differences from the
design: phases are kept as well, a task that had no dates in the baseline and has
them now counts as added, and no baseline is taken on its own when a project goes
from planned to active: a baseline is a deliberate act, and an automatic one would
take a slot of the ten without anybody choosing it. Still open: that automatic
baseline if people ask for it, and the organisation's calendar once item 15 has
one.

### 17. Dependency types and lead time

All four types shipped, not only finish-to-start and start-to-start: `V23` adds
`dependency_type` (`FS` for every link that existed) and replaces the lag check
with a range of -365 to 365 working days, so a negative lag is a lead. The rule
lives in one place, `LinkRule` in `schedule/`: the earliest start of a successor
for each type, with a link on the finish turned into a start by keeping the
successor's length, and the same rule in working-day offsets for both passes of
`CriticalPath`. `Rescheduler`, `ScheduleCheck` and its "Move to" dates go through
it, and a property test holds the dates and the offsets to each other. A lead
across a weekend works because every count starts from a working day:
`addWorkingDays` keeps any date for zero, so the anchor is moved onto one first.
Start-to-finish without a lag ends the successor on the working day before the
predecessor starts, the mirror of finish-to-start. A task on days off only counts
as a one-day task on the next working day everywhere (`LinkRule.normalise`), so
the critical path and the rescheduler agree on a Saturday milestone. A change to a
link moves only what that link reaches, not other tasks that wait for the same
predecessor. `DependencyGraph` did not change. `PATCH /dependencies/{id}` changes the type or
the lag and pushes the successor later when needed, never earlier. A schedule
issue about a link names its type, so the web can say "ends too early" for the
finish types. The timeline draws each arrow between the ends its type names, with
the type and lag beside it, and the task panel has a type select and a lag box for
a new link and for each existing one. A copied project keeps the types. Baselines
keep dates, not links, so the comparison is untouched by types. Still open: a type
for links drawn on the timeline (they are finish-to-start and can be changed in the
panel afterwards), and the organisation's calendar from item 15, which replaces
`WorkCalendar` behind `LinkRule` without changing the rule.

### 18. Attachments on tasks

Files on tasks, in the `attachment` package and `V24`, on an `attachments`
volume under random keys. Whoever can see the project sees and attaches files,
client viewers of its client included; whoever attached a file, an owner or a
manager removes it. What a file is is read from its first bytes and checked
against its name and a fixed list (raster images, PDF, plain text, office
documents); SVG, HTML and plain text that starts like markup are refused. The
bytes come through a link that works for two minutes, an HMAC over the
attachment, the user, the expiry and the preview flag, with access checked
when the link is made; every answer has `Content-Disposition`, `nosniff` and a
sandboxing CSP, and only raster images are ever shown inline. Names lose
control and invisible format characters, and only plain files inside the
folder are served, never links. 20 MB a file and
2 GB an organisation; Quarkus and nginx let 25 MB through so the API can say
why. Deleting an attachment, a task or a project removes the files after the
commit, a failed upload removes its file, and a sweep every six hours removes
files without a row. A copied project takes the files only when asked, and leaves out (and counts)
files that are missing. Uploads lock the organisation's row for the limit. The
backup service archives the volume next to every dump and restores it with
the dump, and the restore drill in CI checks it. Differences from the design:
the bytes are behind a signed link instead of the bearer token, which lets a
plain download and an `<img>` preview work, and a link works for its two
minutes even after access is taken away; and a file removed in the minute between a dump and its
archive is missing from that backup. Still open: attachments on comments,
pasted images, and storage other than the volume.

### 19. My tasks page

`assigneeId` and a repeatable `status` on `GET /api/orgs/{orgId}/search`: with either,
only tasks are found, `q` is optional, the limit goes up to 200, and they come by
due date with no date last, then by project code and position. Every kind of the
search now asks for one row more than its limit and names the kinds cut off in
`more`, which closes item 4's open point. The task hits carry the due date, the
status and the priority, and the task-only answer carries `today`, the
organisation's date. The visibility stays in the query: the organisation's
projects, and a client viewer's client. A `/my-tasks` page groups the tasks into
Overdue, This week (up to Sunday), Later, No date, and Done when "Show done" is on
or a task was just ticked; a box ticks a task done in place and unticking restores
its earlier status. Team members pick another person in a select. Decided: it
stays the search endpoint. Different from the design: the page and its link are for
the team only, since a client viewer can never be assigned a task; the API still
answers a client viewer, within their client's projects, for whoever they ask
about. Still open: the page lists at most 200 and says so, with no paging.

### 20. Approve weeks in one go

`POST /api/orgs/{orgId}/timesheets/approve` takes the weeks as they were on
screen, each with its `submittedAt`, and answers per week, approved or left
out with `NOT_FOUND`, `OWN_WEEK`, `NOT_SUBMITTED` or `CHANGED`; a bad week never
fails the others. The weeks are read in one `id in` query with a lock, in id
order, and the people and their notification choices in one query each, so
five weeks read what one does (`QueryCountTest`). The log has a line per
week. The approvals page has a box per row, "Select all", "Approve
selected" and "Approve all", and names each week left out with its reason.
The "Not submitted" tab shows the week's capacity, counted by
`WorkloadCalculator.capacityMinutes` with the holidays and time off, and
somebody off the whole week stays listed, marked away. Differences from the
design: one notification per week, not one per person, since each opens its
own week; `submittedAt` is compared to the millisecond, since the database keeps
microseconds and a client may keep less; a manager's own week has its box turned off,
since the API would leave it out anyway; and a single approval, a send back and
a withdrawal now lock the week as well, so that a withdrawal at the same
moment as an approval waits for it instead of overwriting it. The dashboard
reads the time off for its count of missing weeks, one statement more (24).
Left out as the roadmap leaned: "Send back all", since a rejection needs a
note for each person.

### 21. Schedule issues beyond the timeline

`HealthSummary` and `ProjectHealth` have `scheduleIssues`, the length of the
timeline's list, worked out by one `ScheduleIssues.check` that the
`schedule-issues` endpoint uses too, so the two always agree. The project list
reads the links of all its projects in one query, grouped in Java, with the
calendar `OrgCalendars` already holds: ten statements of its own instead of
nine, and the `QueryCountTest` bound is one higher. The list shows a marker
with the count that links to the timeline, and the Overview a line under the
end date. The critical path keeps finished tasks at their own dates in both
passes, by leaving out the links into them: they have no float (`floatDays`
is left out) and are never critical, and the tasks after them still follow
them. `LinkRule`, the rescheduler and the schedule check are unchanged. The
line under the timeline's toolbar lost the finished-task case. Decided: every
issue counts, a task due after the project's end included. Different from the
design: the dashboard's project cards do not count issues, so the dashboard
keeps its bound, and an open task that nothing open waits for (only a finished
task) now has float up to the end of the plan.

### 22. Sign-in hardening

`LoginLimits` and `V28` (a `login_failures` table per account and client address,
and `failed_logins` with `last_failed_login_at` on `users`). Five wrong passwords
for an account from one address pass; after that each try from that address for
that account waits from the last wrong one, one second and doubling up to five
minutes, with `429 too_many_attempts` and `Retry-After`. A try during the wait is
refused before the password is checked and does not count. Only that pair waits:
a right password from any other address always goes in, so somebody hammering an
account from one place cannot keep its owner out. Twenty wrong passwords for an
account from all addresses, or a hundred from one address in an hour (in memory,
like `ClientThrottle`), never refuse anything: they leave a new pair one free try
instead of five. A right password clears its pair and the account's count, a reset
or a change of password clears all of them, and a day without failures forgets
them. Each try is counted before the hash is checked, with an update that only
matches the count it read, so a burst at the same moment cannot get past a wait.
An unknown email is checked against a dummy hash of the same cost. nginx sends a
Content-Security-Policy, `nosniff` and `Referrer-Policy: same-origin` with the app,
not on `/api/`, and has the `real_ip` lines for a TLS terminator in front,
commented out. Differences from the design: the wait is per account and address
rather than per account, since one address could otherwise keep an owner waiting
for as long as it liked; `style-src` is `'self'` without `'unsafe-inline'` or a
nonce, because the components have no styles of their own; the lint keeps it that
way, and the build no longer inlines critical CSS, whose loader was an inline
script. Still open: a waiting pair answers 429 where an unknown email answers 401,
and a known email costs a few more writes than an unknown one, which both tell
that it exists, like sign-up does (the README says so); guessing grows with
the number of addresses, about 300 tries a day each once the account is past its
count, which is the price of never locking the owner out (an hourly sweep and a cap
of a thousand pairs per account keep the table bounded); HSTS is for whatever terminates TLS; and a list of sessions with "sign out
this device" was left out, as the design suggested.

### 23. Project lead

`lead_id` on `projects` (`V29`), a user of the organisation who is an owner, a
manager or a member; a client viewer is refused with `client_cannot_lead` and
somebody from elsewhere with `unknown_lead`. Set in the project form and the
Settings tab by owners and managers, and part of `PUT`, so leaving it out clears
it like any other field. The project answers carry `leadId` and `leadName`; the
list reads the names of all leads in one query, which is sent only when a project
has a lead (`QueryCountTest` holds it to one statement). Budget alerts: without a
lead, owners and managers as before; with one, the lead and the owners, and the
money message only to those of them who can manage. Removing a membership clears
the lead in the same transaction, and so does a change of role to client viewer.
A copy keeps the lead only with `keepAssignees`. The project list has a Lead
column and a "Led by me" filter in the page, offered when you lead one of the
listed projects. The demo has Priya leading the Acme booking app and Lena the
design system. Decided: the simplest rule, the lead plus the owners for both
messages. The schedule alerts of item 25 go to the same people as the hours
alert, and the mailer lets a member lead through for those two and never for
the money one. Still open: the morning summary of item 24 still counts
timesheets for owners and managers only, which is right, since a lead does not
review time.

### 24. Daily summary and automatic reminders

`DailySummaries` runs once a minute and sends one mail per person and
organisation from eight to noon in the organisation's zone on its working
days, worked out from the zone each day: tasks due today and overdue, and for
owners and managers the timesheets waiting for review (an owner's own week
included, a manager's not), as counts with links to `/my-tasks` and
`/approvals`. Nothing to say, no mail. A row in `daily_summaries` (`V30`) is
inserted in its own transaction before the mail, so the unique key lets one
instance through. `DAILY_SUMMARY` is a mail-only kind on the settings card
with on and off, on by default where the person manages, and its stop link
turns it off. Automatic reminders are an organisation setting, off by
default: `AutoReminders` sends last week's `TIMESHEET_REMINDER` on the first
working day of the week, with no sender, through the limits of "Remind all".
Every link in notification mails and summaries carries `org=`, and a guard
switches the app to that organisation before the route loads. Decided: one
mail per organisation. Different from the design: the day column is
`summary_day`, since `day` is a keyword in H2; the row is written when there
is nothing to say too, so a person is looked at once a morning, and a summary
that fails to send is not tried again that day; the reminder setting and the
day it last ran are two columns on `organisations` in the same `V30`, since
the item has one number; and the reminders run in the same morning window as
the summary. Item 23's lead is not used. The task lines link to item 19's
My tasks page.

### 25. Schedule alerts

`NotificationType.SCHEDULE_ALERT`, with the project and no actor, opens the
Overview like a budget alert. `ScheduleAlertRule` announces off track once and
takes it down only on track (or done, or not started), and announces a forecast
end past `Project.endDate` once until the plan ends in time again; the state is
`off_track_announced` and `late_end_announced` on `projects` (`V31`). A
`PlanChanged` event from `TaskService` (create, change, move, delete, a cascade
after a link) and from `ProjectService.update`, and the existing `TimeChanged`
and `BudgetChanged`, are observed in the transaction with the project's row
locked. A daily pass, on the first run of each organisation's day, looks at
its active projects through `ProjectHealthService.healths`, the bulk path of
`summaries`, and `organisations.schedule_checked_on` (in the same `V31`) lets
one instance take each day. The alerts go to every owner and manager, as budget alerts do,
or with a project lead (item 23) to the lead and the owners. Left out as the roadmap
leaned: naming the move that pushed the end past the deadline.
