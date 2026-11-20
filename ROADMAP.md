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
| 4   | Search and quick switcher                  | Next  | both     | M    | -          |
| 5   | Accessibility pass                         | Next  | frontend | M    | -          |
| 6   | Scheduled backups and a tested restore     | Next  | infra    | S    | -          |
| 7   | API reference built from the code          | Next  | backend  | S    | -          |
| 8   | Resize bars and draw links on the timeline | Later | both     | M    | -          |
| 9   | Time and utilisation reports               | Later | both     | M    | -          |
| 10  | Budget alerts                              | Later | both     | S    | -          |
| 11  | Invitation and password reset emails       | Later | both     | M    | -          |
| 12  | Notification emails and preferences        | Later | both     | M    | 11         |
| 13  | Duplicate a project as a template          | Later | both     | M    | -          |
| 14  | Performance on large projects              | Later | both     | M    | -          |
| 15  | Public holidays and time off               | Later | both     | L    | -          |
| 16  | Baselines                                  | Maybe | both     | M    | -          |
| 17  | Dependency types and lead time             | Maybe | both     | L    | 1          |
| 18  | Attachments on tasks                       | Maybe | both     | L    | -          |

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
  to `V24`. Item 2 had none and took `V25` for the key on time entries, so the
  next free one is `V26`.
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
  `npm test`, `npm run build` and `docker compose build`. The production build
  has bundle budgets in `angular.json` (a warning at 700 kB and an error at
  1 MB for the initial bundle), which is a reason to think twice before adding
  a library.
- On the web side `core/models.ts` mirrors the API's JSON by hand, the HTTP
  clients are the `core/*.api.ts` files, and everything inside a project page
  shares one `ProjectStore`.
- A new kind of notification is a new `NotificationType` value (the column has
  no check constraint, so no migration), a call to `Notifier.notify`, and an
  icon and a target in `notifications.page.ts` (`ICONS` and
  `notificationTarget`). A notification about a week of time goes through
  `Notifier.notifyAboutWeek` instead and opens `/time?week=`.

## Next

### 4. Search and quick switcher

There is no search anywhere in the app. Finding a task means remembering which
project it is in and scrolling through that project's list. I want `Ctrl+K` /
`Cmd+K`, and a button in the top bar, to open a small dialog: type a few
letters, arrow down, Enter, land on the task or project.

- API: `GET /api/orgs/{orgId}/search?q=&limit=` in a new `search` package.
  Hits come grouped: projects (name, code, client name), tasks (title,
  description) and comments (body). Each hit has a type, an id, the project id
  and code, a title and a short snippet.
- Scope: `access.member(orgId)`. For `Role.CLIENT` restrict everything to
  projects of `membership.clientId`, the way `ProjectResource.list` does.
  Tasks have no organisation column. Reach them through their project with a
  subquery, as `DashboardResource` does for the signed-in user's tasks.
- Matching: `lower(...) like` with `%` and `_` escaped in the input, prefix
  matches before "contains", open tasks before done ones, at most twenty or so
  per group, and nothing below two characters.
- Tricky: the tests run on H2, so Postgres full-text search (`tsvector`,
  `pg_trgm`) is out unless search gets two implementations. `LIKE` is fine for
  a few thousand tasks per organisation. Say so in the README instead of
  pretending it scales.
- Web: a new `features/search` folder with the dialog, opened from `Shell`,
  which has no key handling today. The app has no dialog component yet:
  confirmations use `confirm()` and the task panel is an `aside`. A native
  `<dialog>` traps focus without extra code. Check that jsdom implements
  `showModal()` before relying on it in specs.
- The result list is the same pattern as the mention suggestions in
  `CommentBox` (a `highlighted` index, arrow keys, Enter, Escape). Worth
  sharing instead of copying.
- A task hit goes to `/projects/{projectId}/list?task={id}`, the form the
  dashboard and the notifications already use. A project hit goes to
  `/projects/{id}`.
- Debounce the input, drop the answer to a superseded request, and show the
  last few picks (from `localStorage`) before anything is typed.
- Also in this pull request: filters on the List tab, which can only "Hide
  done" and "Only mine" today. A text filter plus assignee, status and
  priority filters over `store.tasks()` need no API. `visibleRows` filters row
  by row, so when only a subtask matches, keep its parent row, greyed out.
- So the pull request is: the search endpoint (text only), the dialog with its
  shortcut and top bar button, recent picks, and the List tab filters.
- Follow-up, not in this pull request: a "My tasks" page. The dashboard lists
  my overdue tasks, the ones due within seven days (`DUE_SOON_DAYS`) and the
  ones in progress, so a to-do task due in three weeks shows up nowhere
  outside its project. The page would call the search endpoint with new
  `assigneeId` and `status` parameters and no text. Neither the page nor the
  parameters are built here.

### 5. Accessibility pass

The basics are there: labels on form fields, focus outlines, `role="alert"` on
errors, arrow keys for moving bars on the timeline, and the angular-eslint
accessibility rules in CI. But I have never gone through the whole app with
only a keyboard or with a screen reader. Gaps I already know about:

- Board: cards move between columns by HTML drag and drop only
  (`BoardTab.onDragStart`, `onDrop`). Enter opens a card and nothing moves it.
  Add keyboard moves, say `Alt` plus the arrow keys (left and right change the
  status, up and down the `position`), through the same
  `store.updateTask(id, { status, position })`, and announce the result.
- Timeline: the SVG in `gantt.html` has `role="img"`, and the bars inside it
  are focusable with `role="button"`. A screen reader treats an image as one
  thing and hides what is inside, so the bars take focus without being
  announced. The arrow keys also save on every press (`Gantt.onKey` emits
  `moved` at once) and say nothing unless dependants moved.
