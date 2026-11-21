# project-planner

Project planning and delivery tracking for small teams and agencies. Plan a project
as phases, tasks and milestones with finish-to-start dependencies, see it on a
timeline with the critical path, log time against tasks, approve weekly timesheets
and keep an eye on schedule, budget burn and everyone's workload.

It is the planning-heavy sibling of a simple kanban board: the board is there, but
the point is knowing whether a project will land on time and on budget.

## Features

- **Forgotten passwords**: with mail set up, "Forgot your password?" on the sign-in
  page mails a link that works for one hour. Setting a new password, there or in the
  settings, signs you out everywhere else.
- **Sign-in limits**: after five wrong passwords for an account from one address, each
  further try from that address waits longer, up to five minutes. Other addresses are
  not held back, so somebody guessing never keeps the owner out, and a reset clears it. The app is served with a
  Content-Security-Policy. See [Sign-in limits and security headers](#sign-in-limits-and-security-headers).
- **Organisations and roles**: owners, managers, members and client viewers. Invite
  people by email; anyone who already has an account is added straight away, others
  join automatically when they sign up with the invited address. When the server
  sends mail (see [Mail](#mail)), the invitation is mailed with a link that works for
  seven days and can be sent again, and someone who was added is told by mail. Client
  viewers are tied to one client and only see that client's projects (read-only, but
  they can comment).
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
  end or outside its parent, the button says so first. The project list marks a plan
  that has issues with their count, and the Overview has a line with a link to the
  timeline, so they show before anybody opens it.
- **Critical path** (CPM forward/backward pass over working days) with float per task.
  A finished task stays at its own dates: what is done is no longer a plan, so it has no
  float and is never critical, while the tasks that wait for it still follow it. When
  the plan ends with a finished task, no open task is critical, and the timeline says so.
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
  - Managers can tick weeks in the approval queue and approve them in one go, or
    approve all of them. Only the weeks as they were on screen are approved: one that
    was withdrawn, or withdrawn and submitted again since the page loaded, is left out,
    and the page names every week it left out and why.
- **Missing timesheets**: for any week that is over, managers see who has not
  submitted it (drafts and weeks that were sent back) with the hours logged against
  that week's capacity, counted as the workload counts it, and can remind one person
  or everybody who logged time. People without any time that week are listed apart,
  since they may have been away, and somebody whose time off covers the whole week is
  marked away rather than shown as far under capacity. Nobody gets a second
  reminder about the same week on the same day, or more than five reminders a day. The
  reminder, like every timesheet notification, opens the timesheet on the week it is
  about.
- **Workload**: allocated hours per person per week against their capacity, with
  overallocated weeks flagged. Each open task's estimate is spread over its working
  days. A week's capacity is a fifth lower for each holiday and each day off, and the
  cell says so.
- **Holidays and time off**: managers keep the organisation's holidays in Settings.
  A holiday is not a working day anywhere: moves and the dependency cascade skip it,
  the critical path, the schedule check, progress, the copy of a plan and capacity
  leave it out, and the timeline shades it like a weekend. Adding one moves no task, so
  a task across it is a working day shorter. On the Team page people add their own time
  off and managers anybody's. Time off only lowers capacity, in the workload and in
  the utilisation report. Time can still be logged on a holiday, and the timesheet
  marks it in its header.
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
- **My tasks**: one page with everything assigned to you across the projects, grouped
  into Overdue, This week (up to Sunday), Later and No date by the organisation's date,
  with the project's code, the due date, the status and the priority. A box ticks a task
  done without leaving the page, and unticking puts it back to what it was. Done tasks
  are hidden unless "Show done" is on. Team members can pick somebody else to see their
  tasks. A click opens the task in its project.
- **Comments with @mentions** (handles are the part of the email before the @, with
  autocomplete), in-app notifications, an activity log per project and organisation.
  A mention only reaches people who can see the project: a client viewer of another
  client is neither offered nor notified.
- **Notification mails**: with mail set up, mentions, tasks assigned to you, timesheets
  sent back and reminders are also mailed, several at a time in one mail. Each type can
  be mailed, shown in the app only or switched off in the settings, and every mail has
  a link that stops the mails of its types without signing in.
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
`attachment`, `baseline`, `comment`, `notification`, `mail` (settings, the mails and the
thread that sends them), `activity`, `dashboard`, `search` and `seed`. Resources are thin;
permission checks live in `OrgAccess`.

The scheduling and reporting rules are plain Java classes without any framework
dependencies, so they are tested in isolation:

| Class                 | What it does                                                         |
| --------------------- | -------------------------------------------------------------------- |
| `WorkCalendar`        | Working-day arithmetic (Mon–Fri without the organisation's holidays): durations, lags, week starts |
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
drag (`gantt-edit.ts`: pixels to days, snapping off weekends and holidays, handles, drop targets)
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
budget alert of the Globex patient portal, which is over its hours budget, and each
person's two holidays of the year as time off (Tom is also away three days next week,
which the workload shows). All dates are
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
| api     | 4411      | http://localhost:4411/q/health                      | Quarkus, on 127.0.0.1 only; with `API_DOCS=true` the API reference is at http://localhost:5191/api/docs |
| db      | 5451      | `postgresql://planner:planner@localhost:5451/planner` | Postgres 17                     |
| mail    | 5192      | http://localhost:5192                               | optional mail catcher (Mailpit), only on 127.0.0.1 |
| backup  | none      |                                                     | optional, daily dumps to `./backups` |

Ports can be changed with `WEB_PORT`, `API_PORT` and `DB_PORT` (in `.env` or inline,
e.g. `WEB_PORT=8081 docker compose up`). The API port is only published on 127.0.0.1,
for the checks below: the web container is the way in. Publishing it on other
addresses lets clients reach the API without nginx, which is fine for the API itself
(every request still needs its token), but such clients are counted by their own
address for the reset limit, and the address is all the limit has to go on. Data lives
in the `db-data` volume and the files attached to tasks in the `attachments` volume;
`docker compose down -v` removes both. The dumps of the backup service are files in
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

## Mail

The app mails invitations, links to reset a password and notifications. It sends them
by plain SMTP, to the server in `MAIL_HOST`. Without `MAIL_HOST` it sends nothing and
works as it did before it had mail: there is no "Forgot your password?" link,
invitations are not mailed and there is no "resend" beside them, the reset
endpoints answer 404 with the code `mail_off`, and notifications only show in the app.

With `MAIL_HOST` set, `MAIL_FROM` and `APP_URL` have to be set too, or the API does not
start. `APP_URL` is the address of the web app, such as `https://planner.example.com`.
The links in mails are built from it and never from the address a request came in on.
The connection to the mail server has to be encrypted: STARTTLS on port 587 by
default, or TLS from the start with `MAIL_TLS=true` on port 465. `MAIL_STARTTLS=DISABLED`
lifts that, which is only meant for the mail catcher. `MAIL_USERNAME` and
`MAIL_PASSWORD` are used when the server asks for them.

For development, `.env.example` starts the `mail` service, a mail catcher
([Mailpit](https://mailpit.axllent.org)), and points the API at it. Every mail the
app sends ends up at http://localhost:5192 and nowhere else. The page is only bound to
127.0.0.1 and the catcher's SMTP port is not published. Without `.env`, Compose starts
neither and mail is off.

What the mails hold, and what they do not:

- Only fixed wording, the link and how long it works. No names and no organisation
  names, because those are typed by users and a mail in the app's name should not carry
  somebody else's words.
- The token travels after the `#` of the link (`/reset#token=...`), which browsers do
  not send to a server, so it stays out of access logs. The page takes it out of the
  address bar once it has read it.
- A token is 32 random bytes. Only its SHA-256 is stored. It works once: using it is a
  single update that only matches while it still works, so of two requests with the
  same link only one gets through.

A reset request answers 204 whether or not the address has an account. The lookup,
the link and the mail happen on a thread of their own after the answer is ready, so the
time it takes does not tell either. One client may ask ten times in a quarter of an
hour, by the address nginx saw: with `BEHIND_PROXY=true` the API takes the
`X-Forwarded-For` that nginx writes, but only from the addresses in `TRUSTED_PROXIES`.
In Compose that is the web container's fixed address, `WEB_ADDRESS` in the subnet
`COMPOSE_SUBNET` (`172.28.40.10` in `172.28.40.0/24`), so a client that reaches the API
another way cannot pick an address for itself. Change both when that subnet is taken on
the machine, for one when two copies of the stack run side by side. An account gets at most
three links that work at the same time, and at most one a minute. Asking again never
cancels a link that was already sent, so nobody can keep the link in somebody else's
inbox dead. Using a link ends every session of the account that was started before,
and its other links stop working, however close in time the session was started: every
token carries a version of the account, which a reset or a change of password counts up.
A password change in the settings does the same and gives the session it was made in a
new token.

An invitation link fixes the organisation and the role. The page at `/invite` shows
which address it is for. With an account for that address you sign in and accept;
without one you sign up there, with that address. Signed in with another account, it
says so and does not let you accept. Sending an invitation again gives it a new link
and the old one stops working, and cancelling it stops its link at once.

With mail on, a mailed invitation is only ever accepted with its link. Signing up with
the invited address alone makes an account of its own and leaves the invitation
waiting: otherwise whoever knows the address could sign up first and get the invited
role. The link still works for that account, after signing in. Nobody is kept from
signing up by an invitation they did not ask for. Invitations made while mail was off
were never mailed and are still accepted by signing up with the address, as before.
`INVITE_LINK_REQUIRED=true` goes further: nobody can sign up without the link of an
invitation (`409 invitation_required`), which closes the app to people nobody invited. It only has an effect with
mail on, so that a fresh install without mail can still be joined, and it is off by
default.

### Notification mails

`Notifier.notify` sends nothing itself: it runs inside the transaction of the request,
so a slow mail server would hold the request up and a rollback would come too late to
take a mail back. A notification that is to be mailed waits with no `emailed_at`, and
a job (`NotificationMailer`, once a minute) mails what waits, one mail per person with
everything of theirs that came in since. The job looks at every notification again
before it mails it, and only marks it as done when it is read already, its type is no
longer mailed to the person, they left the organisation, or they can no longer see
what it is about (a project of another client, budgets or approvals after losing the
manager role). A mail that fails is tried again after 1, 2, 4, 8 and 16 minutes and
then given up, which the log says. Several instances of the API can run the job at
the same time. A run takes people one at a time, the one whose oldest waiting
notification is oldest first and each once a round, so that one person's backlog does
not hold up the others. Just before a person's mail it reads their waiting rows with
`FOR UPDATE SKIP LOCKED` and claims them for five minutes with a token of its own
(`email_claimed_until` and `email_claim_token`, `V27`), so no two runs mail the same
notification. It stamps the outcome only on rows that still hold its token, and a run
that dies in the middle of a mail leaves its rows to be taken on again once the claim
has run out. A round stops after four minutes and leaves the rest to the next. One case still sends twice: a mail that the
mail server took but whose answer never came back counts as failed and is sent again.
The job only ever writes its own columns, so reading a notification while its mail is
on the way is never undone.

The mails say what happened in fixed words, one line per notification with a link to
the task, the project, the week or the approvals page: never the title of a task or
the words of a comment. They end with a link to the settings and, for each type in the
mail, a link that stops the mails of that type. That link works without signing in:
its token names the person and the type and is signed with a key of its own derived
from `JWT_SECRET`, so it can do nothing else and cannot be bent to another person or
type. The page it opens asks before it changes anything, so a mail scanner that opens
links does not unsubscribe anybody.

Defaults: mailed for mentions, tasks assigned to you, timesheets sent back and
reminders; the app only for the rest. "Not at all" also leaves the notification out of
the app. The choice holds for every organisation a person is in. Notifications from
before the upgrade, made while mail was off, or seeded with the demo data are never
mailed.

A mail that cannot be sent is logged at WARN with the kind of mail and the type of the
error, without the address or the link. The whole error, which can name the address,
is at DEBUG. The API tests send to a mock mailbox and never to a server.

## Sign-in limits and security headers

Wrong passwords are limited per account and client address together. Five wrong
passwords for an account from one address pass. After that each further try from that
address for that account waits, counted from the last wrong one: one second, then two,
four and so on, up to five minutes. A try during the wait answers
`429 too_many_attempts` with `Retry-After` in seconds, and the sign-in page says how long
that is. The password is not looked at during the wait, so a right one gets the same
answer as a wrong one, and the try does not make the wait longer.

That wait is the only thing that refuses a try, and it only holds back the address the
wrong passwords came from. Somebody who keeps trying passwords for an account from one
place keeps that place waiting, while the owner signs in from home or the office at once.
A password reset clears the waits of the account everywhere.

Two more counts slow guessing down without refusing anybody: wrong passwords for an
account from all addresses together (twenty), and wrong passwords from one address for
all accounts (a hundred in an hour). Past either, a new pair of account and address gets
one free try instead of five before it waits. That makes guessing at one account from many
addresses, and at many accounts from one address, slow, while the right password from a
place that has not failed still goes straight in. A whole office behind one address keeps
signing in, even when somebody there is trying passwords.

That is the deliberate cost of never locking the owner out: guessing from many addresses
grows with the number of addresses. Once the account is past its count, each address gets
one free try and then waits the same way, which comes to about 300 tries a day per address
when the waits have reached five minutes. A thousand addresses can try some 300,000
passwords a day at one account. A long password, or one from a password manager, is what
holds against that; the limits make it slow, not impossible.

The waits per account and address are a table, `login_failures`, and the counts per
account two columns on `users`, so they hold across a restart and across copies of the
API. A right password removes the row of its address, and rows that have been quiet for a
day. An hourly job deletes the rows quiet for a day, and an account keeps at most a
thousand of them: past that the ones that failed least recently go, so trying from ever
more addresses cannot grow the table without end. The counts per address are in memory, like the reset limit, and a restart resets
them. Failures are forgotten after a day without one (an hour for an address). A burst of
tries at the same moment does not get past a wait: each try is counted before the
password is checked, by an update that only matches the count it read, and taken back
when the password was right.

All of this goes by the address the API sees. In Compose that is the address nginx saw
(see [Mail](#mail) for `BEHIND_PROXY` and `TRUSTED_PROXIES`). When something else
terminates TLS in front of the web container, nginx sees that one address for everybody,
and every client then shares one count per account. Let nginx take the client's address
from the terminator with `set_real_ip_from` and `real_ip_header` (the lines are in
`web/nginx.conf`, commented out).

A sign-in with an unknown email checks the password against a dummy hash of the same
cost, so that it takes as long as a wrong password and its time does not say whether
there is an account. That does not make account names secret: sign-up still answers
`email_taken` for an address that has an account, and an account that waits answers
429 where an unknown email answers 401. Both say that the account exists. I left them,
since closing them would mean a sign-up that answers in the mail rather than on the page.
For the same reason a known email still costs a few more database writes than an unknown
one (the count of the pair and of the account), which the hash, at about a tenth of a
second, mostly hides but does not cancel.

The web container sends these headers with the app (`web/nginx.conf`):

- `Content-Security-Policy` with `script-src 'self'`, `style-src 'self'` and
  `frame-ancestors 'none'`, among others. The login token sits in `localStorage`, so the
  policy is what stands between a script slipped into a page and every session. Styles
  come from `styles.css` only: the components have no styles of their own (the lint
  refuses them, and style attributes in templates), `[style.width.%]` bindings are set
  through the DOM, which the policy allows, and the production build does not inline
  critical CSS (`inlineCritical` in `angular.json`), since it loads the rest with an
  inline script.
- `X-Content-Type-Options: nosniff` and `Referrer-Policy: same-origin`.

They are not sent on `/api/`: the answers there set their own. Attachments have a
stricter policy, and the API reference at `/api/docs` runs a script of its own. The
Angular dev server sends none of them. HSTS is not sent either: this nginx serves plain
HTTP, and HSTS belongs to whatever terminates TLS in front of the stack. Set it there.

To check the headers of a running stack:

```bash
curl -sI http://localhost:5191/ | grep -iE 'content-security|nosniff|referrer'
```

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
`.env` (`COMPOSE_PROFILES=mail,backup` to keep the mail catcher as well).

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
counts as made now and not as overdue. A file that is named like a dump but with a date
that does not exist, 31 February for one, is left alone: it does not count as a backup,
it is never removed, and the log names it after every dump.

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
given to the API's user (`ATTACHMENTS_OWNER`, `1000:1000`). An archive that names a path
outside the folder is refused, and anything in it that is not a plain file or folder (a
link, a device, a file hard-linked to another) is left out and counted in the log. A dump without an archive,
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
| `MAIL_HOST`            | none: mail is off (`mail` in `.env.example`)  | api     |
| `MAIL_PORT`            | `587`                                         | api     |
| `MAIL_FROM`            | none, required with `MAIL_HOST`               | api     |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | none                                | api     |
| `MAIL_STARTTLS`        | `REQUIRED` (`DISABLED` for the mail catcher)  | api     |
| `MAIL_TLS`             | `false` (`true` for TLS from the start, 465)  | api     |
| `APP_URL`              | none, required with `MAIL_HOST`               | api     |
| `INVITE_LINK_REQUIRED` | `false`                                       | api     |
| `BEHIND_PROXY`         | `false` (`true` in compose)                   | api     |
| `TRUSTED_PROXIES`      | `127.0.0.1` (`WEB_ADDRESS` in compose)        | api     |
| `COMPOSE_SUBNET`, `WEB_ADDRESS` | `172.28.40.0/24`, `172.28.40.10`     | compose |
| `MAIL_UI_PORT`         | `5192`                                        | compose |
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

## Accessibility

Everything works from the keyboard, and what changes out of sight is read out:

- **Every page**: Tab first reaches "Skip to content", which jumps past the navigation.
  The current page is marked in the navigation and the project tabs (`aria-current`),
  the menu button says whether the menu is open, and the bell says how many
  notifications are unread. Every page has a title of its own ("Timeline · Website ·
  project-planner"). Saves, moves and errors are read out through two live regions in
  the shell, one polite and one for errors, so a screen reader hears them wherever the
  focus is. Whatever has the focus shows a ring.
- **Task panel**: it takes the focus when a task opens, Escape closes it, and the focus
  goes back to the row, card or bar that opened it, or to the list, board or timeline
  when that task was deleted, which is read out. In a text field Escape first leaves the
  field, so a comment that is being written is kept and the panel says so; the next
  Escape closes it. Escape in the mention suggestions only closes the suggestions.
- **Search**: Ctrl+K (Cmd+K on a Mac) opens it as a modal dialog. The focus stays in it,
  and goes back where it was when it closes.
- **Board**: focus a card and press Alt with the arrow keys. Left and right change the
  status, up and down the place in the column. Each move is read out, and so is why a
  card did not move (it is at the edge, or only the team moves cards). Alt with an arrow
  on a card never goes Back or Forward in the browser.
- **Timeline**: the arrow keys move the focused bar by a working day, Shift with the
  arrow keys moves its due date, Delete removes a focused arrow and Escape gives up a
  drag. Presses that follow each other within 300 ms, a held key included, are added up
  and saved as one move, which is read out once with the task's new dates. Every bar says in its label how
  it compares with the baseline being compared against, and every arrow says how its
  task waits.
- **Weekly grid**: the arrow keys move between cells, and Enter saves and moves down.
  Each save, and each refusal, is read out.
- **Files**: "Attach files" is a button that opens the file picker, so dropping is not the
  only way. **Baselines** are a select and buttons. **Mentions**: the comment box is a combobox
  that says when its suggestions are open, points at the list and at the person who is
  highlighted. The role is on a wrapper around the textarea (the ARIA 1.1 pattern),
  because ARIA in HTML allows no role on a textarea.
- `prefers-reduced-motion` turns the pulse of the running timer and the transitions off.

Three specs check this in CI:

- `a11y.spec.ts` renders every page inside the shell, with an API that answers from
  fixtures (`src/testing/fixtures.ts`), and runs axe over it (`src/testing/axe.ts`). It
  does the same with the search dialog open, the menu open and a timer running, the
  mention suggestions open, a page whose data could not be loaded, and as a member and
  a client viewer. The
  only rule that is off is colour contrast, because jsdom computes no colours.
- `contrast.spec.ts` reads `styles.css`, works out every text colour against its
  background in the light and the dark theme, hover and focus states included, and holds
  each to 4.5:1. Text in a container is measured on the container's background, and an
  `opacity` is multiplied into the colour. A `:hover:not(:disabled)` state is measured;
  only disabled states are exempt. The focus ring has to reach 3:1 on every background. A rule is only left
  out with a reason written next to it: the shapes that hold no text, and the avatar,
  whose colours are measured on their own. That check is why `--muted`, `--primary`,
  `--good`, `--warning`, `--danger` and `--critical` are darker in the light theme than
  they were, and why text on a primary or danger background is dark in the dark theme
  (`--on-primary`, `--on-danger`).
- `keyboard.spec.ts` uses the keyboard on rendered pages: the skip link, the states of the
  menu and the bell, the titles, the task panel's focus, a card moved with Alt and an
  arrow, and a move on the timeline being read out.

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

`QueryCountTest` counts the statements that the project list, the dashboard, the
approval queue, the members, `/me` and a comment with a mention send, on an organisation
of fifty projects with a thousand tasks and twenty thousand time entries on one of them
(`support/LargeOrganisation`, built by the test, not by the demo seed). The counts come
from Hibernate's statistics, which are on in the test profile, and each has a fixed
bound. A change that queries per row fails there instead of getting slow later. The
project list is ten statements, one of them for the links behind its count of
schedule issues. Approving five weeks at once reads as much as approving one, and only
writes more: a notification and a line in the activity log for each week.

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

- Apart from sign-up, login and the forgotten password and invitation link endpoints
  under `/api/auth`, a request needs a token: `Authorization: Bearer <token>`. A token
  stops working when the password is changed or reset after it was issued.
- Login answers `429 too_many_attempts` after too many wrong passwords, with the
  seconds to wait in `Retry-After`, see
  [Sign-in limits](#sign-in-limits-and-security-headers).
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
`more` names the kinds that had more hits than the limit, so a list can say that it is
cut off.

With `assigneeId`, `status` (which may be given more than once) or both, the same
endpoint finds only tasks, and `q` is optional: it then narrows by text if given. That
is the My tasks page. The limit goes up to 200, the earliest due date comes first, tasks
without one last, then the project's code and the place in the project. The answer
carries `today`, the organisation's date, so that the page can tell overdue from not.
The visibility is the search's own: tasks of the organisation's projects, and for a client
viewer of their client's, whoever they are assigned to. It is a fixed number of
statements however many tasks the organisation has (`QueryCountTest`).

`/orgs/{org}/holidays` lists the holidays (`?from=&to=` optional) for every member, client
viewers included since they see the timeline, and takes `POST`, `PUT /{id}` and
`DELETE /{id}` from owners and managers. A second holiday on a date is
`409 holiday_taken`, also when two requests ask for the same date at once. A holiday that
is added, moved or removed is in the organisation's activity log, since it changes every
duration and capacity across it. `/orgs/{org}/time-off` lists the team's time off (`?userId=`,
`?from=&to=`) for the team. People add, change and remove their own, owners and managers
anybody's on the team (`unknown_member` for anybody else). Days that are already off are
`409 time_off_overlaps`, checked with the person's membership locked so that two requests
can not both get through. Every count of working days in a request uses one calendar of
the organisation (`calendar/OrgCalendars`, request-scoped, one query for the holidays),
and the web app counts drags on the timeline with the same holidays (`shared/dates.ts`).

`GET /orgs/{org}/projects` answers with each project and a summary of its health, in a
fixed number of statements however many projects, tasks and entries there are: the
tasks of all listed projects come in one query, their time in one query grouped by
project, person, billable flag and length, and the rates once. Money is still priced
entry by entry and rounded to a cent each time, so the list, the overview, the CSV and
the reports agree. A form that only needs a project select asks
`GET /orgs/{org}/projects/options` instead: id, code, name, status and client of each
project the caller can see, by name, with nothing worked out.

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
stored under a random key, never under their name. A name loses any folder, control
characters and invisible format characters (such as a right-to-left override).

The bytes are not behind the bearer token, since a plain link or an `<img>` cannot send
it. `POST /attachments/{id}/link` checks that the caller may see the project and answers
with a path that works for two minutes without a token: it carries the attachment, the
user, the expiry and whether to show the file in the page, with an HMAC over them (the
key is derived from `JWT_SECRET`). `GET /attachments/{id}/content` checks that HMAC and
the expiry, `403 link_invalid` otherwise. A link is checked once, when it is made: it
keeps working for the rest of its two minutes after the user loses access, which is why
it is short. Changing `JWT_SECRET` makes every link that is out there invalid, along
with every login token. Only a plain file inside the attachments folder is served, never
a link. Every answer has `Content-Disposition`
(`attachment`, or `inline` for the four raster image types when a preview was asked
for), `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none';
sandbox` and `Cache-Control: private, no-store`.

Deleting an attachment, a task or a project deletes the files once the deletion is
committed; a failed request keeps them. A file written for an upload that is not
committed goes again. Every six hours a sweep removes files that no row knows and that
are older than an hour. `POST /projects/{id}/duplicate` copies the files only with
`copyAttachments: true`, each under a key of its own, and the answer then says how many
were copied and how many were left out because their file was missing
(`attachmentsCopied`, `attachmentsSkipped`). `GET` on the same path says how many there
are (`attachments`, `attachmentBytes`).

An upload or a copy of files locks the organisation's row while it checks the space and
writes, so uploads that arrive together are counted one after the other and never pass
the limit between them. That row is taken before any membership or project row.

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

Finished tasks are never in the list. On the critical path they stay at their own
dates in both passes: the links into a finished task are not counted, so an overlap
that was kept on purpose does not move the end, and its `floatDays` is left out. When
the `finish` of the critical path is still not the latest due date, the timeline says
why in a line under its toolbar.

The project list (`GET /api/orgs/{orgId}/projects`) has `health.scheduleIssues`, the
length of this list for each project, and so has `GET /api/projects/{projectId}/health`.
Every issue counts, a task due after the project's end included, although the Overview
shows that one as forecast against planned end as well. The links of all listed
projects come in one query, so the list costs one statement more than before, ten of
its own. The dashboard's project cards leave the count out.

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
reasons `NOT_MISSING`, `YOURSELF`, `ALREADY_REMINDED` and `DAILY_LIMIT`. Each person
on the missing list has `capacityHours`, the week's capacity less holidays and time
off, and `away` when their time off covers every working day of it.

`POST .../timesheets/approve` approves several weeks at once, for owners and managers.
It takes up to 200 weeks as they were listed, each with the `submittedAt` the queue
showed: `{ "sheets": [{ "id", "submittedAt" }] }`. A week that can't be approved never
fails the others. The answer is `{ "approved": [...], "skipped": [{ "timesheetId",
"reason" }] }` with the reasons `NOT_FOUND`, `OWN_WEEK` (a manager's own week),
`NOT_SUBMITTED` (approved, sent back or withdrawn since) and `CHANGED` (withdrawn and
submitted again since, so its `submittedAt` is another one). The weeks are locked
while they are approved, and so are a single approval, a send back and a withdrawal,
so that a withdrawal at the same moment waits and then sees the approval. Each person
gets the same notification a single approval sends, one per week, and the activity log
has a line per week.

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
