import { BackupStatus } from '../../core/models';

export interface BackupLine {
  text: string;
  /** Whether it needs a look: shown as a warning. */
  bad: boolean;
}

/** "3 hours ago", "2 days ago", from two instants. */
export function ago(then: string, now: Date): string {
  const minutes = Math.max(0, Math.round((now.getTime() - new Date(then).getTime()) / 60000));
  if (minutes < 60) {
    return minutes <= 1 ? 'a minute ago' : `${minutes} minutes ago`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 48) {
    return hours === 1 ? 'an hour ago' : `${hours} hours ago`;
  }
  return `${Math.round(hours / 24)} days ago`;
}

/** The line about the backups on the Settings page for owners, or null when the server does not watch them. */
export function backupLine(status: BackupStatus | null, now: Date): BackupLine | null {
  if (!status?.watched) {
    return null;
  }
  if (!status.state) {
    return { text: 'There is no backup status to read yet. Is the backup service running?', bad: true };
  }
  const last = status.lastGoodAt ? `The newest good backup is from ${ago(status.lastGoodAt, now)}.` : '';
  switch (status.state) {
    case 'OK':
      return { text: `Backups are working. ${last}`, bad: false };
    case 'COPY_FAILED':
      return { text: `The newest backup was made but could not be copied off the machine. ${last}`, bad: true };
    case 'LATE':
      return { text: `Backups have stopped. ${last} See the logs of the backup service.`, bad: true };
    case 'NO_GOOD_DUMP':
      return { text: 'No backup has worked yet. See the logs of the backup service.', bad: true };
  }
}
