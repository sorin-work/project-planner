#!/bin/sh
# Database backups for project-planner. Runs in the postgres image, which
# already has pg_dump and pg_restore, and connects with the usual PG*
# variables (PGHOST, PGUSER, PGPASSWORD, PGDATABASE).
#
#   backup.sh                  dump once a day until stopped (the compose service)
#   backup.sh once             dump now and exit
#   backup.sh restore <file>   replace the database with a dump
#   backup.sh health           whether a good dump is recent (the healthcheck)
#
# Dumps go to $BACKUP_DIR as planner-<UTC time>.dump in pg_dump's custom
# format. After every dump only the newest $BACKUP_KEEP are kept.
#
# With BACKUP_ENCRYPT_TO (one or more age public keys, separated by spaces)
# the dump and the archive are written through age as they are made, and
# only the encrypted file ever touches the disk: planner-<time>.dump.age and
# .files.tar.age. A restore of one needs the private key in the file named
# by BACKUP_DECRYPT_KEY.
#
# Every finished dump, with its archive, is copied to BACKUP_COPY_DIR (a
# mounted share or another disk) and to BACKUP_RCLONE_REMOTE (an rclone
# remote, for a bucket that can be self-hosted), and pruned there the same
# way. A copy that fails does not undo the dump, but counts as a failure.
#
# After every run a status file, $BACKUP_DIR/.backup-status, says when the
# newest good dump was made (by its name), how the last run went and the
# interval. "health" reads it, and so can the api.
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
encrypt_to=${BACKUP_ENCRYPT_TO:-}
decrypt_key=${BACKUP_DECRYPT_KEY:-}
copy_dir=${BACKUP_COPY_DIR:-}
remote=${BACKUP_RCLONE_REMOTE:-}
copy_timeout=${BACKUP_COPY_TIMEOUT_SECONDS:-3600}
status_file="$dir/.backup-status"

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
  is_positive_integer "$copy_timeout" || fail "BACKUP_COPY_TIMEOUT_SECONDS must be a whole number above zero, got '$copy_timeout'"
  if [ -n "$encrypt_to" ]; then
    command -v age >/dev/null || fail "BACKUP_ENCRYPT_TO is set, but age is not installed (use the image in backup/)"
    # A key that age does not take would only show at the first dump.
    for key in $encrypt_to; do
      echo check | age -r "$key" >/dev/null 2>&1 || fail "BACKUP_ENCRYPT_TO holds a key that age does not take: $key"
    done
  fi
  if [ -n "$copy_dir" ] && { [ ! -d "$copy_dir" ] || [ ! -w "$copy_dir" ]; }; then
    fail "cannot write to BACKUP_COPY_DIR $copy_dir"
  fi
  if [ -n "$remote" ]; then
    command -v rclone >/dev/null || fail "BACKUP_RCLONE_REMOTE is set, but rclone is not installed (build backup/ with BACKUP_PACKAGES=rclone)"
  fi
  # A volume that this service mounted before the api did belongs to root, and
  # the api could not write to it.
  if [ -n "$files" ] && [ -n "$files_owner" ] && [ "$(id -u)" = 0 ]; then
    chown "$files_owner" "$files" || fail "could not give $files to $files_owner"
  fi
}

# The archive of the attachments that goes with a dump: .files.tar beside a
# .dump, .files.tar.age beside a .dump.age.
archive_of() {
  case $1 in
    *.dump.age) echo "${1%.dump.age}.files.tar.age" ;;
    *) echo "${1%.dump}.files.tar" ;;
  esac
}

DUMP_NAME='^planner-[0-9]{8}T[0-9]{6}Z\.dump(\.age)?$'

# Every file that is named like a dump, newest first, going by the time in
# the name. In the folder $1, the backup folder by default.
named_like_dumps() {
  ls -1 "${1:-$dir}" | grep -E "$DUMP_NAME" | sort -r
}

