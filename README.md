# project-planner

Project planning and delivery tracking for small teams and agencies. Plan a project
as phases, tasks and milestones with finish-to-start dependencies, see it on a
timeline with the critical path, log time against tasks, approve weekly timesheets
and keep an eye on schedule, budget burn and everyone's workload.

It is the planning-heavy sibling of a simple kanban board: the board is there, but
the point is knowing whether a project will land on time and on budget.

## Features

- **Organisations and roles**: owners, managers, members and client viewers. Invite
  people by email; anyone who already has an account is added straight away, others
  join automatically when they sign up with the invited address. Client viewers are
  tied to one client and only see that client's projects (read-only, but they can
  comment).
- **Clients and projects**: projects have a client (or are internal), a code like
  `ACME-WEB`, status (planned, active, on hold, done), start and end dates, an hours
  budget, a money budget and an optional fixed hourly rate.
- **Duplicate a project**: copy the plan of any project, a finished one included, into
  a new one: phases, tasks with their subtasks, milestones, links with their lag,
  estimates, priorities, descriptions, budgets, colour and rate. You pick the day the
  copy begins on, and every date keeps its distance from the first day of the plan in
  working days, so tasks are as long as they were and links hold as they did. Every
  task starts again as to do. Logged time, comments, notifications and activity are
  not copied, and neither are the client and the assignees unless you ask for them.
  The new project gets a code of its own, proposed and yours to change.
- **Baselines**: save the dates of a plan under a name ("Signed off 3 March") on the
  project's Settings tab, and compare the plan with it later. The timeline draws the
  baseline as a thin grey bar under each bar, says in words how far each task moved,
  and lists what changed: slipped, pulled in, added since and removed since, in working
  days. The Overview tab has the end of the project and of the plan against the
  baseline and a table of the milestones. A baseline never changes after it is saved.
  A project keeps up to ten, and one of them is the reference the timeline starts on.
  Everybody who can see the project sees the comparison, client viewers of its client
  included; only owners and managers save, rename, mark and delete baselines.
- **Files on tasks**: attach the brief, the mock-up or the signed-off PDF to a task, by
  button or by dropping files on the task panel. Images show a small preview. Whoever can
  see the project can see and attach files, client viewers of its client included, and a
  file is removed by whoever attached it or by an owner or a manager. Images (PNG, JPEG,
  GIF, WebP), PDF, plain text and office documents are taken, up to 20 MB each and 2 GB
  per organisation. Nothing a browser would run is taken: no SVG, no HTML.
- **Rates**: hourly rates per member, defaults per role and an organisation default.
  The most specific one wins (project → member → role → organisation). Money is only
  shown to owners and managers.
- **Phases, tasks, subtasks and milestones** with estimates, assignees, start and due
  dates, priority and status. Subtasks are one level deep; milestones are single-day
  markers.
- **Dependencies** of four types: finish-to-start ("starts after Design ends"),
  start-to-start ("starts with Design"), finish-to-finish ("ends with Design") and
  start-to-finish ("ends before Design starts"), each with a lag in working days, or a lead when it is negative
  ("QA starts two days before development ends"). Links that would create a loop are
  rejected with the chain that causes it, whatever their types. When a task moves,
  every task downstream of it that would now come too early is pushed back, keeping
  its length in working days. The type and the lag are picked in the task panel and
  can be changed there; the timeline draws each arrow from and to the bar ends its
  type names, with the type and lag beside it.
- **Schedule issues**: moving a task in front of a task it waits for is allowed,
  because planners overlap work on purpose, but the plan says so. Each project has a
  list of what does not fit: tasks that start too early for a dependency, links to
  tasks without a due date (those are not enforced), tasks due after the project's
  end date and subtasks outside their parent's dates. Finished tasks are left out.
  A task that starts too early comes with the dates that would fix it, and "Move to"
  applies them in one click. When those dates would put the task after the project's
  end or outside its parent, the button says so first.
- **Critical path** (CPM forward/backward pass over working days) with float per task.
- **Views**: list grouped by phase, kanban board with drag and drop, and an SVG
  timeline (Gantt) with dependency arrows, the critical path, today line, collapsible
  phases and day/week/month zoom. Dependencies with a schedule issue are drawn red
  and dashed, and the toolbar counts the issues and lists them.
- **Editing on the timeline**: drag a bar to move it, drag either end to change its
  start or due date, drag the dot after a bar onto another bar to make that one wait
  for it, and click an arrow to remove the link. Everything counts in working days: a
  bar dropped on a weekend goes on to the next working day in the direction of the
  drag, and a moved task keeps its length. While it is dragged, a bar is drawn where
  it will land. With the keyboard, the arrow keys move the focused bar by a working
  day, Shift with the arrow keys moves its due date (every press counts), Delete
  removes the focused arrow and Escape gives up a drag. Tab goes from a bar to the
  arrows that leave it. The timeline is edited at day and week zoom; at month zoom it
  is read-only.
- **Time tracking**: start/stop timers from a task or the top bar, manual entries,
  billable and non-billable time. Timers stopped after midnight book the time on the
  day they started.
