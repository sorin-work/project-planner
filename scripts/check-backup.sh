#!/bin/sh
# Proves that the dumps backup.sh makes can be restored. Runs in the postgres
# image, against a database that holds the demo data and that nothing else is
# writing to. check-restore.sh starts a stack for it and runs it there.
#
#   1. check-backup-loop.sh: the daily loop, the pruning and a restore that
#      fails half way, with stand-ins for the database tools.
#   2. Three real dumps with BACKUP_KEEP=2 leave the two newest, and leave
#      files that are not dumps alone.
#   3. The newest dump restores into a scratch database.
#   4. Every table has the same number of rows in both, and the tables that
#      hold the work (users, tasks, time_entries, timesheets) are not empty.
#   5. The attachments: every dump has its archive and loses it when it is
#      pruned, and the restore puts back exactly the files in the archive,
#      in another folder that held different ones.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
scratch="${PGDATABASE}_restore_check"
work=$(mktemp -d)

cleanup() {
  rm -rf "$work"
  dropdb --if-exists "$scratch" >/dev/null 2>&1 || true
  dropdb --if-exists "${scratch}_restoring" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "FAILED: $*" >&2
  exit 1
}

# One "schema.table rows" line per table.
row_counts() {
  psql -AtXq -F ' ' -v ON_ERROR_STOP=1 -d "$1" <<'SQL'
select format('select %L, count(*) from %I.%I', schemaname || '.' || tablename, schemaname, tablename)
from pg_tables
where schemaname not in ('pg_catalog', 'information_schema')
order by schemaname, tablename
\gexec
SQL
}

echo "== loop, pruning and restore with stand-ins"
sh "$here/check-backup-loop.sh"

export BACKUP_DIR="$work/dumps" BACKUP_KEEP=2
mkdir "$BACKUP_DIR"
echo "not a dump" >"$BACKUP_DIR/keep-me.txt"
# Files in the layout of the api's store: a folder per first two characters of the key.
export ATTACHMENTS_DIR="$work/attachments"
mkdir -p "$ATTACHMENTS_DIR/0a" "$ATTACHMENTS_DIR/ff"
echo "the brief" >"$ATTACHMENTS_DIR/0a/0a000000000000000000000000000001"
head -c 100000 /dev/urandom >"$ATTACHMENTS_DIR/ff/ff000000000000000000000000000002"
(cd "$ATTACHMENTS_DIR" && find . -type f -exec md5sum {} \; | sort) >"$work/files-before.txt"

echo "== retention"
sh "$here/backup.sh" once
first=$(ls "$BACKUP_DIR" | grep '\.dump$')
# Dump names have a resolution of one second.
sleep 1
sh "$here/backup.sh" once
sleep 1
sh "$here/backup.sh" once

left=$(ls "$BACKUP_DIR" | grep '\.dump$' | sort)
[ "$(echo "$left" | wc -l)" -eq 2 ] || fail "expected 2 dumps to be kept, found: $left"
if echo "$left" | grep -qx "$first"; then
  fail "the oldest dump ($first) was kept"
fi
[ -f "$BACKUP_DIR/keep-me.txt" ] || fail "a file that is not a dump was removed"
[ -z "$(ls -A "$BACKUP_DIR" | grep '\.partial$' || true)" ] || fail "a partial dump was left behind"
archives=$(ls "$BACKUP_DIR" | grep '\.files\.tar$' | sort)
[ "$archives" = "$(echo "$left" | sed 's/\.dump$/.files.tar/')" ] || fail "every dump kept should have its archive and no other, found: $archives"
newest=$(echo "$left" | tail -n 1)

echo "== restore $newest into $scratch, and its attachments into a folder that holds other files"
restored_files="$work/restored-attachments"
mkdir -p "$restored_files/12"
echo "a file the backup does not know" >"$restored_files/12/12000000000000000000000000000003"
PGDATABASE="$scratch" ATTACHMENTS_DIR="$restored_files" sh "$here/backup.sh" restore "$newest"
(cd "$restored_files" && find . -type f -exec md5sum {} \; | sort) >"$work/files-after.txt"
diff "$work/files-before.txt" "$work/files-after.txt" || fail "the restored attachments (+) are not the ones that were archived (-)"
[ ! -e "$restored_files/.restoring" ] || fail "the unpacked copy was left behind"

echo "== row counts"
row_counts "$PGDATABASE" >"$work/source.txt"
row_counts "$scratch" >"$work/restored.txt"
cat "$work/source.txt"
diff "$work/source.txt" "$work/restored.txt" || fail "row counts differ between $PGDATABASE (-) and the restored copy (+)"
# Equal counts of nothing prove nothing. Flyway's own table always has rows,
# so it is the application's tables that have to have some.
for table in users tasks time_entries timesheets; do
  rows=$(awk -v name="public.$table" '$1 == name { print $2 }' "$work/source.txt")
  [ "${rows:-0}" -gt 0 ] || fail "$table has no rows in $PGDATABASE, so this proves nothing. The check needs the demo data."
done
total=$(awk '$1 !~ /\.flyway_schema_history$/ { rows += $2 } END { print rows + 0 }' "$work/source.txt")

echo "OK: $(wc -l <"$work/source.txt") tables came back from $newest, $total rows in the application tables, and $(wc -l <"$work/files-before.txt") attachments"
