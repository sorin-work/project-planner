#!/bin/sh
# Checks that check-restore.sh stops wherever it could reach data that is
# not its own, and that it tidies up when it is killed. No Docker is needed:
# docker is replaced by a stand-in that writes down what it is asked to do
# and answers what the case wants, so no case can do any harm.
#
# check-restore.sh drops a column, removes a database volume and writes a
# made-up row. Every case here ends before the first of those, and proves it
# from the list of calls the stand-in received.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The name the stack runs under, as check-restore.sh works it out.
stack=$(sed -n 's/^name: *//p' "$here/../docker-compose.yml")

mkdir "$work/bin"
cat >"$work/bin/docker" <<'STUB'
#!/bin/sh
echo "$*" >>"$STATE/calls"
[ -z "${COMPOSE_FILE+set}" ] || echo "COMPOSE_FILE reached docker" >>"$STATE/calls"
if [ -e "$STATE/no-daemon" ]; then
  echo "Cannot connect to the Docker daemon" >&2
  exit 1
fi
case "$*" in
  *" ps -aq") cat "$STATE/containers" ;;
  "volume ls "*)
    [ ! -e "$STATE/no-volume-list" ] || exit 1
    # Only the volume the filter names, as docker does.
    name=$(echo "$*" | sed -n 's/.*name=^\(.*\)\$.*/\1/p')
    if [ -n "$name" ]; then
      grep -x -F "$name" "$STATE/volumes" || true
    else
      cat "$STATE/volumes"
    fi
    ;;
  *" up --no-start "*) [ ! -e "$STATE/stop-at-create" ] || exit 1 ;;
  *" ps -aq db") echo c0ffee ;;
  "inspect "*) cat "$STATE/mount" ;;
  "volume inspect "*) cat "$STATE/label" ;;
  # A row count, so that a check that has lost a guard runs on and is caught
  # at once instead of waiting for demo data.
  *" exec "*) echo 1 ;;
  *" up -d --wait db api")
    # As if the check were killed while the stack is coming up.
    if [ -e "$STATE/kill-at-start" ]; then
      kill -TERM "$PPID"
      exit 143
    fi
    ;;
esac
STUB
chmod +x "$work/bin/docker"

fail() {
  echo "FAILED ($case_name): $*" >&2
  echo "--- calls to docker" >&2
  cat "$STATE/calls" >&2
  echo "--- output" >&2
  cat "$STATE/log" >&2
  exit 1
}

# $1: the case, $2: the compose project it runs under. Without anything
# else, docker answers as it does for a project nobody has used yet.
begin() {
  case_name=$1
  project=$2
  STATE="$work/state"
  rm -rf "$STATE"
  mkdir "$STATE"
  : >"$STATE/calls"
  : >"$STATE/containers"
  : >"$STATE/volumes"
  echo "volume ${project}_db-data" >"$STATE/mount"
  echo "$project" >"$STATE/label"
  export STATE
}

# Runs the check with $1 as the value of CI and leaves its exit status in
# $status. COMPOSE_FILE is set in every case and must never get through.
run_check() {
  status=0
  PATH="$work/bin:$PATH" TMPDIR="$work" CI="$1" CHECK_PROJECT="$project" \
    COMPOSE_FILE=docker-compose.yml:docker-compose.override.yml \
    sh "$here/check-restore.sh" >"$STATE/log" 2>&1 || status=$?
}

said() {
  grep -q -F -e "$1" "$STATE/log" || fail "it does not say: $1"
}

called() {
  grep -q -e "$1" "$STATE/calls"
}

# Nothing was created, started, changed or removed.
touched_nothing() {
  [ "$status" -ne 0 ] || fail "the check went ahead"
  if called " up " || called " down" || called " exec " || called " run "; then
    fail "it did more than look"
  fi
}

# The containers were created and then looked at, and that is where it
# ended: no server started, no statement run, and no volume removed.
stopped_before_starting() {
  [ "$status" -ne 0 ] || fail "the check went ahead"
  called " up --no-start db api$" || fail "the containers were never created, so this proves nothing"
  if called " up -d" || called " exec " || called " run "; then
    fail "it started or changed something"
  fi
  if called " down -v"; then
    fail "it removed volumes it had not made sure of"
  fi
  called " down --remove-orphans$" || fail "it left the containers it had created"
}

# Every compose call names the one file, whatever COMPOSE_FILE says.
reads_one_file() {
  if grep "^compose " "$STATE/calls" | grep -q -v "^compose -f docker-compose.yml -p $project --profile backup "; then
    fail "a compose call does not name docker-compose.yml and the project"
  fi
  if called "COMPOSE_FILE reached docker"; then
    fail "COMPOSE_FILE was passed on to docker"
  fi
}

remove="docker compose -f docker-compose.yml -p planner-restore-check --profile backup down -v"

begin "the project the stack itself runs under, outside CI" "$stack"
run_check ""
touched_nothing
[ ! -s "$STATE/calls" ] || fail "docker was called at all"
said "is the compose project the stack itself runs under"
said "CI=true"
echo "ok: $case_name"

begin "the same project in CI: allowed, and still looked at before anything starts" "$stack"
touch "$STATE/stop-at-create"
run_check true
called " up --no-start db api$" || fail "it did not get as far as creating the containers"
if called " down -v"; then
  fail "it removed volumes it had not made sure of"
fi
reads_one_file
echo "ok: $case_name"

begin "docker does not answer" planner-restore-check
touch "$STATE/no-daemon"
run_check ""
touched_nothing
said "docker compose ps did not work"
echo "ok: $case_name"

begin "the list of volumes cannot be read" planner-restore-check
touch "$STATE/no-volume-list"
run_check ""
touched_nothing
said "docker volume ls did not work"
echo "ok: $case_name"

begin "the project already has containers" planner-restore-check
echo "0123456789ab" >"$STATE/containers"
run_check ""
touched_nothing
said "already has containers"
said "$remove"
echo "ok: $case_name"

begin "the volume already exists" planner-restore-check
echo "planner-restore-check_db-data" >"$STATE/volumes"
run_check ""
touched_nothing
said "the volume planner-restore-check_db-data already exists"
said "$remove"
echo "ok: $case_name"

begin "the attachments volume already exists" planner-restore-check
echo "planner-restore-check_attachments" >"$STATE/volumes"
run_check ""
touched_nothing
said "the volume planner-restore-check_attachments already exists"
said "$remove"
echo "ok: $case_name"

begin "the database would be on a volume with a fixed name, as an override file can give it" planner-restore-check
echo "volume project-planner-data" >"$STATE/mount"
run_check ""
stopped_before_starting
said "would not be on the volume planner-restore-check_db-data but on 'volume project-planner-data'"
reads_one_file
echo "ok: $case_name"

begin "the database would be in a folder of the host" planner-restore-check
echo "bind " >"$STATE/mount"
run_check ""
stopped_before_starting
said "would not be on the volume planner-restore-check_db-data"
echo "ok: $case_name"

begin "the volume was made by another compose project" planner-restore-check
echo "project-planner" >"$STATE/label"
run_check ""
stopped_before_starting
said "was not made by the compose project planner-restore-check"
echo "ok: $case_name"

begin "killed while the stack is coming up: the stack and its own volume are removed" planner-restore-check
touch "$STATE/kill-at-start"
run_check ""
[ "$status" -eq 143 ] || fail "expected the check to end with 143, got $status"
called " down -v --remove-orphans$" || fail "the stack was left behind"
if called " exec " || called " run "; then
  fail "it carried on after being killed"
fi
echo "ok: $case_name"

echo "OK: check-restore.sh refused or tidied up in every case"
