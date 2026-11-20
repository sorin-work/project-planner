#!/bin/sh
# The restore drill that CI runs after "docker compose build". It needs
# Docker and nothing else, and it works on a compose stack of its own, with
# its own volume and its own folder for the dumps, all removed at the end.
# The check does publish the same host ports, so stop your stack first or
# set DB_PORT and API_PORT.
#
#   1. Start db and api with the demo data and wait until both are healthy
#      and the seed is in. Nothing else in CI starts the stack at all.
#   2. Stop the api and run check-backup.sh in the backup service: the daily
#      loop against made-up clocks, retention, a restore into a scratch
#      database and the row count of every table.
#   3. An older dump under this api, with the commands from the README. One
#      migration is taken back by hand and the database dumped. Then the
#      volume is thrown away and the dump restored: the rows have to be
#      back before the api has run. Then the api is started, without the
#      demo seed. Flyway has to apply the migration again, although the dump
#      holds a newer one.
#   4. A database from a newer version than this api. The api has to refuse
#      to start on it, instead of running old code against a schema it does
#      not know.
#
# On the way it drops a column, throws a database volume away and writes a
# made-up row, so it must never get near data that somebody wants to keep.
# Before it changes anything it makes sure of the following, and stops when
# it cannot (check-restore-guards.sh proves each of these with a stand-in
# for docker):
#
#   - Only docker-compose.yml is read. An override file, or a COMPOSE_FILE
#     in the environment or in .env, could put the database on storage that
#     is in use.
#   - The compose project is not the one the stack itself runs under, except
#     in CI, and it has no containers and no volume yet. A docker command
#     that fails means "cannot tell", never "nothing there".
#   - The db container is created first and looked at before it is started:
#     its data has to be on the volume this project has just made for itself.
#
#   CHECK_PROJECT   compose project to use (default planner-restore-check)
#   CHECK_KEEP=1    leave the stack and the dumps there afterwards
set -eu

cd "$(dirname "$0")/.."

project=${CHECK_PROJECT:-planner-restore-check}
volume="${project}_db-data"
remove="docker compose -f docker-compose.yml -p $project --profile backup down -v"

# -f already takes precedence over COMPOSE_FILE and switches the override
# file off. Unset as well, so that no command below can pick it up.
unset COMPOSE_FILE

compose() {
  docker compose -f docker-compose.yml -p "$project" --profile backup "$@"
}

fail() {
  echo "FAILED: $*" >&2
  exit 1
}

# Runs one statement, or several in one transaction, with psql in the db
# container.
sql() {
  # The variables are the container's, not this shell's.
  # shellcheck disable=SC2016
  compose exec -T db sh -c 'psql -AtXq -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"' sh "$1"
}

# The tables that hold the work, one "name|rows" line each.
counts() {
  sql "select 'users', count(*) from users
       union all select 'tasks', count(*) from tasks
       union all select 'time_entries', count(*) from time_entries
       union all select 'timesheets', count(*) from timesheets"
}

# The data folder of the db container has to be the volume that this compose
# project made for itself. Called while the container exists but has not
# been started, so that a second server is never started on a data folder
# another one is using.
own_storage() {
  container=$(compose ps -aq db) || fail "docker compose ps did not work"
  [ -n "$container" ] || fail "the db container was not created"
  mount=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Type}} {{.Name}}{{end}}{{end}}' "$container") || fail "docker inspect did not work"
  [ "$mount" = "volume $volume" ] || fail "the database of the compose project $project would not be on the volume $volume but on '$mount'. The database was not started and no data was changed."
  label=$(docker volume inspect -f '{{index .Labels "com.docker.compose.project"}}' "$volume") || fail "docker volume inspect did not work"
  [ "$label" = "$project" ] || fail "the volume $volume was not made by the compose project $project (its label says '$label'). The database was not started and no data was changed."
  mine=true
}

# The name the stack runs under when nobody passes one: "name:" in the
# compose file, or else the name of the folder.
for own in "$(sed -n 's/^name: *//p' docker-compose.yml)" "$(basename "$PWD")"; do
  if [ "$project" = "$own" ] && [ "${CI:-}" != true ]; then
    fail "$project is the compose project the stack itself runs under. This check throws the database volume of its project away, so it wants a project of its own: leave CHECK_PROJECT alone or pick another name. Only CI, where there is no data to lose, may use this one (CI=true)."
  fi
