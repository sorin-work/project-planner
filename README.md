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
- **Critical path** (CPM forward/backward pass over working days) with float per task.
- **Views**: list grouped by phase, kanban board with drag and drop, and an SVG
  timeline (Gantt) with dependency arrows, the critical path, today line, collapsible
  phases, day/week/month zoom, and bars you can drag (or move with the arrow keys)
  to reschedule.
- **Time tracking**: start/stop timers from a task or the top bar, manual entries,
  billable and non-billable time. Timers stopped after midnight book the time on the
  day they started.
- **Timesheets**: a weekly grid per person; submit a week, managers approve it or send
  it back with a note. Submitted and approved weeks are locked.
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
(projects and phases), `task` (tasks, dependencies, critical path endpoint), `time`
(entries, timers, timesheets, CSV export), `workload`, `report` (project health),
`comment`, `notification`, `activity`, `dashboard` and `seed`. Resources are thin;
permission checks live in `OrgAccess`.

The scheduling and reporting rules are plain Java classes without any framework
dependencies, so they are tested in isolation:

| Class                 | What it does                                                         |
| --------------------- | -------------------------------------------------------------------- |
| `WorkCalendar`        | Working-day arithmetic (Mon–Fri): durations, lags, week starts       |
| `DependencyGraph`     | Cycle detection, reachability, topological order                     |
| `Rescheduler`         | Pushes dependants later when a task moves, keeping durations         |
| `CriticalPath`        | Early/late start and finish, float and the critical tasks            |
| `ProgressCalculator`  | Progress by estimate, planned vs earned hours, SPI, overdue counts   |
| `BudgetCalculator`    | Hours and money burn against the budgets                             |
| `HealthStatus`        | Turns progress and burn into on track / at risk / off track          |
| `WorkloadCalculator`  | Allocated hours per person per week vs capacity                      |
| `RateResolver`        | Picks the hourly rate for a piece of time                            |
| `MentionParser`       | Finds @handles in comments (and ignores email addresses)             |
| `CsvWriter`           | RFC 4180 CSV that neutralises spreadsheet formulas                   |

Authentication is stateless: `POST /api/auth/login` returns an HS256 JWT signed with
`JWT_SECRET`, which the web app keeps in `localStorage` and sends as
`Authorization: Bearer …`. Roles are per organisation, so they are looked up on each
request rather than stored in the token.

On the web side, `core/` holds the API clients, auth and the current-organisation
context; `features/` has one folder per page; the timeline lives in
`features/gantt` with its layout maths (`gantt-layout.ts`) separate from the
component so it can be unit tested.

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
(older weeks approved, last week waiting for review, one sent back), comments with
mentions and some notifications. All dates are relative to the day it runs. Set
`SEED_DEMO=false` for an empty database.

| Service | Host port | URL                                                 | Notes                             |
| ------- | --------- | --------------------------------------------------- | --------------------------------- |
| web     | 5191      | http://localhost:5191                               | nginx; proxies `/api` to the API  |
| api     | 4411      | http://localhost:4411/q/health                      | Quarkus                           |
| db      | 5451      | `postgresql://planner:planner@localhost:5451/planner` | Postgres 17                     |

Ports can be changed with `WEB_PORT`, `API_PORT` and `DB_PORT` (in `.env` or inline,
e.g. `WEB_PORT=8081 docker compose up`). Data lives in the `db-data` volume;
`docker compose down -v` removes it.

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

## Tests and lint

```bash
cd api && ./mvnw verify      # unit tests + API tests against in-memory H2
cd web && npm test           # Vitest in jsdom, no browser needed
cd web && npm run lint       # ESLint with angular-eslint
```

The scheduling, critical path, cycle detection, budget, progress and workload tests
are plain JUnit tests and need no database. The `@QuarkusTest` API tests start the
application against H2 in PostgreSQL mode and run the same Flyway migrations as
production.

## API overview

All endpoints are under `/api` and, apart from sign-up and login, need a bearer
token. Errors are JSON: `{ "status", "code", "message", "errors"? }`.

| Area          | Endpoints                                                                                       |
| ------------- | ----------------------------------------------------------------------------------------------- |
| Auth          | `POST /auth/signup`, `POST /auth/login`, `GET/PATCH /me`, `POST /me/password`                    |
| Organisation  | `GET/PATCH /orgs/{org}`, `/orgs/{org}/members`, `/orgs/{org}/invitations`, `/orgs/{org}/role-rates` |
| Clients       | `/orgs/{org}/clients`                                                                           |
| Projects      | `GET/POST /orgs/{org}/projects`, `GET/PUT/DELETE /projects/{id}`, `/projects/{id}/phases`, `/phases/{id}` |
| Tasks         | `GET/POST /projects/{id}/tasks`, `GET/PATCH/DELETE /tasks/{id}`, `POST /tasks/{id}/shift`, `GET /tasks/{id}/subtasks` |
| Dependencies  | `GET /projects/{id}/dependencies`, `POST /tasks/{id}/dependencies`, `DELETE /dependencies/{id}`   |
| Planning      | `GET /projects/{id}/critical-path`, `GET /projects/{id}/health`, `GET /orgs/{org}/workload`       |
| Time          | `/orgs/{org}/time-entries`, `GET /orgs/{org}/timer`, `POST /orgs/{org}/timer/start\|stop`         |
| Timesheets    | `GET /orgs/{org}/timesheets/week`, `POST .../submit\|withdraw`, `GET /orgs/{org}/timesheets`, `POST .../{id}/approve\|reject` |
| Export        | `GET /orgs/{org}/exports/timesheets.csv?from&to&userId&projectId&billable`                        |
| Collaboration | `/tasks/{id}/comments`, `/comments/{id}`, `/notifications`, `/orgs/{org}/activity`, `/projects/{id}/activity` |
| Dashboard     | `GET /orgs/{org}/dashboard`                                                                     |

Task updates that change dates return `{ task, rescheduled: [...] }` so the client can
show which dependants were pushed back.

## License

MIT, see [LICENSE](LICENSE).
