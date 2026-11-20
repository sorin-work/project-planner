#!/bin/sh
# Database backups for project-planner. Runs in the postgres image, which
# already has pg_dump and pg_restore, and connects with the usual PG*
# variables (PGHOST, PGUSER, PGPASSWORD, PGDATABASE).
#
#   backup.sh                  dump once a day until stopped (the compose service)
#   backup.sh once             dump now and exit
#   backup.sh restore <file>   replace the database with a dump
#
# Dumps go to $BACKUP_DIR as planner-<UTC time>.dump in pg_dump's custom
# format. After every dump only the newest $BACKUP_KEEP are kept.
#
# With ATTACHMENTS_DIR set, the files attached to tasks are archived next to
# each dump as planner-<UTC time>.files.tar, written and pruned with it, and
# a restore puts them back from the archive that goes with the dump. The
# dump is taken first and the archive right after it. Files are only ever
# added or removed, never changed, so a file attached in between is an
# extra file in the archive, which the api's sweep removes, and a file
# removed in between is missing from it: its row is in the dump, and the
# api answers 404 for it.
#
# The time in the name is the only time that counts, for the age of a dump
# and for the order they are pruned in. Modification times are not looked
# at: on a network share they come from another machine's clock.
set -eu

dir=${BACKUP_DIR:-/backups}
keep=${BACKUP_KEEP:-7}
interval=${BACKUP_INTERVAL_SECONDS:-86400}
files=${ATTACHMENTS_DIR:-}
# Who the attachments belong to, as uid:gid. Without it, the owner of the folder.
files_owner=${ATTACHMENTS_OWNER:-}

# A dump holds the password hashes, the rates and every hour that was
# logged: owner only.
umask 077

# The dump and the archive being written, if any, so that they can be
# removed when stopped.
partial=''
partial_files=''

log() {
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*"
}

fail() {
  log "$*" >&2
  exit 1
}

is_positive_integer() {
  case $1 in
    '' | 0* | *[!0-9]*) return 1 ;;
  esac
}

check_settings() {
  is_positive_integer "$keep" || fail "BACKUP_KEEP must be a whole number above zero, got '$keep'"
  is_positive_integer "$interval" || fail "BACKUP_INTERVAL_SECONDS must be a whole number above zero, got '$interval'"
  [ -n "${PGDATABASE:-}" ] || fail "PGDATABASE is not set"
  if [ ! -d "$dir" ] || [ ! -w "$dir" ]; then
    fail "cannot write to $dir"
  fi
  if [ -n "$files" ] && { [ ! -d "$files" ] || [ ! -r "$files" ]; }; then
    fail "cannot read the attachments in $files"
  fi
  # A volume that this service mounted before the api did belongs to root, and
  # the api could not write to it.
  if [ -n "$files" ] && [ -n "$files_owner" ] && [ "$(id -u)" = 0 ]; then
    chown "$files_owner" "$files" || fail "could not give $files to $files_owner"
  fi
}

# The archive of the attachments that goes with a dump.
archive_of() {
  echo "${1%.dump}.files.tar"
}

# File names of the finished dumps, newest first, going by the time in the
# name.
dumps() {
  ls -1 "$dir" | grep -E '^planner-[0-9]{8}T[0-9]{6}Z\.dump$' | sort -r
}

# The time in a dump's name as seconds since 1970. Prints nothing when the
# name does not hold a real date.
dump_time() {
  date -u -d "$(echo "$1" | sed -E 's/^planner-(....)(..)(..)T(..)(..)(..)Z\.dump$/\1-\2-\3 \4:\5:\6/')" +%s 2>/dev/null || true
}

# Removes the oldest dumps until $keep are left. The dump that was just
# written ($1) is never one of them: after the clock was set back it sorts
# below older dumps, and it is still the one worth keeping.
prune() {
  dumps | grep -v -x -F "$1" | tail -n +"$keep" | while read -r old; do
    rm -f "$dir/$old" "$dir/$(archive_of "$old")"
    log "removed $old"
  done
}