# The time in a dump's name as seconds since 1970. Prints nothing when the
# name does not hold a real date. The date in this image does not refuse 31
# February, it makes 3 March of it. So the time is written out again and has
# to come back as the name it was read from.
dump_time() {
  stamp=${1#planner-}
  stamp=${stamp%.age}
  stamp=${stamp%.dump}
  seconds=$(date -u -d "$(echo "$stamp" | sed -E 's/^(....)(..)(..)T(..)(..)(..)Z$/\1-\2-\3 \4:\5:\6/')" +%s 2>/dev/null) || return 0
  if [ "$(date -u -d "@$seconds" +%Y%m%dT%H%M%SZ 2>/dev/null)" = "$stamp" ]; then
    echo "$seconds"
  fi
}

# File names of the finished dumps, newest first. A file with a date that
# does not exist is not one of them: nothing says how old it is, so it is
# neither counted nor removed.
dumps() {
  named_like_dumps "${1:-$dir}" | while read -r name; do
    [ -z "$(dump_time "$name")" ] || echo "$name"
  done
}

# Says which files look like dumps and are left alone because of their date.
warn_about_impossible_dates() {
  named_like_dumps | while read -r name; do
    [ -n "$(dump_time "$name")" ] || log "$name has a date that does not exist. It is left alone: not counted as a dump and never removed."
  done
}

# Removes the oldest dumps until $keep are left. The dump that was just
# written ($1) is never one of them: after the clock was set back it sorts
# below older dumps, and it is still the one worth keeping.
prune() {
  dumps | grep -v -x -F "$1" | tail -n +"$keep" | while read -r old; do
    rm -f "$dir/$old" "$dir/$(archive_of "$old")"
    log "removed $old"
  done
  warn_about_impossible_dates
}

# Writes what comes in on stdin to the file $1, encrypted to every key in
# BACKUP_ENCRYPT_TO.
seal() {
  out=$1
  set --
  for key in $encrypt_to; do
    set -- "$@" -r "$key"
  done
  age "$@" -o "$out"
}

# Runs the command "$2 ..." and seals what it writes into $1. Fails when
# either the command or age fails: a pipeline only reports the last one, so
# the command's own status goes through a file next to the output.
sealed() {
  target=$1
  shift
  { "$@"; echo $? >"$target.status"; } | seal "$target"
  sealed_status=$?
  made_status=$(cat "$target.status" 2>/dev/null || echo 1)
  rm -f "$target.status"
  [ "$sealed_status" = 0 ] && [ "$made_status" = 0 ]
}

# The status file, written whole or not at all, and readable by everybody:
# it names no secret, and the api reads it as another user. $1 is how the run went (ok,
# dump_failed, copy_failed), $2 the name of a dump that was just written.
# Without $2 the newest good dump stays the one the file named before. The
# time of a dump is the time in its name, as everywhere else.
write_status() {
  good=${2:-}
  if [ -z "$good" ] && [ -f "$status_file" ]; then
    good=$(sed -n 's/^last_good=//p' "$status_file")
  fi
  good_at=''
  [ -z "$good" ] || good_at=$(dump_time "$good")
  if ! {
    echo "last_run_at=$(date +%s)"
    echo "last_outcome=$1"
    echo "last_good=$good"
    echo "last_good_at=$good_at"
    echo "interval=$interval"
  } >"$status_file.partial" || ! chmod 644 "$status_file.partial" || ! mv "$status_file.partial" "$status_file"; then
    log "could not write $status_file" >&2
  fi
}

# Copies the files "$@" of the dump just written to BACKUP_COPY_DIR and
# BACKUP_RCLONE_REMOTE. A copy that fails or takes longer than
# BACKUP_COPY_TIMEOUT_SECONDS is logged and recorded in the status file;
# the dump is there and counts.
#
# The copy runs in a session of its own, and so does the watchdog that stops
# it. A copy is more than one process (rclone and what it starts), and
# killing only the shell would leave the rest going beside the next dump and
# its pruning. So a timeout signals the whole session, and so does the end of
# the copy, for anything it left behind. The watchdog sleeps with /bin/sleep,
# whatever PATH says.
copy_off() {
  [ -n "$copy_dir$remote" ] || return 0
  fired="$dir/.copy-timed-out"
  rm -f "$fired"
  setsid sh "$0" copy "$@" &
  copy_pid=$!
  setsid sh -c '/bin/sleep "$1" && : >"$3" && kill -TERM -"$2" 2>/dev/null' watchdog "$copy_timeout" "$copy_pid" "$fired" &
  watchdog_pid=$!
  copied=0
  wait "$copy_pid" || copied=$?
  kill -TERM -"$watchdog_pid" 2>/dev/null || true
  kill -TERM "$watchdog_pid" 2>/dev/null || true
  wait "$watchdog_pid" 2>/dev/null || true
  stop_session "$copy_pid"
  if [ -e "$fired" ]; then
    rm -f "$fired"
    log "the copy of $(basename "$1") took longer than ${copy_timeout}s and was stopped. The dump itself is fine." >&2
    return 1
  fi
  if [ "$copied" != 0 ]; then
    log "the copy of $(basename "$1") failed (exit $copied). The dump itself is fine." >&2
    return 1
  fi
  log "copied $(basename "$1") to ${copy_dir:+$copy_dir }${remote:+$remote}"
}

# Ends whatever is left in the session $1 and waits until it is gone: up to
# ten seconds after TERM, then KILL.
stop_session() {
  kill -TERM -"$1" 2>/dev/null || return 0
  tries=0
  while kill -0 -"$1" 2>/dev/null; do
    tries=$((tries + 1))
    if [ "$tries" -gt 10 ]; then
      kill -KILL -"$1" 2>/dev/null || true
      return 0
    fi
    /bin/sleep 1
  done
}

# "backup.sh copy <files>": what copy_off runs in its own session. Each file
# is written under a hidden name and renamed, so a copy cut short is never
# taken for a dump. Then the copies are pruned like the folder itself, by
# the names, never the dump just copied.
copy_files() {
  newest=$(basename "$1")
  for file in "$@"; do
    name=$(basename "$file")
    if [ -n "$copy_dir" ]; then
      cp "$file" "$copy_dir/.$name.partial" && mv "$copy_dir/.$name.partial" "$copy_dir/$name" || {
        rm -f "$copy_dir/.$name.partial"
        exit 1
      }
    fi
    if [ -n "$remote" ]; then
      rclone copyto "$file" "$remote/$name" || exit 1
    fi
  done
  if [ -n "$copy_dir" ]; then
    dumps "$copy_dir" | grep -v -x -F "$newest" | tail -n +"$keep" | while read -r old; do
      rm -f "$copy_dir/$old" "$copy_dir/$(archive_of "$old")"
      log "removed $old from $copy_dir"
    done
  fi
  if [ -n "$remote" ]; then
    listed=$(rclone lsf --files-only "$remote") || exit 1
    echo "$listed" | grep -E "$DUMP_NAME" | sort -r | while read -r old; do
      [ -n "$(dump_time "$old")" ] && echo "$old"
    done | grep -v -x -F "$newest" | tail -n +"$keep" | while read -r old; do
      rclone deletefile "$remote/$old" || exit 1
      if echo "$listed" | grep -q -x -F "$(archive_of "$old")"; then
        rclone deletefile "$remote/$(archive_of "$old")" || exit 1
      fi
      log "removed $old from $remote"
    done
  fi
}

# pg_dump writes to a hidden .partial file that is renamed when it is
# complete, so a dump that was cut short never counts as a backup and never
# pushes an older one out. Every step is checked by hand because "set -e" is
# off inside "if dump".
dump() {
  name="planner-$(date -u +%Y%m%dT%H%M%SZ).dump${encrypt_to:+.age}"
  archive=$(archive_of "$name")
  partial="$dir/.$name.partial"
  partial_files=''
  if [ -n "$encrypt_to" ]; then
    sealed "$partial" pg_dump --format=custom && dumped=0 || dumped=1
  else
    pg_dump --format=custom --file="$partial" && dumped=0 || dumped=1
  fi
  if [ "$dumped" != 0 ]; then
    log "pg_dump${encrypt_to:+ or age} failed" >&2
    give_up
    return 1
  fi
  # The attachments, as they are right after the dump. Without both there
  # is no backup, and neither file is kept.
  if [ -n "$files" ]; then
    partial_files="$dir/.$archive.partial"
    if [ -n "$encrypt_to" ]; then
      sealed "$partial_files" tar -C "$files" -cf - . && archived=0 || archived=1
    else
      tar -C "$files" -cf "$partial_files" . && archived=0 || archived=1
    fi
    if [ "$archived" != 0 ]; then
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
  write_status ok "$name"
  if [ -n "$files" ]; then
    copy_off "$dir/$name" "$dir/$archive" || write_status copy_failed
  else
    copy_off "$dir/$name" || write_status copy_failed
  fi
}

# Removes what a dump that did not work left behind.
give_up() {
  rm -f "$partial" ${partial_files:+"$partial_files"} "$partial.status" ${partial_files:+"$partial_files.status"}
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
  log "dumping $PGDATABASE to $dir every ${interval}s, keeping the newest $keep${encrypt_to:+, encrypted}"
  warn_about_impossible_dates
  # A folder from before the status file: the newest dump there is the last good one.
  if [ ! -f "$status_file" ] && [ -n "$(dumps | head -n 1)" ]; then
    write_status ok "$(dumps | head -n 1)"
  fi

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
      write_status dump_failed
      log "dump failed, trying again in ${step}s"
    fi
    nap "$step"
  done
}

# The healthcheck: healthy while the newest good dump, by the time in its
# name, is at most twice the interval old and still there. A time ahead of
# the clock counts as now, as in the loop.
health() {
  is_positive_integer "$interval" || fail "BACKUP_INTERVAL_SECONDS must be a whole number above zero, got '$interval'"
  if [ ! -f "$status_file" ]; then
    echo "no good dump yet: there is no $status_file"
    exit 1
  fi
  good=$(sed -n 's/^last_good=//p' "$status_file")
  good_at=$(sed -n 's/^last_good_at=//p' "$status_file")
  outcome=$(sed -n 's/^last_outcome=//p' "$status_file")
  if [ -z "$good" ] || ! is_positive_integer "$good_at"; then
    echo "no good dump yet (the last run: $outcome)"
    exit 1
  fi
  if [ ! -f "$dir/$good" ]; then
    echo "the last good dump, $good, is no longer in $dir"
    exit 1
  fi
  checked_at=$(date +%s)
  [ "$good_at" -le "$checked_at" ] || good_at=$checked_at
  age_s=$((checked_at - good_at))
  if [ "$age_s" -gt $((2 * interval)) ]; then
    echo "the last good dump, $good, is ${age_s}s old, more than twice the interval of ${interval}s (the last run: $outcome)"
    exit 1
  fi
  echo "the last good dump, $good, is ${age_s}s old (the last run: $outcome)"
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
  encrypted=''
  case $file in
    *.age)
      encrypted=yes
      command -v age >/dev/null || fail "$(basename "$file") is encrypted, and age is not installed (use the image in backup/)"
      [ -n "$decrypt_key" ] || fail "$(basename "$file") is encrypted: name the file with the private key in BACKUP_DECRYPT_KEY"
      [ -r "$decrypt_key" ] || fail "cannot read the key in BACKUP_DECRYPT_KEY ($decrypt_key)"
      # The whole dump once, into nothing: a wrong key or a damaged file stops here, before anything changed.
      age -d -i "$decrypt_key" "$file" >/dev/null || fail "could not decrypt $(basename "$file") with the key in BACKUP_DECRYPT_KEY, nothing was restored"
      ;;
  esac

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
      if ! names=$(unsealed "$archive" tar -tf -); then
        fail "could not read $(basename "$archive"), nothing was restored"
      fi
      if echo "$names" | grep -q -E '^/|(^|/)\.\.(/|$)'; then
        fail "$(basename "$archive") names a path outside the attachments folder, nothing was restored"
      fi
      if ! unsealed "$archive" tar -C "$staged" -xf -; then
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
  if ! unsealed "$file" pg_restore --exit-on-error --no-owner --dbname="$incoming"; then
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

# Runs "$2 ..." with the file $1 on its stdin, decrypted on the way when it
# is an .age file, so that no plain copy is written anywhere. Fails when the
# decryption or the command fails.
unsealed() {
  source_file=$1
  shift
  case $source_file in
    *.age)
      decrypted_status=$(mktemp)
      { age -d -i "$decrypt_key" "$source_file"; echo $? >"$decrypted_status"; } | "$@"
      used=$?
      decrypted=$(cat "$decrypted_status" 2>/dev/null || echo 1)
      rm -f "$decrypted_status"
      [ "$used" = 0 ] && [ "$decrypted" = 0 ]
      ;;
    *) "$@" <"$source_file" ;;
  esac
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
    if ! dump; then
      write_status dump_failed
      fail "dump failed"
    fi
    ;;
  copy)
    shift
    copy_files "$@"
    ;;
  health)
    health
    ;;
  restore)
    shift
    restore "$@"
    ;;
  *)
    echo "usage: backup.sh [run | once | restore <file> | health]" >&2
    exit 1
    ;;
esac