- Task panel: it opens from `?task=` as an `aside`. Focus stays where it was,
  Escape does not close it, and closing does not return focus to the row or
  bar that opened it.
- Mentions: the suggestion list in `CommentBox` has `role="listbox"`, but the
  textarea does not point at it (`aria-controls`, `aria-expanded`,
  `aria-activedescendant`) and the options have no ids, so the highlighted
  name is not read out.
- Shell: there is no "skip to content" link before the sidebar. The menu
  button has no `aria-expanded`. The navigation and the project tabs mark the
  current page with a class only (`routerLinkActive="active"` without
  `ariaCurrentWhenActive`). The bell is labelled "Notifications" whatever the
  count. The running timer chip has `role="status"` and its text changes every
  second, which a screen reader may try to read out every second.
- Page titles: no route has a `title`, so every page is "project-planner" in
  the tab, in the history and in what a screen reader announces on navigation.
- Motion: `styles.css` has no `prefers-reduced-motion` rule. The one animation
  is the pulsing dot of the timer (`@keyframes pulse`).
- Add an automated check: `axe-core`, directly or through `vitest-axe`, run
  against rendered pages in the specs so that regressions fail CI. jsdom
  cannot work out colour contrast, so that rule stays off and both themes
  (`prefers-color-scheme: dark` in `styles.css`) need checking by hand. I
  would look at `--muted` text and the workload cell colours first.
- Tricky: this touches templates all over `features/`, where every other item
  also works. Keep the changes small and local: attributes and handlers, not
  restructured markup.
- Undecided: `@angular/cdk` has a live announcer, a focus trap and a drag and
  drop that works from the keyboard, at the price of a dependency. I lean
  towards a small live-region service of my own and plain key handlers.

### 6. Scheduled backups and a tested restore

Everything lives in the `db-data` volume and there is no backup of any kind.
The only export is the timesheet CSV. For a tool that holds a year of billable
hours, that is the first thing anyone hosting it should ask about.

- A `backup` service in `docker-compose.yml` behind a compose profile, on the
  same `postgres:17-alpine` image as `db` so that `pg_dump` matches the
  server. It dumps once a day in custom format into a mounted folder and keeps
  the last N files. The folder goes into `.gitignore`, the settings (how many
  to keep, how often) into `.env.example` and the configuration table in the
  README.
- The same script should run once on demand
  (`docker compose run --rm backup ...`), for a dump before an upgrade and for
  the CI check below.
- A "Backup and restore" section in the README with the restore steps: stop
  `api`, `pg_restore --clean --if-exists`, start `api`.
- Tricky: Flyway. The dump contains `flyway_schema_history`. An older dump
  under a newer API is fine: the migrations it lacks run at start
  (`quarkus.flyway.migrate-at-start=true`), and since out-of-order is on that
  includes one with a lower number than the dump's latest. Cover that case in
  the CI check if it is cheap. A newer dump under an older API is
  the risky direction: as far as I know Flyway only warns about applied
  versions it has no file for, and the old code then runs against a newer
  schema. Try it, and have the README say to restore into the same version or
  a newer one.
- A restore that is never tested is not a backup. The `docker` job in CI only
  runs `docker compose build`, so nothing ever starts the stack. Add a step
  that starts `db` and `api` with `SEED_DEMO=true`, waits for the health
  check, takes one backup, restores it into a scratch database and compares
  row counts for `users`, `tasks`, `time_entries` and `timesheets`. That is
  also the first check that the compose stack boots at all.
- Tricky: who owns the dump files. The postgres image only drops to its own
  user for the server itself, so a script run in it writes as root, and the
  files in a mounted host folder end up owned by root. Set a user for the
  service or say in the README what to expect.
- The extra CI time should be small. The Maven build inside `api/Dockerfile`
  is the slow part and `docker compose build` already pays for it.
- Undecided: cron inside a container is clumsy. A shell loop with `sleep` is
  probably enough for one dump a day.
- Undecided: if item 18 exists, its files belong in the same backup, and a
  restore of one without the other leaves dangling rows.

### 7. API reference built from the code

The README has a table of endpoints and nothing more: no request or response
shapes, no error codes. Anyone scripting against the API reads the Java.
Quarkus can build an OpenAPI document from the resources.

- Add `quarkus-smallrye-openapi`. By default the document is served at
  `/q/openapi` and Swagger UI only exists in dev mode. nginx forwards only
  `/api/` to the API (`web/nginx.conf`), so set
  `quarkus.smallrye-openapi.path=/api/openapi`,
  `quarkus.swagger-ui.path=/api/docs` and
  `quarkus.swagger-ui.always-include=true` to reach both through the web port.
  Both must answer without a token.
- Most of it comes free. The request and response types are records with Bean
  Validation annotations (`TaskDtos`, `ProjectDtos`, `TimeDtos`, `OrgDtos`,
  `AuthDtos`), so types, required fields and limits are picked up.
- What the generator cannot see and needs writing down:
  - the bearer token (`quarkus.smallrye-openapi.security-scheme=jwt`), and
    that `AuthResource` is the one public resource;
  - errors: every error body is an `ApiError` built in `ErrorMappers`. One
    shared response definition, plus the `code` values worth knowing
    (`dependency_cycle`, `timesheet_locked`, `code_taken` and so on);
  - methods that return a raw `Response`: `ExportResource.timesheets` (CSV)
    and `TimeEntryResource.timer` (200 with an entry, or 204);
  - the partial update rule of `UpdateTaskRequest` and `UpdateMemberRequest`:
    null means "leave as it is", which is why `clearDates`, `clearAssignee`,
    `clearPhase` and `clearHourlyRate` exist;
  - fields that are left out for some roles (`@JsonInclude(NON_NULL)` on
    `ProjectResponse`, `BudgetView` and `MemberResponse`).
