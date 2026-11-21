# The image of the backup service: the postgres image, whose pg_dump matches
# the server, with age on top of it to encrypt the dumps, and the tools of
# BACKUP_PACKAGES. rclone, for copies to a self-hosted bucket, is more than
# 100 MB, so it is only there when asked for: BACKUP_PACKAGES=rclone.
# backup.sh itself is mounted from scripts/, not copied in.
FROM postgres:17-alpine
ARG BACKUP_PACKAGES=""
RUN apk add --no-cache age $BACKUP_PACKAGES
