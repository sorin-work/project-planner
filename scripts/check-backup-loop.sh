#!/bin/sh
# Checks the parts of backup.sh that can lose data when they go wrong: the
# daily loop, the pruning, and the step of a restore in which the restored
# copy takes the place of the database. No database is needed. pg_dump, psql
# and sleep are replaced by stand-ins, so every case takes a moment, and dump
# files with made-up dates stand for clocks that were wrong or were changed.
#
# For the loop: it runs for a number of rounds, makes at most one dump,
# pauses after every attempt, and never leaves fewer dumps behind than
# BACKUP_KEEP allows.
set -eu
# The loop is the same with and without attachments, and the stand-ins write
# no archives. check-backup.sh checks the archives for real.
unset ATTACHMENTS_DIR

here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

ROUNDS=12
DAY=86400
now=$(date +%s)
# Two days from now, in the form touch takes.
AHEAD=$(date -u -d "@$((now + 2 * DAY))" '+%Y-%m-%d %H:%M:%S')

mkdir "$work/bin"

# Writes the file pg_dump was asked for. When the case wants it to fail it
# fails after writing, like a dump that was cut short. With "fast-clock" the
# file gets a modification time in the future, as if the folder were on a
# machine whose clock runs ahead.
cat >"$work/bin/pg_dump" <<'STUB'
#!/bin/sh
echo attempt >>"$STATE/attempts"
for arg in "$@"; do
  case $arg in
    --file=*)
      echo "new" >"${arg#--file=}"
      [ ! -e "$STATE/fast-clock" ] || touch -d "$AHEAD" "${arg#--file=}"
      ;;
  esac
done
[ ! -e "$STATE/fail" ] || exit 1
STUB

# The loop asks psql how many tables there are, and the case for a new stack
# answers none. A restore asks it whether there is a database to replace and
# has it do the renames, which the last cases let fail. What a restore does
# to databases is written down in $STATE/steps, in order.
cat >"$work/bin/psql" <<'STUB'
#!/bin/sh
case "$*" in
  *"RENAME TO"*)
    [ ! -e "$STATE/rename-fails" ] || exit 1
    echo "psql $*" >>"$STATE/steps"
    ;;
  *"from pg_database"*) if [ -e "$STATE/no-database" ]; then echo 0; else echo 1; fi ;;
  *) if [ -e "$STATE/no-tables" ]; then echo 0; else echo 15; fi ;;
esac
STUB

cat >"$work/bin/dropdb" <<'STUB'
#!/bin/sh
echo "dropdb $*" >>"$STATE/steps"
STUB

# The rest of what a restore calls. All of it works.
for tool in pg_isready createdb; do
  printf '#!/bin/sh\nexit 0\n' >"$work/bin/$tool"
  chmod +x "$work/bin/$tool"
done
# pg_restore reads the dump on its stdin, decrypted on the way when it is encrypted.
printf '#!/bin/sh\ncat >/dev/null\nexit 0\n' >"$work/bin/pg_restore"
chmod +x "$work/bin/pg_restore"

# Returns at once and stops the loop, its parent, after $ROUNDS naps.
cat >"$work/bin/sleep" <<'STUB'
#!/bin/sh
echo "$1" >>"$STATE/naps"
if [ "$(wc -l <"$STATE/naps")" -ge "$ROUNDS" ]; then
  kill -TERM "$PPID"
fi
STUB
chmod +x "$work/bin/pg_dump" "$work/bin/psql" "$work/bin/dropdb" "$work/bin/sleep"

fail() {
  echo "FAILED ($case_name): $*" >&2
  echo "--- dumps" >&2
  ls -l "$BACKUP_DIR" >&2
  echo "--- log" >&2
  cat "$STATE/log" >&2
  exit 1
}

# A dump file dated $1 seconds from now (negative for the past).
old_dump() {
  name="planner-$(date -u -d "@$((now + $1))" +%Y%m%dT%H%M%SZ).dump"
  echo "old" >"$BACKUP_DIR/$name"
  echo "$name"
}

begin() {
  case_name=$1
  STATE="$work/state"
  BACKUP_DIR="$work/dumps"
  rm -rf "$STATE" "$BACKUP_DIR"
  mkdir "$STATE" "$BACKUP_DIR"
  : >"$STATE/attempts"
  : >"$STATE/naps"
  : >"$STATE/steps"
  export STATE BACKUP_DIR ROUNDS AHEAD
  export BACKUP_KEEP=3 BACKUP_INTERVAL_SECONDS=$DAY PGDATABASE=planner
}