- A tag per feature package, so the UI groups endpoints the way the README
  table does, and a one-line summary on every operation.
- Test: a `@QuarkusTest` that fetches the document and checks that every
  operation has a summary and a tag, that operation ids are unique, and that
  everything except sign-up and login asks for the bearer scheme.
- The "API overview" table in the README shrinks to a pointer and the few
  rules that are not per endpoint.
- Tricky: do not commit the document itself. A checked-in copy with an "is it
  up to date" test would make every other item that touches an endpoint
  conflict with all the others.
- Undecided: generating `web/src/app/core/models.ts` from the document instead
  of keeping it by hand. Tempting, since it is 350 lines that can drift, but
  it adds a build step and a dependency. Not in this item.

## Later

### 8. Resize bars and draw links on the timeline

On the timeline a bar can only be moved as a whole. Making a task longer, or
saying "this waits for that", means opening the task panel and using date
fields and a select. Every Gantt tool lets you drag the end of a bar and draw
an arrow from one bar to another.

- Resize: handles at both ends of a bar in `gantt.html`. `Gantt` keeps one
  drag state (`Drag` with `taskId`, `startX`, `dx`). Give it a mode: move,
  start or end. A resize ends in a new output with the new start or due date,
  which `TimelineTab` sends through `store.updateTask`. That already cascades
  and returns a `TaskChange`. Milestones cannot be resized
  (`TaskService.setDates` forces start and due to the same day).
- Link: a small connector at the right end of a bar. Dropping it on another
  bar calls `store.addDependency(successorId, predecessorId, 0)`. The API
  answers `409 dependency_cycle` with the chain in the message, or
  `409 dependency_exists`. Show both in the tab's existing error line.
- Remove a link: the arrows are thin paths. Add a wide transparent path to
  click on, make the arrow focusable, and delete with a button or the Delete
  key through `store.removeDependency(successorId, dependencyId)`. `GanttLink`
  only has the dependency id, so add the successor.
- Keyboard: the arrow keys move a focused bar today. `Shift` plus the arrow
  keys should change the due date. Linking by keyboard stays in the task
  panel.
- Put the arithmetic in `gantt-layout.ts` next to `dragDays` (new dates from a
  resize, never letting due pass start) and test it there. jsdom has pointer
  events but no layout and no `setPointerCapture` (the code already calls it
  with `?.`), so a component spec can only send events with made-up
  coordinates.
- Tricky: weekends. `TaskService.shift` moves by calendar days (`plusDays`).
  Drag a Thursday to Friday task one column to the right and it becomes Friday
  to Saturday, which `WorkCalendar.duration` counts as one working day: the
  task quietly got shorter. Resizing makes that easier to hit.
- So `shift` changes to N working days, in either direction. The new start is
  `addWorkingDays(start, n)`, which takes negative numbers, and the due date
  keeps the length in working days, the way `Rescheduler` treats dependants.
  Do not snap the result with `onOrAfter`: a Monday bar moved left by one or
  two days would land on the weekend, snap back to Monday and never move.
- The timeline then sends working days. One arrow key press is one working
  day. For a drag, take the day the bar was dropped on, go on to the next
  working day in the direction of the drag if that day is a weekend, and
  count the working days from the old start (`workingDaysBetween` in
  `shared/dates.ts` exists and nothing uses it yet). A resize snaps its end
  the same way, in the direction of the drag.
- `movingATaskCascadesThroughItsDependants` in `TaskResourceTest` shifts by 7
  and expects a week later. With working days that request becomes 5.
- Tricky: at month zoom a day is 4 pixels wide (`DAY_WIDTH.month`), too small
  for handles. Edit only at day and week zoom. On touch screens the bars need
  `touch-action: none`, or the drag scrolls `.gantt-scroll` instead.
- Tricky: a parent with subtasks. Nothing ties their dates together today, so
  resizing the parent leaves the subtasks where they were. Leave that as it
  is.
- So the pull request is: resize handles, drawing and removing links, `Shift`
  plus the arrow keys, and `shift` in working days with the timeline sending
  them.
- Follow-up, not in this pull request: drawing a bar on the empty row of a
  task without dates, to give it dates. Cheap once resize exists, but easy to
  trigger by accident, so it wants a look of its own.

### 9. Time and utilisation reports

The Reports page is a single form that downloads a CSV. To answer "how many
hours went to Acme last month" or "how billable was the team in October" I
open the CSV in a spreadsheet and build a pivot table. The data is all there.

- API: `GET /api/orgs/{orgId}/reports/time` with `from`, `to`, a `groupBy` of
  project, client, member or task, and the filters the export has (`userId`,
  `projectId`, `billable`). Each row has a key, a label, total minutes,
  billable minutes and, for managers, the billable amount. Plus a totals row.
- Same rules as `ExportResource`: team only (`access.team`), members get their
  own time and no money, the range is capped (the export allows two years),
  and running timers are left out
  (`startedAt is null or endedAt is not null`).
- Put the grouping in a plain class in `report/`, like `BudgetCalculator`, fed
  with rows of project, person, task, billable flag, minutes and rate, so it
  is tested without a database.
- Rates: one `RateBook.load(orgId)` per request, then
  `rate(project.hourlyRate, userId)`.
- Tricky: rounding. `Money.cost` rounds each entry to cents, and both
  `BudgetCalculator` and the CSV add up rounded entries. Summing minutes in
  SQL and multiplying once gives totals a few cents away from the CSV for the
  same range, and somebody will notice. Price per entry in Java for now (a
  year of the demo team is a few thousand rows) and leave the grouped query to
  item 14.