- **Timesheets**: a weekly grid per person, one row per project and task and one cell
  per day. Time is typed into the cells as `1:30`, `1.5`, `1,5`, `90m` or `1h 30m` (a
  number on its own is hours). Anything else is refused, not guessed, and decimal hours
  are not rounded: `1.555` is refused, and `1.30` is an hour and eighteen minutes. A cell
  saves when you leave it or press Enter and shows what it read as hours and minutes
  (`1:18`) at once. The arrow keys move between cells, and rows can be added before
  they have time or copied from last week.
  - An empty cell creates an entry. A cell with one entry changes its time and keeps
    its note and billable flag. A cleared cell deletes the entry.
  - A change that can't be saved puts the saved time back, marks the cell and says why
    under the grid, and the week is read again. "Submit week" waits for cells that are
    still being saved and does not go ahead when one of them was refused.
  - An entry with a note is never deleted from the grid, and a cell with several
    entries or a running timer is not typed into at all. Those are changed in the list
    of entries below the grid, where everything about them can be seen.
  - Submit a week, managers approve it or send it back with a note. Submitted and
    approved weeks are locked: the grid shows them as text and the API refuses changes.
- **Missing timesheets**: for any week that is over, managers see who has not
  submitted it (drafts and weeks that were sent back) with the hours logged against
  capacity, and can remind one person or everybody who logged time. People without any
  time that week are listed apart, since they may have been away. Nobody gets a second
  reminder about the same week on the same day, or more than five reminders a day. The
  reminder, like every timesheet notification, opens the timesheet on the week it is
  about.
- **Workload**: allocated hours per person per week against their capacity, with
  overallocated weeks flagged. Each open task's estimate is spread over its working
  days.
- **Project health**: progress by estimate, planned vs earned hours (schedule variance
  and SPI), forecast end against the planned end, hours and money burn, overdue tasks,
  and an on track / at risk / off track status.
- **Search**: `Ctrl+K` (`⌘K` on a Mac), or the Search button in the top bar, opens a
  dialog that finds projects by name, code or client, tasks by title or description and
  comments by their text. Arrow keys and Enter open a hit, and the last five picks are
  there before anything is typed, with their current titles. Only what you can open is
  found, among the recent picks too. The List tab of a
  project has filters of its own: text, assignee, status and priority, kept in the
  address so that they survive a reload and the back button.
- **Comments with @mentions** (handles are the part of the email before the @, with
  autocomplete), in-app notifications, an activity log per project and organisation.
- **Budget alerts**: when a project's hours burn or money burn reaches 80% of its
  budget, and again when it reaches 100%, every owner and manager gets a notification
  that opens the project's overview. Hours and money are announced separately, and the
  hours message has no amounts in it. Each threshold is announced once. A burn that
  falls back under one (time deleted, the budget raised) can announce it again later.
  The check runs when time is logged, changed, moved, deleted or a timer stops, and when
  a budget or a rate changes. A project without a budget is never looked at.
- **Reports**: logged time per project, client, person or task for this or last week
  or month, or any range up to two years, with the billable time and, for owners and
  managers, the billable amount. A click on a project shows its tasks. Utilisation
  compares each person's billable time with their capacity. Members see their own
  time, without amounts. Time is counted in whole minutes and money to the cent, so
  every column adds up to its total, and both reports download as CSV with the rows
  on screen.
- **CSV export** of timesheets (rate and amount columns for managers), safe to open in
  a spreadsheet.
