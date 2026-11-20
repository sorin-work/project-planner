#!/bin/sh
# The restore drill that CI runs after "docker compose build". It needs
# Docker and nothing else, and it works on a compose stack of its own, with
# its own volume and its own folder for the dumps, all removed at the end.
# The data of the stack you use is not touched. The check does publish the
# same host ports, so stop your stack first or set DB_PORT and API_PORT.
#
#   1. Start db and api with the demo data and wait until both are healthy
#      and the seed is in. Nothing else in CI starts the stack at all.
#   2. Stop the api and run check-backup.sh in the backup service: the daily
#      loop against made-up clocks, retention, a restore into a scratch
#      database and the row count of every table.
#   3. An older dump under this api, with the commands from the README. One
#      migration is taken back by hand and the database dumped. Then the
#      volume is thrown away, the dump restored and the api started. Flyway
#      has to apply the migration again, although the dump holds a newer one,
#      and the rows have to be back.
#   4. A database from a newer version than this api. The api has to refuse
#      to start on it, instead of running old code against a schema it does
#      not know.
#
#   CHECK_PROJECT   compose project to use (default planner-restore-check)
#   CHECK_KEEP=1    leave the stack and the dumps there afterwards
set -eu

cd "$(dirname "$0")/.."

project=${CHECK_PROJECT:-planner-restore-check}

compose() {
  docker compose -p "$project" --profile backup "$@"
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

# Everything below ends in "down -v", which must never reach a stack that
# was already there.
[ -z "$(compose ps -aq)" ] || fail "the compose project $project already has containers, the check wants one of its own (CHECK_PROJECT)"
if docker volume inspect "${project}_db-data" >/dev/null 2>&1; then
  fail "the volume ${project}_db-data already exists, the check wants one of its own (CHECK_PROJECT)"
fi

BACKUP_DIR=$(mktemp -d)
export BACKUP_DIR SEED_DEMO=true BACKUP_KEEP=7 BACKUP_INTERVAL_SECONDS=86400

finish() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "--- last lines of the service logs" >&2
    compose logs --no-color --tail 60 db api >&2 || true
  fi
  if [ "${CHECK_KEEP:-}" = 1 ]; then
    echo "The stack is still up and the dumps are in $BACKUP_DIR. To remove the stack:"
    echo "  docker compose -p $project --profile backup down -v"
  else
    compose down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$BACKUP_DIR"
  fi
  exit "$status"
}
trap finish EXIT

echo "== start db and api with the demo data"
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

echo "== lose the volume, restore $dump, start the api"
compose down -v
compose run --rm -T backup restore "$dump"
compose up -d --wait api

echo "== what the api made of it"
sql "select installed_rank, version, description, success from flyway_schema_history order by installed_rank"
applied=$(sql "select count(*) from flyway_schema_history where version = '14' and success and installed_rank = (select max(installed_rank) from flyway_schema_history)")
[ "$applied" = 1 ] || fail "the api did not apply V14 on top of the restored dump"
after=$(counts)
echo "$after"
[ "$before" = "$after" ] || fail "the row counts are not the ones from before the dump"

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
