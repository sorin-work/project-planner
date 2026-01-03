# project-planner

Project planning and delivery tracking for small teams and agencies: phases, tasks
and milestones with finish-to-start dependencies, a critical path, time tracking
and budgets. Work in progress; this is the API so far.

## Running locally

You need Java 21 and Docker for the database.

```bash
docker compose up -d db
cd api
./mvnw quarkus:dev
```

The API listens on http://localhost:8080 and connects to Postgres on
`localhost:5451` (`planner` / `planner`). Override with `DATABASE_URL`,
`DATABASE_USER` and `DATABASE_PASSWORD`; set `JWT_SECRET` to at least 32 bytes
anywhere but your laptop.

## Tests

```bash
cd api && ./mvnw verify
```

Unit tests cover the scheduling rules (working days, rescheduling, cycle
detection); API tests run against an in-memory H2 database.

## License

MIT, see [LICENSE](LICENSE).