# pg_dump writes to a hidden .partial file that is renamed when it is
# complete, so a dump that was cut short never counts as a backup and never
# pushes an older one out. Every step is checked by hand because "set -e" is
# off inside "if dump".
dump() {
  name="planner-$(date -u +%Y%m%dT%H%M%SZ).dump"
  archive=$(archive_of "$name")
  partial="$dir/.$name.partial"
  partial_files=''
  if ! pg_dump --format=custom --file="$partial"; then
    give_up
    return 1
  fi
  # The attachments, as they are right after the dump. Without both there
  # is no backup, and neither file is kept.
  if [ -n "$files" ]; then
    partial_files="$dir/.$archive.partial"
    if ! tar -C "$files" -cf "$partial_files" .; then
      log "could not archive the attachments in $files"
      give_up
      return 1
    fi
  fi
  # Docker runs this as root. Give the dump to whoever owns the folder, so
  # that on the host it belongs to the same user as the folder it is in.
  if [ "$(id -u)" = 0 ]; then
    chown "$(stat -c %u:%g "$dir")" "$partial" ${partial_files:+"$partial_files"} 2>/dev/null || true
  fi
  # The archive is in place before the dump, so a dump never counts as a
  # backup without its files.
  if [ -n "$partial_files" ] && ! mv "$partial_files" "$dir/$archive"; then
    give_up
    return 1
  fi
  if ! mv "$partial" "$dir/$name"; then
    rm -f "$dir/$archive"
    give_up
    return 1
  fi
  partial=''
  partial_files=''
  if [ -n "$files" ]; then
    log "wrote $name ($(wc -c <"$dir/$name") bytes) and $archive ($(wc -c <"$dir/$archive") bytes)"
  else
    log "wrote $name ($(wc -c <"$dir/$name") bytes)"
  fi
  prune "$name"
}

# Removes what a dump that did not work left behind.
give_up() {
  rm -f "$partial" ${partial_files:+"$partial_files"}
  partial=''
  partial_files=''
}

# When the newest dump was made, as seconds since 1970, for a clock that
# reads $1. Dumps dated after that are skipped: the clock was set back since
# they were made, so they say nothing about the age of the newest real one.
# Prints nothing when no dump is left to go by.
newest_dump_time() {
  dumps | while read -r candidate; do
    made=$(dump_time "$candidate")
    if [ -n "$made" ] && [ "$made" -le "$1" ]; then
      echo "$made"
      break
    fi
  done
}

# Sleeps in the background so that "docker compose stop" is not kept waiting.
nap() {
  sleep "$1" &
  wait $!
}

# The daily loop. What it has to get right, because getting it wrong deletes
# backups: a dump followed at once by another dump prunes one old dump per
# round until the history is gone.
#
#   - "last" is when the newest dump was made. It is read from the file names
#     once, at the start. After that it is only ever set to the current time,
#     so nothing on disk and no change of the clock can make a dump look
#     overdue twice in a row.
#   - A dump dated in the future counts as made now, never as overdue.
#   - After every attempt, whether it worked or not, there is a pause before
#     the next one.
#   - The wait is slept in steps of at most a minute, with a look at the clock
#     after each. One long sleep would not count the time a laptop spends
#     suspended, and the dumps would come less often than daily.
run() {
  check_settings
  trap 'rm -f "$partial" ${partial_files:+"$partial_files"}; log "stopping"; exit 0' TERM INT
  step=$((interval < 60 ? interval : 60))
  # Left behind if the last run was killed in the middle of a dump.
  rm -f "$dir"/.planner-*.partial
  log "dumping $PGDATABASE to $dir every ${interval}s, keeping the newest $keep"

  now=$(date +%s)
  last=$(newest_dump_time "$now")
  if [ -z "$last" ] && [ -n "$(dumps | head -n 1)" ]; then
    log "every dump in $dir is dated in the future, counting the newest as made now"
    last=$now
  fi

  while true; do
    now=$(date +%s)
    if [ -n "$last" ]; then
      # The clock was set back since the last dump.
      [ "$last" -le "$now" ] || last=$now
      left=$((interval - (now - last)))
      if [ "$left" -gt 0 ]; then
        nap "$((left < step ? left : step))"
        continue
      fi
    fi
    # On a new stack this starts before the api has run the migrations.
    # Flyway's own table comes first and is not worth a dump by itself.
    tables=$(psql -AtXq -c "select count(*) from pg_tables where schemaname not in ('pg_catalog', 'information_schema') and tablename <> 'flyway_schema_history'" 2>/dev/null) || tables=unknown
    if [ "$tables" = 0 ]; then
      log "the api has not created the tables of $PGDATABASE yet, looking again in ${step}s"
    elif dump; then
      last=$now
    else
      log "dump failed, trying again in ${step}s"
    fi
    nap "$step"
  done
}