- **Backups**: with Docker, an optional service dumps the whole database once a day
  and keeps the last seven dumps, and the same script puts one back. See
  [Backup and restore](#backup-and-restore).
- **API reference**: an OpenAPI document that is built from the code, and an interactive
  page to try requests on. See [API reference](#api-reference).

Ideas for what could come next are in [ROADMAP.md](ROADMAP.md).

## Stack

| Part     | Tech                                                                                    |
| -------- | --------------------------------------------------------------------------------------- |
| API      | Java 21, Quarkus 3 (REST + Jackson, Hibernate ORM with Panache, Hibernate Validator, SmallRye JWT, SmallRye Health, SmallRye OpenAPI) |
| Database | PostgreSQL 17, Flyway migrations                                                        |
| Web      | Angular 22 (standalone components, signals, router), TypeScript, plain CSS              |
| Tests    | JUnit 5, AssertJ, `@QuarkusTest` + REST Assured on H2; Vitest (Angular unit-test builder, jsdom) |
| Tooling  | Maven wrapper, ESLint with angular-eslint, Docker Compose, nginx, GitHub Actions        |

## Architecture

```
browser ──> web (nginx :80) ──/api──> api (Quarkus :8080) ──> db (Postgres :5432)
             Angular build              JWT auth, Flyway on start
```

The browser only talks to the web container: nginx serves the Angular build and
forwards `/api` to the API. In development, the Angular dev server does the same
proxying (`web/proxy.conf.json`).

The API is organised by feature under `api/src/main/java/com/projectplanner`:
`auth`, `org` (organisations, members, invitations, rates), `client`, `project`
(projects and phases), `task` (tasks, dependencies, critical path and schedule
issue endpoints), `time`
(entries, timers, timesheets, CSV export), `workload`, `report` (project health,
time and utilisation reports),
`attachment`, `baseline`, `comment`, `notification`, `activity`, `dashboard`, `search` and `seed`. Resources are thin;
permission checks live in `OrgAccess`.

The scheduling and reporting rules are plain Java classes without any framework
dependencies, so they are tested in isolation:

| Class                 | What it does                                                         |
| --------------------- | -------------------------------------------------------------------- |
| `WorkCalendar`        | Working-day arithmetic (Mon–Fri): durations, lags, week starts       |
| `DependencyGraph`     | Cycle detection, reachability, topological order                     |
| `LinkRule`            | The one rule for what a link of each type asks of its successor, in dates and in critical-path offsets |
| `Rescheduler`         | Pushes dependants later when a task moves and shifts a task by working days, keeping durations |
| `PlanShift`           | Moves a whole plan to a new start, every date at its distance in working days |
| `BaselineComparison`  | Compares a plan with a baseline: slip of start and finish per task and phase, the ends, the estimates |
| `ScheduleCheck`       | Lists what does not fit in a plan, with the rescheduler's link rule  |
| `CriticalPath`        | Early/late start and finish, float and the critical tasks            |
| `ProgressCalculator`  | Progress by estimate, planned vs earned hours, SPI, overdue counts   |
| `BudgetCalculator`    | Hours and money burn against the budgets                             |
| `HealthStatus`        | Turns progress and burn into on track / at risk / off track          |
| `BudgetThresholds`    | The 80% and 100% budget alerts, once each per burn, upwards only     |
| `WorkloadCalculator`  | Allocated hours per person per week vs capacity, and capacity for a range |
| `TimeReport`          | Time per project, client, person or task, priced entry by entry in cents |
| `Utilisation`         | Billable time as a share of capacity, per person and in total        |
| `ReportRange`         | This and last week or month from the organisation's date, or two dates |
| `MissingTimesheets`   | Who has not submitted a finished week, and the limits on reminders   |
| `RateResolver`        | Picks the hourly rate for a piece of time                            |
| `MentionParser`       | Finds @handles in comments (and ignores email addresses)             |
| `CsvWriter`           | RFC 4180 CSV that neutralises spreadsheet formulas                   |

Authentication is stateless: `POST /api/auth/login` returns an HS256 JWT signed with
`JWT_SECRET`, which the web app keeps in `localStorage` and sends as
`Authorization: Bearer …`. Roles are per organisation, so they are looked up on each
request rather than stored in the token.

On the web side, `core/` holds the API clients, auth and the current-organisation
context; `features/` has one folder per page; the timeline lives in
`features/gantt` with its layout maths (`gantt-layout.ts`) and the arithmetic of a
drag (`gantt-edit.ts`: pixels to days, weekend snapping, handles, drop targets)
separate from the component so they can be unit tested. The weekly grid is split the
same way: `timesheet-grid.ts` says what a typed cell means, `save-queue.ts` runs one
save per cell at a time so that typing again during a slow save never makes a second
entry (and makes the keys that do the same for a create whose answer got lost), and
`timesheet-rows.ts` keeps the rows that have no time yet in `localStorage`, under
keys that start with the person's id (`core/user-storage.ts`). Signing out removes
them.

## Running with Docker

```bash
cp .env.example .env    # optional, the defaults work
docker compose up --build
```

Then open http://localhost:5191 and sign in with the demo account.

On first start the API runs the Flyway migrations and, because `SEED_DEMO=true`,
creates a demo agency, *Brightline Studio*, with eight projects at different stages
(two finished, four active including two internal ones, one on hold and one
planned), phases, milestones and about ninety tasks with dependencies, a year of
time entries for five people, timesheets
(older weeks approved, last week waiting for review, one sent back and one still a
draft with a reminder), comments with mentions and some notifications, among them the
budget alert of the Globex patient portal, which is over its hours budget. All dates are
relative to the day it runs. Set `SEED_DEMO=false` for an empty database.

Two of the demo projects have schedule issues to look at on their timeline: in the
Acme booking app "Push notifications" was pulled in front of "Booking and payments",
which it waits for, and the last tasks of the Globex patient portal are due after
the client's deadline. The Acme booking app also has a baseline, "Signed off at
kick-off", from which its build and release phases have slipped a week, so its timeline
has grey bars to compare with and its Overview a launch that is late.

| Service | Host port | URL                                                 | Notes                             |
| ------- | --------- | --------------------------------------------------- | --------------------------------- |
| web     | 5191      | http://localhost:5191                               | nginx; proxies `/api` to the API  |
| api     | 4411      | http://localhost:4411/q/health                      | Quarkus; with `API_DOCS=true` the API reference is at http://localhost:5191/api/docs |
| db      | 5451      | `postgresql://planner:planner@localhost:5451/planner` | Postgres 17                     |
| backup  | none      |                                                     | optional, daily dumps to `./backups` |

Ports can be changed with `WEB_PORT`, `API_PORT` and `DB_PORT` (in `.env` or inline,
e.g. `WEB_PORT=8081 docker compose up`). Data lives in the `db-data` volume and the
files attached to tasks in the `attachments` volume; `docker compose down -v` removes
both. The dumps of the backup service are files in
`./backups` and stay.

Quick check from the command line:

```bash
curl http://localhost:4411/q/health/ready
curl -X POST http://localhost:4411/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.com","password":"demo-password"}'
```

### Demo logins

All demo accounts use the password `demo-password`.

| Email                      | Name          | Role                              |
| -------------------------- | ------------- | --------------------------------- |
| `demo@example.com`         | Maya Collins  | Owner                             |
| `priya@example.com`        | Priya Shah    | Manager                           |
| `tom@example.com`          | Tom Becker    | Member (designer)                 |
| `lena@example.com`         | Lena Novak    | Member, 32 h/week                 |
| `samir@example.com`        | Samir Haddad  | Member                            |
| `jordan@northwind.example` | Jordan Reyes  | Client viewer for Northwind Traders |

## Backup and restore

Everything is in two volumes: `db-data` for the database and `attachments` for the files
attached to tasks. The timesheet CSV is the only export. For a full copy there is a
`backup` service that dumps the whole database with `pg_dump` and archives the files
with it. It
is behind a Compose profile, so it only runs when asked for:

```bash
docker compose --profile backup up -d
```

To have it start with every `docker compose up`, put `COMPOSE_PROFILES=backup` in
`.env`.

Dumps land in `./backups` as `planner-<UTC date and time>.dump`, in `pg_dump`'s custom
format, each with `planner-<the same time>.files.tar` next to it: the attachments as they
were right after the dump. A dump only counts once its archive is written, and the two
are pruned together. Files are only ever added or removed, so the minute between the two
can at worst leave an extra file in the archive, which the API's sweep removes, or a file
missing that was removed in that minute, which then answers 404. When the service starts it makes one, unless the newest dump is less than a day
old, so restarting the stack does not add dumps. On a new stack it first waits for the
API to create the tables. After that it makes one dump a day. It looks at the clock once
a minute instead of sleeping for a whole day, so a machine that was suspended or
switched off catches up when it is back.

After each dump only the newest seven are kept. Files with any other name are left
alone: rename a dump to keep it for good. How old a dump is, and which ones are the
oldest, is read from the time in the file name and never from the file's modification
time. A dump dated in the future, which is what a clock that was set back leaves behind,
counts as made now and not as overdue.

The folder, the number of dumps and the time between two of them are `BACKUP_DIR`,
`BACKUP_KEEP` and `BACKUP_INTERVAL_SECONDS` (see [Configuration](#configuration)).

A dump right now, for example before an upgrade:

```bash
docker compose --profile backup run --rm backup once
```

To restore, stop the API, name the dump and start the API again:

```bash
docker compose stop api
docker compose --profile backup run --rm backup restore planner-20261120T030000Z.dump
docker compose start api
```

`restore` without a file name lists the dumps. The restore replaces the whole database,
and the attachments with what the archive next to the dump holds, so everything written
after the dump is gone. The archive is unpacked first, next to the files in use, so one
that cannot be read stops the restore before anything changed. The restored files are
given to the API's user (`ATTACHMENTS_OWNER`, `1000:1000`). A dump without an archive,
from before attachments, leaves the files as they are. The dump is loaded into a new database
first, and that one takes the place of the old one only when the load worked: a damaged
file, or an API that is still running, leaves the data as it was. The two swap names in
one transaction and the old database is dropped after that, so a restore that is killed
half way never leaves you without a database. On a new volume there is nothing to swap
with. Should giving the restored database its name fail there, the script says where
the data is and prints the command that finishes the job.

After losing the volume, or on another machine, it is the same command: put the dump in
`./backups`, run the `restore` line (it starts the database by itself), then
`docker compose up -d`.

### Dumps and versions of the API

A dump carries the schema and Flyway's list of the migrations that built it
(`flyway_schema_history`), so it belongs to the version of the API that was running
when it was made.

- **An older dump under a newer API works.** When the API starts it applies the
  migrations the dump does not have. That includes one with a lower number than the
  newest in the dump, because Flyway runs out of order here.
- **A newer dump under an older API is refused.** Flyway finds a migration in the dump
  that the API has no file for, and the API stops during startup with
  `Detected applied migration not resolved locally`. The data is not harmed by that.
  Start the version the dump came from, or a newer one.

So restore into the same version or a newer one. Before an upgrade, make a dump with
`once`. If the upgrade has to be taken back, restore that dump under the old version:
a dump made after the upgrade will not start there.

Good to know:

- A dump holds the password hashes, the rates and every hour that was logged, and it is
  not encrypted. The files can only be read by their owner.
- The service runs as root inside its container, like everything in the postgres image
  except the server, and gives each dump to the owner of the folder it is written to.
  `./backups` comes with the repository, so on Linux the dumps are yours and not
  root's. If `BACKUP_DIR` names a folder that does not exist, Docker creates it as root
  and the dumps belong to root as well: create the folder yourself first.
- The dumps sit on the same disk as the database unless `BACKUP_DIR` points somewhere
  else. Copying them to another machine is up to you.
- Nothing warns you when a dump fails. `docker compose logs backup` shows every dump and
  every failure, and a failed dump is tried again a minute later.
- `docker compose down -v` removes the database and attachments volumes, not the dumps.

## Running without Docker

You need Java 21, Node 22 and a PostgreSQL database. The easiest way to get the
database is still Docker:

```bash
docker compose up -d db
```

API (http://localhost:8080):

```bash
cd api
SEED_DEMO=true ./mvnw quarkus:dev
```

It connects to `jdbc:postgresql://localhost:5451/planner` as `planner`/`planner` by
default. Override with `DATABASE_URL`, `DATABASE_USER` and `DATABASE_PASSWORD`.

Web (http://localhost:4200):

```bash
cd web
npm install
npm start
```

### Configuration

| Variable               | Default                                       | Used by |
| ---------------------- | --------------------------------------------- | ------- |
| `DATABASE_URL`         | `jdbc:postgresql://localhost:5451/planner`    | api     |
| `DATABASE_USER`        | `planner`                                     | api     |
| `DATABASE_PASSWORD`    | `planner`                                     | api     |
| `JWT_SECRET`           | a development value (min. 32 bytes)           | api     |
| `JWT_TTL`              | `P7D` (ISO-8601 duration)                     | api     |
| `SEED_DEMO`            | `false` (`true` in compose)                   | api     |
| `API_DOCS`             | `false` (`true` in `.env.example`)            | api     |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://localhost:5191` | api     |
| `PORT`                 | `8080`                                        | api     |
| `WEB_PORT`, `API_PORT`, `DB_PORT` | `5191`, `4411`, `5451`             | compose |
| `ATTACHMENTS_DIR`      | `data/attachments` (`/data/attachments` in compose) | api |
| `ATTACHMENTS_MAX_FILE_BYTES` | `20971520` (20 MB)                      | api     |
| `ATTACHMENTS_MAX_ORGANISATION_BYTES` | `2147483648` (2 GB)             | api     |
| `BACKUP_DIR`           | `./backups`                                   | backup  |
| `BACKUP_KEEP`          | `7`                                           | backup  |
| `BACKUP_INTERVAL_SECONDS` | `86400` (one day)                          | backup  |

`ATTACHMENTS_DIR` is where the API keeps the files attached to tasks; in compose it is
the `attachments` volume. Requests can be up to 25 MB, in Quarkus and in nginx, so that a
file somewhat over the limit gets the API's own answer.

`BACKUP_DIR` is the folder on the host the dumps go to, `BACKUP_KEEP` how many of them
are kept and `BACKUP_INTERVAL_SECONDS` the time from one dump to the next. See
[Backup and restore](#backup-and-restore).

## Tests and lint

```bash
cd api && ./mvnw verify      # unit tests + API tests against in-memory H2
cd web && npm test           # Vitest in jsdom, no browser needed
cd web && npm run lint       # ESLint with angular-eslint
```

The scheduling, schedule check, critical path, cycle detection, budget, progress,
workload and missing timesheet tests are plain JUnit tests and need no database. The
`@QuarkusTest` API tests start the application against H2 in PostgreSQL mode and run
the same Flyway migrations as production.

A restore that is never tested is not a backup, so CI also runs
`scripts/check-restore.sh` once the images are built. It is the only check that starts
the stack on PostgreSQL. On a Compose project of its own, with its own volume, it:

1. starts `db` and `api` with the demo data and waits for the health checks and the
   seed;
2. runs `scripts/check-backup.sh` in the backup service: three dumps while keeping two,
   a restore of the newest into a scratch database, and the row count of every table
   compared between the two (`users`, `tasks`, `time_entries` and `timesheets` must not
   be empty). Each dump has to have its archive of attachments, and the restore has to
   put back exactly the archived files in a folder that held other ones;
3. puts a file on the attachments volume, takes one migration back by hand, dumps,
   removes the volumes and restores the dump with the commands from above. The row
   counts and the file, owned by the API's user, have to be back before the API has run.
   Then the API is started without the demo seed and has to apply the missing migration;
4. checks that the API refuses to start on a database from a newer version.

```bash
sh scripts/check-restore.sh          # needs Docker; stop your stack first, or set DB_PORT and API_PORT
sh scripts/check-restore-guards.sh   # needs nothing
sh scripts/check-backup-loop.sh      # needs nothing
```

The check drops a column and removes a database volume, so it is careful about whose.
It reads `docker-compose.yml` and nothing else: an override file or a `COMPOSE_FILE`
could put the database on storage that is in use. It refuses the project name the stack
itself runs under (CI is the exception, there is no data there), a project that already
has containers or a volume, and a Docker that does not answer. It creates the `db`
container first and only starts it once its data is seen to be on the volume the project
has just made. If you interrupt it, it removes what it started. Should a run leave
something behind anyway, the next one says so and prints the command that removes it.
`scripts/check-restore-guards.sh` goes through those refusals with a stand-in for
`docker` and is run by CI as well.

`check-backup.sh` starts with `scripts/check-backup-loop.sh`, which needs neither Docker
nor a database. It drives the daily loop with stand-ins for `pg_dump` and `sleep` and
with dump files dated in the past and in the future, and fails if the loop makes more
than one dump, makes two without a pause, keeps a dump that was cut short, or leaves
fewer dumps behind than it should.

The [API reference](#api-reference) is held to the code by `ApiReferenceTest`. Every
resource method has to be in the document with a summary, a tag, the status it really
answers with and the error body, and everything but sign-up and login has to ask for
the token. Two of its rules reach into every change to an endpoint:

**Error codes.** A resource method names every error code it can answer with for a
reason of its own, with a line like
`@Fails(status = 409, code = "code_taken", when = "another project already uses the code")`.
The test reads the codes from the sources (`ApiException.badRequest`,
`ApiException.conflict` and `new ApiException`, as a string literal or a string
constant) and compares per resource: a code has to be named, with the status it is
thrown with, on a method of a resource that reaches the class where it is thrown, and a
resource cannot name a code that nothing it reaches throws. A resource reaches the
classes its source mentions and the ones those mention. A code the test cannot read,
for example one that is put together at run time, fails it too. The message lists only
what is wrong, each with its file and line and with what to do about it. The codes any
endpoint can answer with (`validation_failed`, `not_found` and so on) are not named per
method: they are in the introduction, `api/src/main/resources/META-INF/openapi.yaml`.

**The shape.** `api/src/test/resources/api-shape.txt` pins the shape of the document:
every path, parameter, response status and schema, one fact per line (a line per enum
value and per required field), without the prose. The test fails when the shape
changes, so that no field or status changes by accident, and prints the lines that are
gone and the ones that are new. When the change is meant, refresh the file and commit
the difference with the change:

```bash
cd api && ./mvnw -q test -Dtest=ApiReferenceTest -Dapi.shape=update
```

After a merge that conflicts in that file, take either side and run the same command.

Schemas are named after their class. Two records with the same class name that both
end up in the API stop the build, with a message that names them: give one of them a
name of its own in `SCHEMA_NAMES` in `ApiReferenceFilter`.

## API reference

All endpoints are under `/api`. What they take and what they answer is written down in
an OpenAPI document. It is built from the resource classes and their records when the
API is built, so it cannot fall behind the code:

- `/api/openapi` is the document, in YAML. `/api/openapi.json` is the same in JSON. It
  is always served and needs no token.
- `/api/docs` is the same as a page (Swagger UI): every endpoint by area, with its
  fields, its errors and who may call it. "Authorize" takes the token from
  `POST /api/auth/login`, and then requests can be tried from the page. It is served
  when `API_DOCS=true`.

With Docker both go through the web container: http://localhost:5191/api/docs. The
page is off unless `API_DOCS=true`, with Compose as well, because Compose is also how the
app runs for real and the page is third-party script on the address where the web app
keeps the login token. For development, turn it on: `.env.example` has the line, or pass
it inline (`API_DOCS=true docker compose up`, or `API_DOCS=true ./mvnw quarkus:dev`
without Docker). The page loads nothing from other sites and does not ask the Swagger
validator about the document. The document itself stays either way: it says nothing
that the source does not, and it only describes `/api`.

The rules that hold for every endpoint:

- Apart from sign-up and login, a request needs a token: `Authorization: Bearer <token>`.
- Errors are JSON: `{ "status", "code", "message", "errors"? }`. The `code` is stable.
  The reference lists the codes of each endpoint and the ones that any endpoint can
  answer with.
- An organisation you are not in answers 404. So does a project of another client for
  a client viewer.
- `PATCH` changes only the fields that are sent. Taking a value away has a flag of its
  own (`clearDates`, `clearAssignee`, `clearPhase`, `clearHourlyRate`).
- Rates, amounts and money budgets are left out of the answers for members and client
  viewers.

The rest of this section is what the reference only mentions in passing.

`GET /orgs/{org}/search?q=&limit=` matches the text as it is, wherever it occurs and
without regard to case: a `%` or `_` in it stands for itself, and there are no words,
no stemming and no ranking beyond "the start of a name first, open tasks before done
ones". It answers with at most `limit` projects, tasks and comments each (8 unless
asked, 20 at most), and with nothing for less than two characters. Underneath it is
`lower(...) like '%text%'` over the projects, tasks and comments of the organisation,
because that is what PostgreSQL and the H2 database of the tests have in common. No
index helps a search for text in the middle, so every search reads all of the
organisation's tasks and comments. That is fine for a few thousand tasks, and it is
not a search engine. On PostgreSQL alone a trigram index would be the next step.

Task updates that change dates return `{ task, rescheduled: [...] }` so the client can
show which dependants were pushed back.

`POST /tasks/{id}/shift` takes `{ "days": n }` in working days, negative for earlier.
The start moves by that many working days and the due date keeps the task's length in
working days, so a Thursday to Friday task shifted by 1 runs Friday to Monday. The
result is not pulled onto a working day afterwards: a Monday start shifted by -1 is
the Friday before.

`POST /projects/{id}/duplicate` copies the plan of a project into a new one and answers
with it (`201`, status `PLANNED`). It is for owners and managers who can see the source.
The body is `{ "name", "code", "startDate", "clientId"?, "keepAssignees"? }`: without
`clientId` the copy is internal, and without `keepAssignees` nobody is assigned. With it,
people who have left the team are dropped instead of the copy failing. A code that is
in use answers `409 code_taken`, also when another request took it a moment earlier.
A task or phase deleted from the source while it is being copied answers
`409 source_changed`, and nothing is copied. `GET /projects/{id}/duplicate` proposes
`{ name, code, startDate }` (the code is free at that moment) and says how many phases,
tasks and links there are to copy.

The copy is made in one transaction, so it is there as a whole or not at all. It takes
the same number of statements for twenty tasks as for a few thousand (each table is
filled by one statement, and by one more for every further 5,000 rows). The first day of the plan
(the earliest task date or the project's own start) lands on `startDate`, or on the next
working day, and everything else keeps its distance from it in working days. So the
schedule issues of the copy are those of the source's dates, no more and no fewer.
The copy has one activity entry. Its text does not say which project the plan came
from, because a copy can be made for another client: `relatedProjectId` and
`relatedProjectName` are only in the entry for a reader who may see the source.

Two things to know: tasks that were finished in the source are open again in the copy,
so an overlap that the source no longer reports comes back. And a date on a weekend is
taken as the working day it stands for. A task that lies on a weekend entirely becomes
a one-day task on the next working day, which is how the scheduling rules read it in the
source too.

`POST /projects/{id}/baselines` with `{ "name", "reference"? }` saves the dates of every
task and phase and the project's own start and end as they are now (`201`). The rows
are copied inside the database by one statement per table, so a plan of a few thousand
tasks takes as many statements as a small one, and the caller's transaction holds them
together. A baseline keeps the task's id, title, phase name, dates, estimate and whether
it is a milestone, as plain values: a task deleted later is still in it. A project keeps
up to ten (`409 baseline_limit`), names are unique within the project
(`409 baseline_name_taken`), and the first baseline is the reference.
`PATCH /baselines/{id}` renames one or makes it the reference (`{ "reference": true }`),
and `DELETE /baselines/{id}` deletes it; when that was the reference, the latest one left
takes over. These three are for owners and managers. Each locks the project's row first,
so two that arrive together run one after the other: the limit, the names and the one
reference hold, and the database has unique constraints on the name and the reference
behind that. `GET /projects/{id}/baselines` and
`GET /baselines/{id}/comparison` are for everybody who can see the project.

The comparison has an entry per task and per phase that is in the plan or in the
baseline, with the dates of both and `startSlipDays` and `finishSlipDays` in working
days (later is positive), plus `projectEnd` (the project's own end date) and `planEnd`
(the latest due date), the counts of each change, and the total estimate then and now.
`change` is one of `UNCHANGED`, `SLIPPED`, `PULLED_IN`, `ADDED` and `REMOVED`. The finish
decides, and the start only when the finish did not move. A date on a weekend counts as
the working day it stands for, so a due date moved from Friday to Saturday has not
slipped. A task that had no dates in the baseline and has them now is `ADDED` with
`existedThen: true`; one that is gone is `REMOVED` with `existsNow: false`. Whether a task
exists then and now is decided first, so a task created or deleted since is `ADDED` or
`REMOVED` even when it has no dates. The schedule
issues and the critical path look at the plan only, never at a baseline, and duplicating
a project does not copy its baselines.

`POST /tasks/{id}/dependencies` takes `{ "predecessorId", "lagDays"?, "type"? }`, and
`PATCH /dependencies/{id}` changes `type` and `lagDays` of an existing link. `type` is
`FS` (the default), `SS`, `FF` or `SF`, and `lagDays` runs from -365 to 365 working days.
Every check of a link goes through `LinkRule`: FS lets the successor start the working
day after the predecessor is due plus the lag, SS when the predecessor starts plus the
lag, FF lets it be due when the predecessor is due plus the lag, and SF on the working
day before the predecessor starts plus the lag, the mirror of FS (a lag of 1 lets the
two share that first day). A task on days off only, a Saturday milestone for instance,
counts as a one-day task on the next working day, in the rescheduler, the check and the
critical path alike. A link on the finish moves the successor whole, so it
keeps its length. A lead longer than the predecessor puts the successor before it,
which is allowed. Changing a link pushes the successor later when it now comes too
early, and never pulls anything earlier. Links that existed before were all
finish-to-start, and are that still.

Attachments: `GET` and `POST /tasks/{id}/attachments` list a task's files and take one
as the multipart form field `file`; `DELETE /attachments/{id}` removes one. What a file
is is worked out from its first bytes, which have to match its name, against a fixed
list; the type the browser sends is ignored. A file that is not on the list, or is not
what its name says, is `415 unsupported_file_type`. Above 20 MB it is
`413 file_too_large`, and past the organisation's 2 GB `413 storage_full`. Files are
stored under a random key, never under their name.

The bytes are not behind the bearer token, since a plain link or an `<img>` cannot send
it. `POST /attachments/{id}/link` checks that the caller may see the project and answers
with a path that works for two minutes without a token: it carries the attachment, the
user, the expiry and whether to show the file in the page, with an HMAC over them (the
key is derived from `JWT_SECRET`). `GET /attachments/{id}/content` checks that HMAC and
the expiry, `403 link_invalid` otherwise. Every answer has `Content-Disposition`
(`attachment`, or `inline` for the four raster image types when a preview was asked
for), `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none';
sandbox` and `Cache-Control: private, no-store`.

Deleting an attachment, a task or a project deletes the files once the deletion is
committed; a failed request keeps them. A file written for an upload that is not
committed goes again. Every six hours a sweep removes files that no row knows and that
are older than an hour. `POST /projects/{id}/duplicate` copies the files only with
`copyAttachments: true`, each under a key of its own; `GET` on the same path says how
many there are (`attachments`, `attachmentBytes`).

`GET /projects/{id}/schedule-issues` is open to everyone who can see the project and
returns a list of `{ type, taskId, relatedTaskId?, days?, suggestedStart?, suggestedDue?, suggestionCauses?, linkType? }`,
ordered by the date of the task:

| Type                    | Meaning                                                                  | `relatedTaskId` | `days` (working days)   |
| ----------------------- | ------------------------------------------------------------------------ | --------------- | ----------------------- |
| `STARTS_TOO_EARLY`      | The task is earlier than a link allows (`linkType` says which end counts) | the predecessor | how many days too early |
| `WAITS_FOR_UNSCHEDULED` | The task waits for a task without a due date, so the link is not enforced | the predecessor | -                       |
| `AFTER_PROJECT_END`     | The task is due after the project's end date                             | -               | how many days late      |
| `OUTSIDE_PARENT`        | The subtask has days before its parent starts or after it is due         | the parent      | how many days outside   |

Only `STARTS_TOO_EARLY` has `suggestedStart` and `suggestedDue`: the first day all of
the task's predecessors allow, and the due date that keeps its length in working days.
They are the dates the rescheduler would give the task, so a client fixes the issue by
sending both back in `PATCH /tasks/{id}` and does no calendar arithmetic of its own.
A task with several broken links gets one issue per link, all with the same dates.

The suggestion only looks at the dependencies. When moving the task there would get it
reported for something it is not reported for today, `suggestionCauses` lists that:
`AFTER_PROJECT_END`, `OUTSIDE_PARENT` or both, worked out by the rules that report those
issues. It says nothing about the tasks that the move pushes in turn.

Finished tasks are never in the list, but the critical path does not make that
exception: it still moves a finished task that overlapped a task it waited for. So the
`finish` of the critical path can be later than the latest due date while this list is
empty. The timeline says which of the two it is in a line under its toolbar.

`GET .../timesheets/week` takes `?date=` (any day of the week) and, for owners and
managers, `?userId=`. Without a date it is the current week by the organisation's date.
The weekly grid needs no endpoints of its own: a cell is a `POST`, `PUT` or `DELETE` on
`/orgs/{org}/time-entries`. A `PUT` replaces the entry, so the note has to be sent back
with it. A week that is submitted or approved answers `409 timesheet_locked` to
everything that would change its time: a new entry, a change, an entry moved into or
out of it, a delete, and stopping a timer that was started in it. A running timer
keeps its day until it is stopped, and its week can't be submitted while it runs.

A create can carry a `clientKey` (8 to 64 characters, made up by the client). The same
key from the same person for the same project, task and day is taken as the same
request: the answer is the entry that was made the first time, as it is now, and no
second one is made. That makes it safe to send a create again when its answer never
arrived. The web app does so for every new entry. A key that was used for something
else answers `409 client_key_reused`.

`GET .../timesheets/missing` and `POST .../timesheets/remind` are for owners and
managers and only take weeks that are over by the organisation's date
(`400 week_not_over` otherwise). A reminder request names the week and the people,
`{ "weekStart", "userIds": [...] }`, and the answer says who was reminded and who was
left out and why: `{ "reminded": [...], "skipped": [{ "userId", "reason" }] }` with the
reasons `NOT_MISSING`, `YOURSELF`, `ALREADY_REMINDED` and `DAILY_LIMIT`.

### Reports

`GET .../reports/time` and `GET .../reports/utilisation` take a range: either
`preset=this_week|last_week|this_month|last_month`, which the API works out from the
organisation's date, or `from` and `to`. Both days are included, a week is Monday to
Sunday, a month runs from its first to its last day, and a range covers at most two
years. The answer says which dates it used.

The time report also takes `groupBy=project|client|member|task` and the filters
`projectId`, `clientId`, `userId` and `billable`. Each row has a `key`, a `label`,
`minutes`, `billableMinutes` and, for owners and managers, `billableAmount`, and
`total` is the sum of the rows. Running timers are left out.

- Rounding: time is whole minutes. Money is priced one entry at a time, minutes at the
  hourly rate rounded half up to a cent, exactly as the budget and the entry export do
  it. Three entries of twenty minutes at 100 an hour are 99.99, not 100.00. Nothing is
  rounded after adding up, so a total is the sum of its rows to the cent.
- The database does the adding up. Entries are grouped by project, person, billable
  flag and length (and task, when rows are per task) and counted, and one entry of
  each group is priced. A year of the demo team is a few hundred groups.
- Access: the team only, client viewers get `403`. A member gets their own time and
  no amounts (the field is left out), and `403` when asking for somebody else. A
  project, client or person that is not in the organisation is a `404`, never an
  empty report. Somebody who has left the organisation but has time in it can still
  be asked for with `userId`, as they still have a row when the report is per person.
- Utilisation is for owners and managers. A row has `minutes`, `billableMinutes`,
  `capacityMinutes` and `utilisationPct`, which is billable time as a share of
  capacity with one decimal. Capacity is the one the workload page uses: a person's
  weekly hours spread over Monday to Friday. Time and capacity are both counted from
  the start of the range up to its end or today, whichever comes first (`countedTo`,
  `workingDays`), so time that is logged ahead does not count against days that have
  no capacity yet. It uses the weekly hours as they are now, also for somebody who
  joined or changed them inside the range, and it does not know about holidays or
  time off. Only the current team is listed: the time of somebody who has left is in
  the time report and not here, so the two totals can differ.
- The `.csv` forms have the same rows and the same access rules. Columns:
  `from,to,group_by,key,label,minutes,billable_minutes,billable_amount` (the last one
  only for owners and managers) and
  `from,to,member,minutes,billable_minutes,capacity_minutes,utilisation_pct`. Dates
  are ISO dates, time is whole minutes so that a column can be summed, amounts are
  plain decimals, and a text cell that starts with `=`, `+`, `-` or `@` gets an
  apostrophe in front so that a spreadsheet does not run it.

## License

MIT, see [LICENSE](LICENSE).