# Runs the loop until the stand-in for sleep stops it. The timeout is for a
# loop that never pauses at all.
run_loop() {
  status=0
  PATH="$work/bin:$PATH" timeout 30 sh "$here/backup.sh" >"$STATE/log" 2>&1 || status=$?
  [ "$status" -eq 0 ] || fail "the loop did not stop by itself (exit $status), so it ran without pausing"
}

count() {
  wc -l <"$1" | tr -d ' '
}

dump_count() {
  ls "$BACKUP_DIR" | grep -c '\.dump$' || true
}

# $1: how many dumps the loop should have made, $2: how many files to find.
expect() {
  attempts=$(count "$STATE/attempts")
  [ "$attempts" -eq "$1" ] || fail "expected $1 dump(s) to be made, saw $attempts"
  [ "$(dump_count)" -eq "$2" ] || fail "expected $2 dump files, found $(dump_count)"
  [ "$(count "$STATE/naps")" -ge "$ROUNDS" ] || fail "the loop stopped before $ROUNDS pauses"
  echo "ok: $case_name"
}

begin "no dumps yet: one dump, then it waits"
run_loop
expect 1 1

begin "a new stack, before the api has run the migrations: it waits"
touch "$STATE/no-tables"
run_loop
expect 0 0

begin "newest dump two days old: one dump, the oldest goes"
oldest=$(old_dump $((-4 * DAY)))
old_dump $((-3 * DAY)) >/dev/null
old_dump $((-2 * DAY)) >/dev/null
run_loop
expect 1 3
[ ! -e "$BACKUP_DIR/$oldest" ] || fail "the oldest dump was kept"

begin "newest dump an hour old: nothing to do"
old_dump $((-2 * DAY)) >/dev/null
old_dump $((-1 * DAY)) >/dev/null
old_dump -3600 >/dev/null
run_loop
expect 0 3