# dropdb without the notice it prints when there is nothing to drop.
drop() {
  PGOPTIONS='-c client_min_messages=warning' dropdb --if-exists "$1"
}

# Restores into a new database first and swaps it in only when that worked,
# so a damaged dump cannot take the current data with it. A new database
# also has nothing in it that the dump does not know about. Restoring over
# the old one would keep the tables of migrations newer than the dump, while
# the restored flyway_schema_history says they never ran, and the api would
# fail on them at its next start.
#
# The swap is two renames in one transaction, and the old database is
# dropped after it. Whenever this script is killed there is a database with
# the right name: the old one before the commit, the restored one after.
restore() {
  check_settings
  file=${1:-}
  if [ -z "$file" ]; then
    echo "usage: restore <file>" >&2
    echo "dumps in $dir, newest first:" >&2
    dumps >&2
    exit 1
  fi
  [ -f "$file" ] || file="$dir/$file"
  [ -f "$file" ] || fail "no such dump: $1"

  # The attachments are unpacked first, next to the ones in use, so that an
  # archive that cannot be read stops the restore before anything changed.
  staged=''
  if [ -n "$files" ]; then
    archive=$(archive_of "$file")
    if [ -f "$archive" ]; then
      staged="$files/.restoring"
      rm -rf "$staged"
      mkdir "$staged"
      # Until the swap starts, a restore that stops leaves no unpacked copy behind.
      trap '[ -z "$staged" ] || rm -rf "$staged"' EXIT
      # Nothing in the archive may point outside the folder it is unpacked in.
      if ! names=$(tar -tf "$archive"); then
        fail "could not read $(basename "$archive"), nothing was restored"
      fi
      if echo "$names" | grep -q -E '^/|(^|/)\.\.(/|$)'; then
        fail "$(basename "$archive") names a path outside the attachments folder, nothing was restored"
      fi
      if ! tar -C "$staged" -xf "$archive"; then
        fail "could not unpack $(basename "$archive"), nothing was restored"
      fi
      # The api only ever writes plain files in plain folders. Anything else that an archive holds,
      # a link, a device, a pipe, or a file hard-linked to another, could make it serve a file
      # from elsewhere, so it is removed and counted. This comes right after unpacking and before
      # anything else touches what was unpacked: BusyBox tar lists a hard link like a plain file
      # and unpacks one to a file outside the folder as a second name of that file, which the
      # chown -R of the swap would otherwise give to the api's user.
      odd=$(find "$staged" -mindepth 1 \( ! -type f ! -type d \) -o \( -type f -links +1 \) | wc -l)
      if [ "$odd" -gt 0 ]; then
        find "$staged" -mindepth 1 \( \( ! -type f ! -type d \) -o \( -type f -links +1 \) \) -exec rm -f {} \;
        log "left out $odd entries of $(basename "$archive") that are not plain files or folders"
      fi
    else
      log "there is no archive of the attachments next to $(basename "$file"), the attachments are left as they are"
    fi
  fi

  # On a new volume "docker compose run" starts the database for this, and
  # the image can pass its health check while it is still setting itself up,
  # a moment before it takes connections from other containers.
  waited=0
  until pg_isready -q -d postgres; do
    waited=$((waited + 1))
    [ "$waited" -lt 30 ] || fail "the database server does not answer"
    nap 1
  done

  incoming="${PGDATABASE}_restoring"
  replaced="${PGDATABASE}_replaced"
  drop "$incoming"
  createdb "$incoming"
  if ! pg_restore --exit-on-error --no-owner --dbname="$incoming" "$file"; then
    drop "$incoming"
    fail "could not restore $(basename "$file"), $PGDATABASE was left as it was"
  fi

  rename="ALTER DATABASE \"$incoming\" RENAME TO \"$PGDATABASE\""
  if ! existing=$(psql -AtXq -d postgres -c "select count(*) from pg_database where datname = '$PGDATABASE'"); then
    drop "$incoming"
    fail "could not look up $PGDATABASE, it was left as it was"
  fi
  if [ "$existing" = 0 ]; then
    # A new volume, or a name nothing had yet: there is nothing to replace.
    if ! psql -qX -v ON_ERROR_STOP=1 -d postgres -c "$rename"; then
      log "There was no database $PGDATABASE to replace, and the restored copy could not be given that name." >&2
      log "Nothing is lost: the data from $(basename "$file") is in the database $incoming." >&2
      log "To finish the restore, run this against the same server:" >&2
      log "  psql ${PGUSER:+-U $PGUSER }-d postgres -c '$rename'" >&2
      log "With the compose stack that is:" >&2
      log "  docker compose exec db psql ${PGUSER:+-U $PGUSER }-d postgres -c '$rename'" >&2
      exit 1
    fi
  else
    # What an earlier restore replaced, if it was killed before its last step.
    if ! drop "$replaced"; then
      drop "$incoming"
      fail "could not remove $replaced, which an earlier restore left behind. $PGDATABASE was left as it was."
    fi
    # A database cannot be renamed while anything is connected to it, which
    # is how a running api is noticed.
    if ! psql -qX -v ON_ERROR_STOP=1 -d postgres -c "BEGIN; ALTER DATABASE \"$PGDATABASE\" RENAME TO \"$replaced\"; $rename; COMMIT;"; then
      drop "$incoming"
      fail "could not replace $PGDATABASE, something is still connected to it. It was left as it was. Stop the api and try again."
    fi
    drop "$replaced" || log "the old database is still there as $replaced, remove it with: dropdb $replaced" >&2
  fi
  log "restored $PGDATABASE from $(basename "$file")"
  [ -z "$staged" ] || swap_files
}