done

containers=$(compose ps -aq) || fail "docker compose ps did not work, so there is no telling whether the compose project $project is in use. Nothing was started."
[ -z "$containers" ] || fail "the compose project $project already has containers, and the check wants a project of its own (CHECK_PROJECT)."
found=$(docker volume ls -q --filter "name=^${volume}\$") || fail "docker volume ls did not work, so there is no telling whether the volume $volume exists. Nothing was started."
[ -z "$found" ] || fail "the volume $volume already exists, and the check wants one of its own (CHECK_PROJECT)."

BACKUP_DIR=$(mktemp -d)
export BACKUP_DIR SEED_DEMO=true BACKUP_KEEP=7 BACKUP_INTERVAL_SECONDS=86400

# True while the database volume of the project is known to be this run's own.
mine=false

finish() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "--- last lines of the service logs" >&2
    compose logs --no-color --tail 60 db api >&2 || true
  fi
  if [ "${CHECK_KEEP:-}" = 1 ]; then
    echo "The stack is still there and the dumps are in $BACKUP_DIR. To remove the stack:"
    echo "  $remove"
  elif [ "$mine" = true ]; then
    compose down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$BACKUP_DIR"
  else
    # Whatever the database is on, it is not known to be this run's: the
    # containers go, the volumes stay.
    compose down --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$BACKUP_DIR"
  fi
  exit "$status"
}
trap finish EXIT

echo "== create db and api, look where the database will live, start them with the demo data"
compose up --no-start db api
own_storage
compose up -d --wait db api
# Healthy does not have to mean seeded. The seed is one transaction, so the
# first user means all of it is there.
tries=0
until [ "$(sql 'select count(*) from users' 2>/dev/null || echo 0)" -gt 0 ]; do
  tries=$((tries + 1))
  [ "$tries" -lt 60 ] || fail "no demo data after two minutes"
  sleep 2
done
before=$(counts)
echo "$before"

echo "== stop the api, then dumps, retention and a restore into a scratch database"
compose stop api
compose run --rm -T --entrypoint sh backup /scripts/check-backup.sh

echo "== an older dump: take V14 back, then dump"
# V14 only adds tasks.position, so it can be undone by hand. What is left is
# a database that ran V15 and never V14, which is what a dump looks like
# after two branches with migrations were merged in the other order.
sql "alter table tasks drop column position; delete from flyway_schema_history where version = '14'"
compose run --rm -T backup once
dump=$(ls "$BACKUP_DIR")
[ -r "$BACKUP_DIR/$dump" ] || fail "$dump cannot be read by the owner of the folder it is in"

echo "== lose the volume, restore $dump"
compose down -v
mine=false
compose up --no-start db
own_storage
compose run --rm -T backup restore "$dump"
# Counted before the api has run, and the api is then started without the
# demo seed. The seed makes the same rows every time, so with it a restore
# that brought back the tables and no rows would be filled up again and
# pass.
restored=$(counts)
echo "$restored"
[ "$before" = "$restored" ] || fail "the restored database does not have the row counts from before the dump"

echo "== start the api on it, without the demo seed"
export SEED_DEMO=false
compose up -d --wait api
sql "select installed_rank, version, description, success from flyway_schema_history order by installed_rank"
applied=$(sql "select count(*) from flyway_schema_history where version = '14' and success and installed_rank = (select max(installed_rank) from flyway_schema_history)")
[ "$applied" = 1 ] || fail "the api did not apply V14 on top of the restored dump"
[ "$before" = "$(counts)" ] || fail "the row counts changed when the api migrated the restored dump"

echo "== a database from a newer version: the api has to refuse it"
# All that Flyway goes by is a migration in its history that this api has no
# file for, so one made-up row stands for a dump from a newer version.
compose stop api
sql "insert into flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
     select max(installed_rank) + 1, '9999', 'from a newer version', 'SQL', 'V9999__from_a_newer_version.sql', 0, current_user, 0, true
     from flyway_schema_history"
if compose up -d --wait api; then
  fail "the api started on a database that a newer version has migrated"
fi
refusal="Detected applied migration not resolved locally: 9999"
compose logs --no-color api | grep -q "$refusal" || fail "the api did not start, but not because of the migration it does not know"
echo "refused: $refusal"

echo "OK: the stack starts, a dump restores, an older dump is migrated by the api and a newer one is refused"