begin "modification times in the future, as on a share with a fast clock"
old_dump $((-2 * DAY)) >/dev/null
old_dump $((-1 * DAY)) >/dev/null
old_dump -3600 >/dev/null
touch -d "$AHEAD" "$BACKUP_DIR"/*.dump
touch "$STATE/fast-clock"
run_loop
expect 0 3

begin "the same share, with a dump that is due: one dump, not one per round"
old_dump $((-4 * DAY)) >/dev/null
old_dump $((-3 * DAY)) >/dev/null
old_dump $((-2 * DAY)) >/dev/null
touch -d "$AHEAD" "$BACKUP_DIR"/*.dump
touch "$STATE/fast-clock"
run_loop
expect 1 3

begin "every dump dated in the future, after the clock was set back"
old_dump $((1 * DAY)) >/dev/null
old_dump $((2 * DAY)) >/dev/null
old_dump $((3 * DAY)) >/dev/null
run_loop
expect 0 3
grep -q "dated in the future" "$STATE/log" || fail "the log does not mention the dates"

begin "dumps dated in the future next to an overdue one"
overdue=$(old_dump $((-2 * DAY)))
old_dump $((2 * DAY)) >/dev/null
old_dump $((3 * DAY)) >/dev/null
run_loop
expect 1 3
[ ! -e "$BACKUP_DIR/$overdue" ] || fail "the overdue dump was kept instead of the new one"
grep -lq new "$BACKUP_DIR"/*.dump || fail "the dump that was just written was pruned"

begin "a file dated 31 February: not a dump to count or to remove"
# From last year, so that it would be the oldest by its name and the first to go.
impossible="planner-$(($(date -u +%Y) - 1))0231T030000Z.dump"
echo "odd" >"$BACKUP_DIR/$impossible"
oldest=$(old_dump $((-4 * DAY)))
old_dump $((-3 * DAY)) >/dev/null
old_dump $((-2 * DAY)) >/dev/null
run_loop
expect 1 4
[ -e "$BACKUP_DIR/$impossible" ] || fail "the file with a date that does not exist was removed"
[ ! -e "$BACKUP_DIR/$oldest" ] || fail "the oldest real dump was kept in its place"
grep -q "$impossible has a date that does not exist" "$STATE/log" || fail "the log does not mention the file"

begin "only a file dated 31 February: it does not count as a backup, so a dump is made"
echo "odd" >"$BACKUP_DIR/$impossible"
run_loop
expect 1 2
[ -e "$BACKUP_DIR/$impossible" ] || fail "the file with a date that does not exist was removed"

begin "pg_dump is cut short: a pause after every attempt, nothing removed"
old_dump $((-4 * DAY)) >/dev/null
old_dump $((-3 * DAY)) >/dev/null
old_dump $((-2 * DAY)) >/dev/null
touch "$STATE/fail"
run_loop
[ "$(count "$STATE/attempts")" -gt 1 ] || fail "the failed dump was not tried again"
[ "$(count "$STATE/attempts")" -le "$(count "$STATE/naps")" ] || fail "two attempts without a pause in between"
[ "$(dump_count)" -eq 3 ] || fail "expected the 3 old dumps to be left alone, found $(dump_count)"
! grep -lq new "$BACKUP_DIR"/*.dump || fail "a dump that was cut short was kept as a backup"
[ -z "$(ls -A "$BACKUP_DIR" | grep '\.partial$' || true)" ] || fail "a partial dump was left behind"
echo "ok: $case_name"

begin "once, with every other dump dated in the future: the new dump stays"
old_dump $((1 * DAY)) >/dev/null
old_dump $((2 * DAY)) >/dev/null
old_dump $((3 * DAY)) >/dev/null
PATH="$work/bin:$PATH" sh "$here/backup.sh" once >"$STATE/log" 2>&1 || fail "once failed"
[ "$(dump_count)" -eq 3 ] || fail "expected 3 dump files, found $(dump_count)"
grep -lq new "$BACKUP_DIR"/*.dump || fail "the dump that was just written was pruned"
echo "ok: $case_name"

# Runs a restore of $1 and leaves its exit status in $status.
run_restore() {
  status=0
  PATH="$work/bin:$PATH" PGUSER=planner sh "$here/backup.sh" restore "$1" >"$STATE/log" 2>&1 || status=$?
}

# The database itself must never be dropped, only the copy and what a swap
# has already replaced.
never_drops_the_database() {
  if grep -q -x "dropdb --if-exists planner" "$STATE/steps"; then
    fail "the database was dropped: $(tr '\n' ';' <"$STATE/steps")"
  fi
}

begin "restore: both renames in one transaction, the old database dropped last"
dump=$(old_dump -3600)
run_restore "$dump"
[ "$status" -eq 0 ] || fail "the restore failed"
never_drops_the_database
swap='BEGIN; ALTER DATABASE "planner" RENAME TO "planner_replaced"; ALTER DATABASE "planner_restoring" RENAME TO "planner"; COMMIT;'
[ "$(grep -c "RENAME TO" "$STATE/steps")" -eq 1 ] || fail "the renames were not done in one call"
grep -q -F "$swap" "$STATE/steps" || fail "the swap is not the two renames in one transaction"
[ "$(tail -n 1 "$STATE/steps")" = "dropdb --if-exists planner_replaced" ] || fail "the old database was not dropped as the last step"
echo "ok: $case_name"

begin "restore: the api is still connected, so the swap fails and nothing changes"
dump=$(old_dump -3600)
touch "$STATE/rename-fails"
run_restore "$dump"
[ "$status" -ne 0 ] || fail "the restore reported success"
never_drops_the_database
grep -q "planner, something is still connected to it. It was left as it was" "$STATE/log" || fail "it does not say that the database was left alone"
[ "$(grep -c -x "dropdb --if-exists planner_restoring" "$STATE/steps")" -eq 2 ] || fail "the copy that was not used was left behind"
echo "ok: $case_name"

begin "restore on a new volume: nothing to replace, and naming the copy fails"
dump=$(old_dump -3600)
touch "$STATE/no-database" "$STATE/rename-fails"
run_restore "$dump"
[ "$status" -ne 0 ] || fail "the restore reported success"
never_drops_the_database
grep -q "the data from $dump is in the database planner_restoring" "$STATE/log" || fail "it does not say where the data is"
finish="psql -U planner -d postgres -c 'ALTER DATABASE \"planner_restoring\" RENAME TO \"planner\"'"
grep -q -F "$finish" "$STATE/log" || fail "it does not print the command that finishes the restore"
[ "$(grep -c -x "dropdb --if-exists planner_restoring" "$STATE/steps")" -eq 1 ] || fail "the copy with the data in it was dropped"
echo "ok: $case_name"

# Stand-ins for age and rclone. age "encrypts" by writing "sealed:" and what
# came in; it decrypts only with a key file that says "right". With
# $STATE/age-fails it writes half and fails. rclone copies into $STATE/bucket,
# and with $STATE/hang it starts a child that would outlive it and waits.
cat >"$work/bin/age" <<'STUB'
#!/bin/sh
out=''
key=''
decrypt=''
while [ $# -gt 0 ]; do
  case $1 in
    -o) out=$2; shift 2 ;;
    -r) shift 2 ;;
    -i) key=$2; shift 2 ;;
    -d) decrypt=yes; shift ;;
    *) in=$1; shift ;;
  esac
done
if [ -n "$decrypt" ]; then
  grep -q right "$key" 2>/dev/null || { echo "age: no identity matched" >&2; exit 1; }
  sed '1s/^sealed://' "$in"
  exit 0
fi
# The keys are tried at the start without -o: those tries work, only writing a dump fails.
if [ -e "$STATE/age-fails" ] && [ -n "$out" ]; then
  echo "sealed:half" >"$out"
  exit 1
fi
if [ -n "$out" ]; then
  { printf 'sealed:'; cat; } >"$out"
else
  { printf 'sealed:'; cat; } >/dev/null
fi
STUB
cat >"$work/bin/rclone" <<'STUB'
#!/bin/sh
mkdir -p "$STATE/bucket"
case $1 in
  copyto)
    if [ -e "$STATE/hang" ]; then
      /bin/sleep 300 &
      echo $! >"$STATE/child"
      /bin/sleep 300
    fi
    cp "$2" "$STATE/bucket/${3##*/}"
    ;;
  lsf) ls -1 "$STATE/bucket" ;;
  deletefile) rm -f "$STATE/bucket/${2##*/}" ;;