# Puts the unpacked attachments in the place of the ones in use. Only what
# the archive holds is there afterwards, owned by whoever owns the folder,
# which is the api's user.
swap_files() {
  # From here on what is unpacked is the only copy of some of it: keep it if this fails.
  trap - EXIT
  find "$files" -mindepth 1 -maxdepth 1 ! -name .restoring -exec rm -rf {} \; ||
    fail "$PGDATABASE was restored, but the attachments could not be cleared. The ones from the backup are in $staged."
  for entry in "$staged"/* "$staged"/.[!.]*; do
    [ ! -e "$entry" ] || mv "$entry" "$files/" ||
      fail "$PGDATABASE was restored, but the attachments could not all be put back. The rest are in $staged."
  done
  rmdir "$staged"
  if [ "$(id -u)" = 0 ]; then
    chown -R "${files_owner:-$(stat -c %u:%g "$files")}" "$files" 2>/dev/null || true
  fi
  log "restored the attachments from $(basename "$(archive_of "$file")") ($(find "$files" -type f | wc -l) files)"
}

case ${1:-run} in
  run)
    run
    ;;
  once)
    check_settings
    dump || fail "dump failed"
    ;;
  restore)
    shift
    restore "$@"
    ;;
  *)
    echo "usage: backup.sh [run | once | restore <file>]" >&2
    exit 1
    ;;
esac
