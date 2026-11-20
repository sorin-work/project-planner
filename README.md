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
- **Rates**: hourly rates per member, defaults per role and an organisation default.
  The most specific one wins (project → member → role → organisation). Money is only
  shown to owners and managers.
- **Phases, tasks, subtasks and milestones** with estimates, assignees, start and due
  dates, priority and status. Subtasks are one level deep; milestones are single-day
  markers.
- **Dependencies** (finish-to-start, with an optional lag in working days). Links that
  would create a loop are rejected with the chain that causes it. When a task moves,
  every task downstream of it that would now start too early is pushed back,
  keeping its length in working days.
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
- **Timesheets**: a weekly grid per person; submit a week, managers approve it or send
  it back with a note. Submitted and approved weeks are locked.
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
- **Comments with @mentions** (handles are the part of the email before the @, with
  autocomplete), in-app notifications, an activity log per project and organisation.
- **CSV export** of timesheets (rate and amount columns for managers), safe to open in
  a spreadsheet.
- **Backups**: with Docker, an optional service dumps the whole database once a day
  and keeps the last seven dumps, and the same script puts one back. See
  [Backup and restore](#backup-and-restore).

Ideas for what could come next are in [ROADMAP.md](ROADMAP.md).

## Stack

| Part     | Tech                                                                                    |
| -------- | --------------------------------------------------------------------------------------- |
| API      | Java 21, Quarkus 3 (REST + Jackson, Hibernate ORM with Panache, Hibernate Validator, SmallRye JWT, SmallRye Health) |
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
(entries, timers, timesheets, CSV export), `workload`, `report` (project health),
`comment`, `notification`, `activity`, `dashboard` and `seed`. Resources are thin;
permission checks live in `OrgAccess`.

The scheduling and reporting rules are plain Java classes without any framework
dependencies, so they are tested in isolation:

| Class                 | What it does                                                         |
| --------------------- | -------------------------------------------------------------------- |
| `WorkCalendar`        | Working-day arithmetic (Mon–Fri): durations, lags, week starts       |
| `DependencyGraph`     | Cycle detection, reachability, topological order                     |
| `Rescheduler`         | Pushes dependants later when a task moves and shifts a task by working days, keeping durations |
| `PlanShift`           | Moves a whole plan to a new start, every date at its distance in working days |
| `ScheduleCheck`       | Lists what does not fit in a plan, with the rescheduler's link rule  |
| `CriticalPath`        | Early/late start and finish, float and the critical tasks            |
| `ProgressCalculator`  | Progress by estimate, planned vs earned hours, SPI, overdue counts   |
| `BudgetCalculator`    | Hours and money burn against the budgets                             |
| `HealthStatus`        | Turns progress and burn into on track / at risk / off track          |
| `WorkloadCalculator`  | Allocated hours per person per week vs capacity                      |
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
separate from the component so they can be unit tested.

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
draft with a reminder), comments with mentions and some notifications. All dates are
relative to the day it runs. Set `SEED_DEMO=false` for an empty database.

Two of the demo projects have schedule issues to look at on their timeline: in the
Acme booking app "Push notifications" was pulled in front of "Booking and payments",
which it waits for, and the last tasks of the Globex patient portal are due after
the client's deadline.

| Service | Host port | URL                                                 | Notes                             |
| ------- | --------- | --------------------------------------------------- | --------------------------------- |
| web     | 5191      | http://localhost:5191                               | nginx; proxies `/api` to the API  |
| api     | 4411      | http://localhost:4411/q/health                      | Quarkus                           |
| db      | 5451      | `postgresql://planner:planner@localhost:5451/planner` | Postgres 17                     |
| backup  | none      |                                                     | optional, daily dumps to `./backups` |

Ports can be changed with `WEB_PORT`, `API_PORT` and `DB_PORT` (in `.env` or inline,
e.g. `WEB_PORT=8081 docker compose up`). Data lives in the `db-data` volume;
`docker compose down -v` removes it. The dumps of the backup service are files in
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

Everything is in the `db-data` volume, and the timesheet CSV is the only export. For a
full copy there is a `backup` service that dumps the whole database with `pg_dump`. It
is behind a Compose profile, so it only runs when asked for:

```bash
docker compose --profile backup up -d
```

To have it start with every `docker compose up`, put `COMPOSE_PROFILES=backup` in
`.env`.