- Utilisation: `GET /api/orgs/{orgId}/reports/utilisation` for managers. Per
  person and range: logged hours, billable hours, capacity, and billable hours
  as a share of capacity. Capacity is `weeklyCapacityHours / 5` for each
  working day in the range (`WorkCalendar.workingDaysBetween`).
- Web: `ReportsPage` gets three tabs: Summary (group-by select, date range and
  the presets from `preset()`), Utilisation, and the existing Export. Bars are
  drawn with `ProgressBar`. No chart library. Clicking a project row regroups
  by task within that project.
- Tricky: capacity for somebody who joined in the middle of the range or
  changed their hours. There is no history of `weeklyCapacityHours`, only the
  current value. Use the current value and say so on the page.
- Undecided: a filter for approved time only. The CSV has a `timesheet_status`
  column but no filter. It is what invoicing wants and it is cheap to add to
  both.

### 10. Budget alerts

The point of the app is knowing whether a project will land on budget, but it
only tells you when you open the project. Nobody hears about it when a project
crosses 80% of its hours or goes over. Harvest sends a message at a threshold,
and that is the one thing of theirs I miss.

- When: after time changes on a project, which is `TimeService.create`,
  `update`, `delete` and `finish` (a stopped timer). Compare the hours burn
  and the money burn with the thresholds and notify when either burn crosses
  one for the first time.
- One threshold setting, two alert states. The thresholds (80 and 100) are the
  same for hours and for money, kept as constants next to
  `HealthStatus.BURN_AHEAD_TOLERANCE`. What has been announced is kept per
  burn: two integer columns on `projects`, `hours_alert_pct` and
  `amount_alert_pct`, both defaulting to 0
  (`V17__add_project_budget_alert.sql`). Each holds the highest threshold
  already announced for its own burn. With a single column, hours reaching
  100 would swallow the later message that money passed 80.
- Reset each state on its own: when its burn falls back under a threshold
  (entries deleted) or its budget changes in `ProjectService`.
- Who: every owner and manager of the organisation, with the loop
  `TimesheetService.submit` uses. A new `NotificationType.BUDGET_ALERT` with
  the project set and no actor. Pass `null` as the actor: `Notifier.notify`
  skips people's own actions, and the manager whose entry crossed the line
  should still hear about it.
- Money is only for owners and managers. They are the only recipients for now,
  but keep the hours message and the money message separate so that widening
  the audience later cannot leak amounts.
- Where: a small `BudgetAlerts` bean in `report/`. `TimeService` either calls
  it or fires a CDI event that it observes, which keeps `time` from depending
  on `report`.
- Tricky: cost. `ProjectHealthService.budget` loads every time entry of the
  project and a full `RateBook` on each call. Doing that on every saved entry
  is wasteful on a long project. The hours threshold only needs a
  `sum(minutes)`. Money needs minutes per person, since the rate depends on
  project and person, so one grouped query.
