import { ago, backupLine } from './backup-line';

const NOW = new Date('2026-03-10T12:00:00Z');

describe('backupLine', () => {
  it('says nothing when the server does not watch its backups', () => {
    expect(backupLine(null, NOW)).toBeNull();
    expect(backupLine({ watched: false }, NOW)).toBeNull();
  });

  it('says how old the newest good backup is, and warns when they stopped or were not copied', () => {
    expect(backupLine({ watched: true, state: 'OK', lastGoodAt: '2026-03-10T09:00:00Z' }, NOW)).toEqual({
      text: 'Backups are working. The newest good backup is from 3 hours ago.', bad: false,
    });
    expect(backupLine({ watched: true, state: 'LATE', lastGoodAt: '2026-03-07T12:00:00Z' }, NOW)?.text)
      .toBe('Backups have stopped. The newest good backup is from 3 days ago. See the logs of the backup service.');
    expect(backupLine({ watched: true, state: 'COPY_FAILED', lastGoodAt: '2026-03-10T11:59:00Z' }, NOW)?.bad).toBe(true);
    expect(backupLine({ watched: true, state: 'NO_GOOD_DUMP' }, NOW)?.bad).toBe(true);
    expect(backupLine({ watched: true, state: 'FUTURE_DATED', lastGoodAt: '2026-04-01T00:00:00Z' }, NOW)?.text).toContain('dated in the future');
    expect(backupLine({ watched: true }, NOW)?.text).toContain('no backup status');
  });

  it('counts minutes, hours and days', () => {
    expect(ago('2026-03-10T11:59:30Z', NOW)).toBe('a minute ago');
    expect(ago('2026-03-10T11:00:00Z', NOW)).toBe('an hour ago');
    expect(ago('2026-03-08T12:00:00Z', NOW)).toBe('2 days ago');
    // A time ahead of the clock is now.
    expect(ago('2026-03-10T13:00:00Z', NOW)).toBe('a minute ago');
  });
});