Dumps land in `./backups` as `planner-<UTC date and time>.dump`, in `pg_dump`'s custom
format. When the service starts it makes one, unless the newest dump is less than a day
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
so everything written after the dump is gone. The dump is loaded into a new database
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
- `docker compose down -v` removes the database volume, not the dumps.

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
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://localhost:5191` | api     |
| `PORT`                 | `8080`                                        | api     |
| `WEB_PORT`, `API_PORT`, `DB_PORT` | `5191`, `4411`, `5451`             | compose |
| `BACKUP_DIR`           | `./backups`                                   | backup  |
| `BACKUP_KEEP`          | `7`                                           | backup  |
| `BACKUP_INTERVAL_SECONDS` | `86400` (one day)                          | backup  |

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
   be empty);
3. takes one migration back by hand, dumps, removes the volume and restores the dump
   with the commands from above. The row counts have to be back before the API has run.
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

## API overview

All endpoints are under `/api` and, apart from sign-up and login, need a bearer
token. Errors are JSON: `{ "status", "code", "message", "errors"? }`.

| Area          | Endpoints                                                                                       |
| ------------- | ----------------------------------------------------------------------------------------------- |
| Auth          | `POST /auth/signup`, `POST /auth/login`, `GET/PATCH /me`, `POST /me/password`                    |
| Organisation  | `GET/PATCH /orgs/{org}`, `/orgs/{org}/members`, `/orgs/{org}/invitations`, `/orgs/{org}/role-rates` |
| Clients       | `/orgs/{org}/clients`                                                                           |
| Projects      | `GET/POST /orgs/{org}/projects`, `GET/PUT/DELETE /projects/{id}`, `GET/POST /projects/{id}/duplicate`, `/projects/{id}/phases`, `/phases/{id}` |
| Tasks         | `GET/POST /projects/{id}/tasks`, `GET/PATCH/DELETE /tasks/{id}`, `POST /tasks/{id}/shift`, `GET /tasks/{id}/subtasks` |
| Dependencies  | `GET /projects/{id}/dependencies`, `POST /tasks/{id}/dependencies`, `DELETE /dependencies/{id}`   |
| Planning      | `GET /projects/{id}/critical-path`, `GET /projects/{id}/schedule-issues`, `GET /projects/{id}/health`, `GET /orgs/{org}/workload` |
| Time          | `/orgs/{org}/time-entries`, `GET /orgs/{org}/timer`, `POST /orgs/{org}/timer/start\|stop`         |
| Timesheets    | `GET /orgs/{org}/timesheets/week`, `POST .../submit\|withdraw`, `GET /orgs/{org}/timesheets`, `POST .../{id}/approve\|reject`, `GET .../missing?weekStart`, `POST .../remind` |
| Export        | `GET /orgs/{org}/exports/timesheets.csv?from&to&userId&projectId&billable`                        |
| Collaboration | `/tasks/{id}/comments`, `/comments/{id}`, `/notifications`, `/orgs/{org}/activity`, `/projects/{id}/activity` |
| Dashboard     | `GET /orgs/{org}/dashboard`                                                                     |

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
in use answers `409 code_taken`. `GET /projects/{id}/duplicate` proposes
`{ name, code, startDate }` (the code is free at that moment) and says how many phases,
tasks and links there are to copy.

The copy is made in one transaction, so it is there as a whole or not at all, and in a
fixed number of statements however many tasks the plan has. The first day of the plan
(the earliest task date or the project's own start) lands on `startDate`, or on the next
working day, and everything else keeps its distance from it in working days. So the
schedule issues of the copy are those of the source's dates, no more and no fewer.
Two things to know: tasks that were finished in the source are open again in the copy,
so an overlap that the source no longer reports comes back. And a date on a weekend is
taken as the working day it stands for, which changes nothing unless a task lies on a
weekend entirely: that one becomes a one-day task and can be in the way of a neighbour.

`GET /projects/{id}/schedule-issues` is open to everyone who can see the project and
returns a list of `{ type, taskId, relatedTaskId?, days?, suggestedStart?, suggestedDue?, suggestionCauses? }`,
ordered by the date of the task:

| Type                    | Meaning                                                                  | `relatedTaskId` | `days` (working days)   |
| ----------------------- | ------------------------------------------------------------------------ | --------------- | ----------------------- |
| `STARTS_TOO_EARLY`      | The task starts before a task it waits for is due, plus the lag          | the predecessor | how many days too early |
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

`GET .../timesheets/missing` and `POST .../timesheets/remind` are for owners and
managers and only take weeks that are over by the organisation's date
(`400 week_not_over` otherwise). A reminder request names the week and the people,
`{ "weekStart", "userIds": [...] }`, and the answer says who was reminded and who was
left out and why: `{ "reminded": [...], "skipped": [{ "userId", "reason" }] }` with the
reasons `NOT_MISSING`, `YOURSELF`, `ALREADY_REMINDED` and `DAILY_LIMIT`.

## License

MIT, see [LICENSE](LICENSE).