esac
STUB
chmod +x "$work/bin/age" "$work/bin/rclone"

run_once() {
  status=0
  PATH="$work/bin:$PATH" sh "$here/backup.sh" once >"$STATE/log" 2>&1 || status=$?
}

status_says() {
  grep -q -x "$1" "$BACKUP_DIR/.backup-status" || fail "the status file does not say $1: $(tr '\n' ';' <"$BACKUP_DIR/.backup-status" 2>/dev/null)"
}

# pg_dump writes to stdout when it is given no --file, which is how it feeds age.
cat >"$work/bin/pg_dump" <<'STUB'
#!/bin/sh
echo attempt >>"$STATE/attempts"
file=''
for arg in "$@"; do
  case $arg in --file=*) file=${arg#--file=} ;; esac
done
if [ -n "$file" ]; then echo "new" >"$file"; else echo "new"; fi
[ ! -e "$STATE/fail" ] || exit 1
STUB
chmod +x "$work/bin/pg_dump"

begin "encrypted: only the .age file reaches the disk"
export BACKUP_ENCRYPT_TO=age1standin
run_once
[ "$status" -eq 0 ] || fail "once failed"
dump=$(ls "$BACKUP_DIR" | grep '\.dump\.age$' || true)
[ -n "$dump" ] || fail "no encrypted dump was written"
[ -z "$(ls -A "$BACKUP_DIR" | grep -E '\.dump$|\.partial$|\.status$' || true)" ] || fail "a plain or partial file was left on disk: $(ls -A "$BACKUP_DIR")"
grep -q '^sealed:new' "$BACKUP_DIR/$dump" || fail "the dump did not go through age"
status_says "last_outcome=ok"
status_says "last_good=$dump"
echo "ok: $case_name"

begin "encrypted: age fails half way, nothing is kept"
export BACKUP_ENCRYPT_TO=age1standin
touch "$STATE/age-fails"
run_once
[ "$status" -ne 0 ] || fail "once reported success"
[ -z "$(ls -A "$BACKUP_DIR" | grep -v '^\.backup-status$' || true)" ] || fail "something was left behind: $(ls -A "$BACKUP_DIR")"
status_says "last_outcome=dump_failed"
echo "ok: $case_name"

begin "encrypted: pg_dump fails under age, nothing is kept"
export BACKUP_ENCRYPT_TO=age1standin
touch "$STATE/fail"
run_once
[ "$status" -ne 0 ] || fail "once reported success"
[ -z "$(ls "$BACKUP_DIR" | grep '\.age$' || true)" ] || fail "a dump that failed was kept"
echo "ok: $case_name"
unset BACKUP_ENCRYPT_TO

begin "a restore of an encrypted dump without the key, or with the wrong one, changes nothing"
echo "sealed:old" >"$BACKUP_DIR/planner-20260301T030000Z.dump.age"
run_restore planner-20260301T030000Z.dump.age
[ "$status" -ne 0 ] || fail "a restore without BACKUP_DECRYPT_KEY went ahead"
grep -q "BACKUP_DECRYPT_KEY" "$STATE/log" || fail "it does not say which key is missing"
echo wrong >"$STATE/key"
export BACKUP_DECRYPT_KEY="$STATE/key"
run_restore planner-20260301T030000Z.dump.age
[ "$status" -ne 0 ] || fail "a restore with the wrong key went ahead"
[ ! -s "$STATE/steps" ] || fail "the database was touched: $(tr '\n' ';' <"$STATE/steps")"
echo right >"$STATE/key"
run_restore planner-20260301T030000Z.dump.age
[ "$status" -eq 0 ] || fail "a restore with the right key failed: $(cat "$STATE/log")"
unset BACKUP_DECRYPT_KEY
echo "ok: $case_name"

begin "copies: to a second folder, pruned there like the first"
mkdir "$work/copies"
for days in 5 4 3; do
  echo old >"$work/copies/planner-$(date -u -d "@$((now - days * DAY))" +%Y%m%dT%H%M%SZ).dump"
done
export BACKUP_COPY_DIR="$work/copies"
run_once
unset BACKUP_COPY_DIR
[ "$status" -eq 0 ] || fail "once failed: $(cat "$STATE/log")"
[ "$(ls "$work/copies" | grep -c '\.dump$')" -eq 3 ] || fail "expected 3 copies, found $(ls "$work/copies")"
grep -lq new "$work/copies"/*.dump || fail "the new dump was not copied"
[ -z "$(ls -A "$work/copies" | grep '\.partial$' || true)" ] || fail "a partial copy was left behind"
status_says "last_outcome=ok"
rm -rf "$work/copies"
echo "ok: $case_name"

begin "copies: a copy that hangs is stopped with everything it started, and counts as failed"
touch "$STATE/hang"
export BACKUP_RCLONE_REMOTE=standin:bucket BACKUP_COPY_TIMEOUT_SECONDS=2
run_once
unset BACKUP_RCLONE_REMOTE BACKUP_COPY_TIMEOUT_SECONDS
[ "$status" -eq 0 ] || fail "a failed copy failed the dump"
[ "$(dump_count)" -eq 1 ] || fail "the dump itself was not kept"
status_says "last_outcome=copy_failed"
grep -q "took longer than 2s and was stopped" "$STATE/log" || fail "the log does not say the copy was stopped"
child=$(cat "$STATE/child" 2>/dev/null || echo '')
[ -n "$child" ] || fail "the copy did not start"
! kill -0 "$child" 2>/dev/null || fail "a process the copy started is still running"
echo "ok: $case_name"

begin "health: a recent good dump is healthy, one older than twice the interval is not"
run_once
PATH="$work/bin:$PATH" sh "$here/backup.sh" health >"$STATE/log" 2>&1 || fail "unhealthy after a good dump: $(cat "$STATE/log")"
good=$(sed -n 's/^last_good=//p' "$BACKUP_DIR/.backup-status")
sed -i "s/^last_good_at=.*/last_good_at=$((now - 3 * DAY))/" "$BACKUP_DIR/.backup-status"
! PATH="$work/bin:$PATH" sh "$here/backup.sh" health >"$STATE/log" 2>&1 || fail "healthy with a good dump three days old"
grep -q "more than twice the interval" "$STATE/log" || fail "it does not say why"
rm -f "$BACKUP_DIR/$good"
sed -i "s/^last_good_at=.*/last_good_at=$now/" "$BACKUP_DIR/.backup-status"
! PATH="$work/bin:$PATH" sh "$here/backup.sh" health >/dev/null 2>&1 || fail "healthy while the last good dump is gone"
echo "ok: $case_name"

echo "OK: the loop, the pruning and the restore held in every case"