- Web: an icon in `ICONS`. `notificationTarget` has nowhere to send a
  notification that has a project but no task, so add that case (to the
  project's overview tab).
- Tricky: a changed rate (on the project, a member, a role or the
  organisation) moves the money burn without any time being logged. Nothing
  fires at that moment. The next entry on the project picks it up.
- Undecided: who a project belongs to. There is no project lead, so every
  manager is told about every project, which is noise in a bigger team. A lead
  on `projects` would fix that, and would help the daily summary that item 12
  leaves as a follow-up.
- Undecided: alerts for the schedule (health turning off track, or the plan
  running past `Project.endDate` after a cascade). Those need hooks in
  `TaskService` as well. Not here.

### 11. Invitation and password reset emails

The app sends no email at all, and two things are broken without it. Inviting
somebody who has no account stores an `Invitation` and tells the manager that
the person "joins automatically when they sign up with that address", but the
person is never told. And a forgotten password cannot be reset:
`MeResource.changePassword` needs the current one, so the only way back in is
editing the database.

- Mail: add `quarkus-mailer`. Settings: `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM`
  and `APP_URL` (links need the web app's address, and the API does not know
  it). Mailpit as a `mail` service in `docker-compose.yml`, with its inbox on
  a host port listed with the other three in `.env.example`.
- Without `MAIL_HOST` nothing is sent and the app behaves as it does today. In
  dev and test `quarkus.mailer.mock` is on by default, and tests assert on the
  injected `MockMailbox`.
- Invitations: `OrgResource.invite` mails "Maya invited you to Brightline
  Studio" with a link to `/signup?email=...`. `SignupPage` reads the
  parameter. The router already binds query parameters to inputs
  (`withComponentInputBinding`, used by `LoginPage.returnUrl`). People who
  already have an account are added at once today. Mail them too. Add a
  "Resend" link beside "cancel" in `team.page.html`.
- Reset: `POST /api/auth/forgot` with an email always answers 204, whether the
  account exists or not. `POST /api/auth/reset` takes the token and a new
  password. A `password_resets` table holds the user, a SHA-256 of the token,
  an expiry of one hour and a used-at time
  (`V18__create_password_resets.sql`). New pages `/forgot` and `/reset` under
  `guestGuard`, and a link on `LoginPage`.
- Tricky: joining by typing an address. `AuthService.signup` accepts every
  pending invitation for the email it is given, and nothing proves that the
  person owns that address. Today that is the only way to join. Once
  invitations are mailed, the link can carry a token (a `token_hash` on
  `invitations`, in the same migration) and sign-up can insist on it. I lean
  towards requiring the token when mail is configured and keeping the current
  behaviour when it is not.
- Tricky: a reset does not end other sessions. Logins are stateless JWTs valid
  for `JWT_TTL` (seven days). Only `CurrentUser.get()` loads the user, and
  only `MeResource` calls it. Everything else goes through `OrgAccess` or
  `NotificationResource`, which ask `CurrentUser.id()` for the id in the token
  and never look at the user. A check in `get()` would leave old tokens valid
  on nearly every endpoint.
- So the check goes into `CurrentUser.id()`, the one method every signed-in
  request passes through (`get()` calls it too). Load the user there, once per
  request (the bean is request-scoped), and answer 401 when the token was
  issued before a new `password_changed_at` on `users` (same migration).
  `TokenService.issue` already puts an issued-at time in the token. Compare in
  whole seconds: issued-at has no fractions, and a login straight after the
  change must not be thrown out. The price is one user lookup per request. I
  would pay it, since `MeResource.changePassword` has the same hole.
- Tricky: `/api/auth/forgot` can be used to flood somebody's inbox, and
  nothing in the API is rate limited. One live token per user and a minimum
  gap between mails covers it without new machinery.
- Templates: plain text plus simple HTML, with Qute, which the mailer brings
  along.
- Undecided: verifying the address at sign-up. It would close the hole above
  for everybody, but the first run of a fresh install would then depend on
  working mail. I would not.

### 12. Notification emails and preferences

Notifications only exist inside the app, behind a bell that the web app polls
once a minute (`UNREAD_POLL_MS` in `Shell`). Somebody who is not looking at
the app does not learn that they were mentioned, given a task or had a
timesheet sent back. Depends on item 11 for the mailer and `APP_URL`.

- Every notification is created in one place, `Notifier.notify`. Do not send
  mail from there. It runs inside the request's transaction, so a slow mail
  server would slow the request down, and a rollback would come too late to
  take the mail back.
- Outbox instead: an `emailed_at` column on `notifications`, and a job that
  mails the rows that still need it and stamps them. `quarkus-scheduler`, once
  a minute. Several notifications for one person in the same run go into one
  mail. A notification that was read before the job ran (`read_at`) is not
  mailed.
- Preferences: which types are mailed, per user. A `notification_preferences`
  table (user, type, email on or off) with the defaults in code: on for
  `MENTIONED`, `ASSIGNED`, `TIMESHEET_REJECTED` and `TIMESHEET_REMINDER`, off
  for `COMMENTED`.
  `GET` and `PUT /api/me/notification-preferences`, and a card in
  `SettingsPage`. Both schema changes go in `V19__notification_emails.sql`.
- Links: `APP_URL` plus the targets `notificationTarget` returns, and a
  footer link to the settings card.
- Tests: switch the scheduler off in the test profile
  (`quarkus.scheduler.enabled=false`), call the job method directly and assert
  on `MockMailbox`.
- Tricky: rows that must never be mailed. An install that is upgraded has old
  notifications without `emailed_at`. Stamp those in the migration.
- The migration does not cover demo data. `DemoSeeder` runs after Flyway and
  creates its notifications through `Notifier.notify`, so a fresh install with
  `SEED_DEMO=true` would mail every unread demo notification the first time
  the job runs. `DemoSeeder.notification` already rewrites `createdAt` and
  `readAt` on each row it has just created. Set `emailedAt` there as well, and
  assert in `DemoSeederTest` that seeding leaves nothing for the job to send.
- Tricky: mentions leak task titles. `CommentResource.mentionedUsers` matches
  the handles against every membership of the organisation and never asks
  whether that person can see the project. A client viewer mentioned on
  another client's task gets "... mentioned you on" followed by the task's
  title in the app today, and would get it by mail. Fix that for mentions in
  general, as part of this item. The rule is in `OrgAccess.viewProject` (a
  client viewer only sees projects of their own client) but tied to the
  signed-in user. Give `OrgAccess` a check that takes any membership and a
  project, use it in `viewProject`, and have `mentionedUsers` skip the people
  who fail it. That closes the gap for the in-app notification too. With it in
  place, client viewers get mail like anybody else. The other types only ever
  go to team members.
- The suggestion list in `CommentBox` offers every member as well
  (`store.members()`). Filter it by the same rule, which the web app can do
  from the member's `clientId` and the project's.
- So the pull request is: the outbox column and the job, the preferences with
  their endpoint and settings card, the project check for mentions, and the
  seeding fix.
- Follow-up, not in this pull request: a daily summary ("due today",
  "overdue", and for managers "timesheets waiting"), which is the other half
  of what people expect. The queries exist in `DashboardResource`. It needs
  "eight in the morning in the organisation's time zone"
  (`Organisation.zone()`) and a record of what was sent, so that it does not
  repeat. The automatic timesheet reminders that item 3 leaves out belong to
  that follow-up too.
- Undecided: separate preferences per organisation for people who are in
  several. I lean towards no.

### 13. Duplicate a project as a template

Every client project in the demo data has the same shape: discovery, design,
build and launch, with the same kinds of tasks and links. A new project starts
empty, and that shape is typed in by hand each time. People asked Asana for
years for "copy a project and shift its dates". That is this feature.

- API: `POST /api/projects/{projectId}/duplicate` for managers
  (`access.manageProject` on the source) with a name, a code, an optional
  client, a start date and a "keep assignees" flag. It answers with the new
  project, which starts as `PLANNED`.
- Copied: phases, tasks, subtasks, milestones, dependencies with their lag,
  estimates, priorities, descriptions, the colour, the budgets and the fixed
  rate. Not copied: time entries, comments, activity and notifications. Every
  task starts again as `TODO` with `completedAt` empty.
- The order matters because of ids: phases first, then top-level tasks, then
  subtasks (`parentId`), then the `TaskDependency` rows from
  `TaskDependency.forProject`, each through a map from old id to new id.
- Dates: given a start date, every task keeps its distance from the source's
  earliest task start, and its length, both counted in working days
  (`WorkCalendar.workingDaysBetween`, `addWorkingDays`, `onOrAfter`). Nothing
  lands on a weekend and every link still holds. Tasks without dates stay
  without. The project's own start and end move the same way. Put the shift in
  a plain class in `schedule/` with its own test.
- Tricky: do not build the copy with `TaskService.create`. It writes an
  activity entry per task and notifies every assignee (`notifyAssigned`),
  which for a forty-task plan is forty notifications. Create the entities
  directly and record one activity entry for the whole copy.
- Tricky: positions. The board orders cards by `Task.position` within a
  status. With everything back in `TODO`, the copied numbers collide.
  Renumber in the order of the source's list. Phase positions can already
  have gaps or repeats, because the settings tab reorders with two separate
  `PUT /api/phases/{id}` calls. Copy the order, not the numbers.
- Tricky: assignees who have left. `TaskService.assigneeIn` only accepts
  current team members. With "keep assignees", drop the ones who are gone
  instead of failing.
- Web: in `ProjectsPage` the "New project" form (`ProjectForm`) gets a "Copy
  the plan from" select, and the start date becomes required when it is set. A
  "Duplicate" button on the project's Settings tab.
- Undecided: real templates, meaning projects that are flagged as templates
  and hidden everywhere else. The flag would have to be respected by the
  project list, the dashboard, the workload (`WorkloadResource` counts every
  planned and active project), time logging and search. Copying from any
  project, a finished one included, gets most of the value without that. I
  would stop there.

### 14. Performance on large projects

The demo data is eight projects, about ninety tasks and a year of time for
five people, and everything is instant. Some of the code only works at that
size. There are seven separate changes here, listed in the order I would do
them. The first four are the pull request. The last three are follow-ups, each
small enough for a pull request of its own.

In the pull request:

1. A query-count check, first, because it makes the rest checkable in CI
   without timing anything. Switch on Hibernate statistics in the test profile
   (`quarkus.hibernate-orm.statistics=true`) and assert that the number of
   statements for the project list with thirty projects stays under a fixed
   bound. Build that data in the test, not in `DemoSeeder`.
2. The project list and the dashboard. `ProjectResource.list` calls
   `ProjectHealthService.summary` per project, which loads all of its tasks,
   every one of its time entries (`TimeEntry.list("projectId", ...)`) and a
   fresh `RateBook` (the organisation, the role rates and all memberships).
   `ProjectService.toResponse` then looks up the client by id, and
   `DashboardResource` does the same for the active projects. Load the tasks
   and one grouped time query for all listed projects at once, with one
   `RateBook` per request.
3. A light project list for selects (id, code, name, status, client) under its
   own path or a query parameter. `TimesheetPage` and `ReportsPage` call the
   full list today only to fill a project select. Switch both.
4. People in one `id in` query, as `TaskMapper` already does, in the places
   that look them up one at a time: `TimesheetService.summaries` (which also
   queries the entries once per sheet), `OrgResource.members` and
   `CommentResource.mentionedUsers` (`User.findById` per member), and
   `MeResource.me` (the organisation per membership).

Follow-ups, not in this pull request:

5. Older entries in the two lists that only take a limit. `ActivityResource`
   (at most 200) and `NotificationResource` cannot be asked for anything
   older. A "before this id" parameter, and "Load more" in `ActivityTab` and
   `NotificationsPage`.
6. The timeline with many tasks. It draws every row and every bar.
   `layoutGantt` is recomputed when tasks, zoom or collapsed phases change and
   not during a drag, which is right, so with a thousand tasks it is the size
   of the DOM that hurts. Open with the phases collapsed above some task count
   first, and only then think about drawing just the visible rows.
7. Smaller things on the web side, and only with a measurement that says they
   matter: `groupTasks` copies the child array for every subtask and scans the
   phases for every task, `ProjectStore.changed` fetches the critical path
   again after each change, and the board reloads the whole task list after
   each drop (`reloadTasks`).

Notes for the pull request:

- Tricky: rounding again. `BudgetCalculator` adds up `Money.cost` per entry,
  rounded to cents each time. Feeding it one `TimeFacts` per person with
  summed minutes moves `spentAmount` by a few cents, and no test would
  notice: `roundsPartialHoursToCents` in `BudgetCalculatorTest` has a single
  entry. Before touching the query, add a case that pins rounding per entry:
  three one-minute entries at 100 an hour cost 1.67 each and 5.01 together,
  where three minutes in one entry cost 5.00.
- Keep rounding per entry, so that the budget, the CSV and item 9's report
  stay in step. The grouped query in step 2 can still be exact: group by the
  minutes value as well (project, user, billable, minutes, and a count), price
  one entry and multiply by the count.
- Item 12 changes `CommentResource.mentionedUsers` too, to skip people who
  cannot see the project. Whichever lands second keeps both changes.
- The indexes look right for today's queries. Add one only together with a
  query from steps 2 to 4 that needs it (`V20`).
- The target: an organisation with 50 projects, and a project with 1,000 tasks
  and 20,000 time entries, with the project list under ten statements.

### 15. Public holidays and time off

`WorkCalendar` knows weekends and nothing else. A task planned across
Christmas counts those days as working days, and the workload page shows forty
free hours for somebody who is on holiday. (The demo data even skips two
holiday stretches per person when it logs time, but nothing in the model says
so.) OpenProject has non-working days for the whole instance, and Float builds
its capacity view on leave and public holidays.

- Data (`V21__create_holidays_and_time_off.sql`): `holidays` (organisation,
  date, name, one per organisation and date) and `time_off` (organisation,
  user, first day, last day, note).
- API in a new `calendar` package: `/api/orgs/{orgId}/holidays` (the team
  reads, managers write) and `/api/orgs/{orgId}/time-off` (people manage their
  own, managers anybody's).
- Calendar: `WorkCalendar` gets a constructor that takes the non-working
  dates. `WorkCalendar.DEFAULT` stays for the tests and the seeder.
  `isWorkingDay` checks the set. `workingDaysBetween` has a fast path that
  counts five days per whole week, so subtract the holidays that fall on
  weekdays in the range.
- Four places build a helper on `WorkCalendar.DEFAULT` once, in a field, and
  need the organisation's calendar per request instead: `TaskService`
  (`rescheduler`), `ScheduleResource` (`criticalPath`), `ProjectHealthService`
  (`progressCalculator`, plus three direct calls) and `WorkloadResource`
  (`calculator`). Add a request-scoped loader so that one request reads the
  holidays once. The static `WorkCalendar.weekStart` is not affected.
- Item 1's `ScheduleCheck` takes a calendar as well. It is built in
  `ScheduleResource` next to `criticalPath` (the `scheduleCheck` field), so
  that class has two helpers to switch.
- Time off does not move the schedule. It only lowers capacity.
  `WorkloadCalculator.Person` has one `capacityHours` for all weeks. Make it
  per week (`WeekLoad` already reports capacity per week). A day off takes a
  fifth of the weekly capacity, and a holiday does the same for everybody. A
  task is still spread evenly over its own working days, not around its
  assignee's days off.
- Web: a holidays card in Settings for managers. Time off on the Team page or
  a page of its own. The timeline shades holidays like weekends: `layoutGantt`
  builds `weekends` from `isWeekend`, so pass the holiday dates in through its
  options. Workload cells show the lower capacity with a marker. The timesheet
  grid marks holidays in its header.
- Tricky: existing plans. Dates are stored and durations are worked out from
  them, so a new holiday inside a task makes it one working day shorter
  without moving anything, and can leave a task starting or ending on the
  holiday. Do not rewrite plans when a holiday is added. Dependants are only
  checked on the next move. If item 1 is in, it reports the links that now
  break.
- Tricky: `TimeService` lets people log time on any day and should keep doing
  so. People do work on holidays.
- Tricky: other items do new arithmetic with `WorkCalendar.DEFAULT`: the check
  in item 1, the working-day shift in item 8, capacity in item 9, the date
  shift in item 13 and the slip in item 16. Whichever lands second switches to
  the organisation's calendar. The timeline's own count of working days for a
  drag (item 8) needs the holiday dates too.
- Undecided, with my leaning in brackets: approval for time off (no, this is a
  planner and not an HR tool), half days (no), importing public holidays (a
  typed list of dates is enough), and one calendar per country for spread-out
  teams (one per organisation for now).

## Maybe

### 16. Baselines

Health compares the plan with today: `forecastEnd` is the latest due date and
`endVarianceDays` its distance from `Project.endDate`. What the plan looked
like when it was agreed is not kept anywhere. The activity log has
"Rescheduled ... to ..." as text and no more. A baseline is a saved copy of
the dates to compare against: the grey bar under each bar in MS Project.

- Data (`V22__create_baselines.sql`): `baselines` (project, name, created by,
  created at) and `baseline_tasks` (baseline, task id, title, start, due,
  estimate, milestone). The task id is a plain value without a foreign key, as
  in `activity_log`, and the title is copied, so that a task deleted later
  still shows up as removed.
- API: `GET` and `POST /api/projects/{projectId}/baselines` (creating needs
  `manageProject`), `DELETE /api/baselines/{id}`, and
  `GET /api/baselines/{id}/comparison`: per task the baseline dates and the
  current ones, the slip of start and finish in working days, the tasks added
  and removed since, and totals (slip of the end date, change in total
  estimate).
- The comparison is a plain class in `schedule/` with its own test.
- Web: a "Compare with" select on the timeline toolbar. `GanttBar` gets
  optional baseline coordinates and `gantt.html` draws a thin bar under the
  real one. `ROW_HEIGHT` is 32 and the bar is 18 high, so there is room.
  `dateRange` has to include the baseline dates, or the grey bars fall off the
  left edge.
- On the Overview tab, a card with the slip of the end date and a list of
  milestones (baseline date, current date, days late). Milestones are what a
  client asks about.
- "Save baseline" with a name ("Signed off 3 March") on the project's Settings
  tab.
- Tricky: tasks created after the baseline have nothing to compare with. Show
  them as new, not as on time. The same goes for tasks that had no dates then
  and have dates now.
- Tricky: client viewers see the plan (`viewProject`). Should they see how far
  it slipped from what was promised? I think yes, read-only, since it is their
  project. Make it a deliberate choice.
- Undecided: taking a baseline automatically when a project goes from planned
  to active (`ProjectService.update` already notices status changes). I lean
  towards yes, named "Start of project", because nobody remembers to take one
  until they need it. And a cap of about ten per project.
- Undecided: whether a small team looks at this more than twice a year. That
  is why it is under Maybe.

### 17. Dependency types and lead time

Every link is finish-to-start with a lag of zero or more working days. Real
plans also have "QA starts two days before development ends" (lead, which is a
negative lag) and "these two start together" (start-to-start). MS Project has
four link types. In practice finish-to-start with lead, and start-to-start,
cover nearly everything. Depends on item 1, because its check and this item
must share one rule.

- Model: a `type` on `task_dependencies` (`FS`, `SS`, `FF`, `SF`, default
  `FS`) and a lag that may be negative. `V23__add_dependency_type.sql` adds
  the column and replaces `ck_task_dependencies_lag` (`lag_days >= 0`) with a
  range. `Link` gets the type. `DependencyRequest.lagDays` is `@Min(0)` today.
- One rule in one place: given a link and the predecessor's dates, the
  earliest allowed start or finish of the successor. FS: start after the
  predecessor's due date plus lag. SS: start no earlier than its start plus
  lag. FF: finish no earlier than its finish plus lag. SF: finish no earlier
  than its start plus lag. `Rescheduler.earliestStart` becomes that rule. For
  the two finish-based types the task is moved so that its finish fits,
  keeping its length.
- `CriticalPath`: the forward pass (`ef + 1 + lag`) and the backward pass
  (`ls - 1 - lag`) both have finish-to-start written into them. Each needs the
  four cases. `CriticalPathTest` and `ReschedulerTest` cover FS. Add the same
  shapes for the other types.
- `DependencyGraph` does not change. A loop is a loop whatever the type, and
  one link per pair of tasks stays (`uq_task_dependencies`).
- `Rescheduler.propagate` still only moves tasks downstream of the change, and
  only later. With SS a change to the predecessor's start now matters, which
  works because the chain is recomputed from the current dates.
- Web: a type select and a lag that accepts negative numbers in the "Waits
  for" form of `task-panel.html`. The list says "+2 working days" today and
  needs wording for the rest ("starts with", "2 working days before the end
  of"). `linkPath` always runs from the right end of one bar to the left end
  of the next. SS leaves from the left end and FF arrives at the right end.
  `Dependency` in `models.ts` gets the type.
- Tricky: lead across a weekend. `addWorkingDays` accepts negative numbers,
  but returns its date unchanged for zero, weekend or not. `Rescheduler` gets
  around that today by calling `nextWorkingDay` first. Write the Friday and
  Monday tests before the code.
- Tricky: a lead longer than the predecessor makes the successor start before
  it. MS Project allows that. So would I.
- Tricky: the check from item 1 and its "Move to" fix must use the same rule,
  or valid start-to-start links get reported as broken.
- Undecided: all four types, or only FS and SS. SF is almost never used and
  doubles the test matrix. I would ship FS and SS with lead, and keep the
  column able to hold the others.

### 18. Attachments on tasks

A task has a description and plain-text comments, nothing else. The brief, the
mock-up and the signed-off PDF live in email or on a shared drive, with a link
pasted into a comment. Every comparable tool has attachments. The question is
whether file storage is worth it for a planner.

- Data (`V24__create_attachments.sql`): `attachments` (task, uploader, file
  name, content type, size, storage key, created at), deleted with the task.
- Storage: files on disk under `ATTACHMENTS_DIR`, on a new named volume in
  `docker-compose.yml`, saved under a random key and never under the uploaded
  name. S3-compatible storage can wait.
- API in a new `attachment` package: list and upload (multipart) at
  `/api/tasks/{taskId}/attachments`, `GET /api/attachments/{id}` for the
  bytes, and `DELETE /api/attachments/{id}`. Reading needs `viewProject`.
  Uploading needs `editProject`, or `viewProject` like commenting if client
  viewers may upload (see below). Deleting is for the uploader and for
  managers.
- Web: a section in `task-panel.html` between the subtasks and the comments,
  with the list, an upload button and a drop target. A download has to send
  the bearer token, which a plain link cannot do. `TimeApi.exportCsv` and
  `downloadBlob` in `reports.page.ts` already solve that for the CSV: fetch a
  blob and save it. Image previews work the same way with object URLs.
- Limits per file (say 20 MB) and per organisation. Two places cap the request
  size: Quarkus (`quarkus.http.limits.max-body-size`, 10 MB by default) and
  nginx, which has no `client_max_body_size` in `web/nginx.conf` and so stops
  at its default of 1 MB.
- Tricky: clean-up. Deleting a task or a project removes rows through foreign
  key cascades (`ProjectResource.delete` is a single `project.delete()`),
  which would leave the files on disk. Delete the files explicitly in
  `TaskService.delete` and before a project delete, and add a sweep for files
  without a row. Probably both.
- Tricky: serving other people's files. Always send
  `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff`. An
  uploaded SVG or HTML file shown inline would run script on the app's origin,
  where the login token sits in `localStorage`.
- Tricky: client viewers can read tasks and comment. Should they upload? I
  lean towards yes, with deleting limited to their own files. Sending the
  brief is what a client does.
- Tricky: backups. Item 6 dumps the database only. With attachments the volume
  has to be in the backup as well.
- Tests: a multipart upload with REST Assured against a temporary directory,
  then download, the size limit, permissions and clean-up.
- Undecided: whether this is worth it at all, when a link to the shared drive
  in the description works. Attachments on comments and pasted images are what
  people would ask for next, so it does not stay small.

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
and refuse the rest, save when they are left or on Enter, and Tab, Enter and
the arrow keys move between them. What a typed cell means is decided in
`timesheet-grid.ts` (`parseCell`, `cellAction`): create, update or delete
through the existing `/time-entries` endpoints, with the note and the billable
flag sent back on an update. Rows without time can be added, are kept per
organisation and week in `localStorage` (`timesheet-rows.ts`) and can be
copied from last week. Saved entries are put into the week on screen instead
of loading it again, also by the form and the list. Without `?week=` the page
asks the API for the current week, and `TimesheetResource.week` now works that
out from the organisation's date. Different from the design: a cell with
several entries is a button that points at them in the list, as planned, and
a cleared cell does not delete an entry that has a note either, because the
note can not be seen in the grid. One save runs per cell at a time
(`save-queue.ts`), the latest typed value wins, and "Submit week" waits for
saves that are still on their way. A row that loses its last entry is kept,
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
