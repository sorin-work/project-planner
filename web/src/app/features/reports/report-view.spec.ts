import { HttpParams } from '@angular/common/http';
import { rangeParams } from '../../core/report.api';
import { barWidth, capacityNote, csvName, formatAmount, formatShare } from './report-view';

describe('report view', () => {
  it('sends a preset by name and a custom range as two dates', () => {
    expect(rangeParams({ preset: 'last_month' }).toString()).toBe(new HttpParams().set('preset', 'last_month').toString());
    expect(rangeParams({ from: '2026-03-01', to: '2026-03-15' }).toString()).toBe('from=2026-03-01&to=2026-03-15');
  });

  it('draws a bar as long as the row is against the longest one', () => {
    const rows = [{ minutes: 240 }, { minutes: 60 }, { minutes: 0 }];
    expect(rows.map((r) => barWidth(r, rows))).toEqual([100, 25, 0]);
    expect(barWidth({ minutes: 0 }, [{ minutes: 0 }])).toBe(0);
    expect(barWidth({ minutes: 0 }, [])).toBe(0);
  });

  it('shows money to the cent, so that rows add up to their total', () => {
    expect(formatAmount(219.99, 'EUR')).toBe('€219.99');
    expect(formatAmount(50, 'EUR')).toBe('€50.00');
    expect(formatAmount(1234.5, 'USD')).toBe('US$1,234.50');
    expect(formatAmount(0, 'EUR')).toBe('€0.00');
    expect(formatAmount(null, 'EUR')).toBe('–');
  });

  it('shows a share with one decimal', () => {
    expect(formatShare(62.5)).toBe('62.5%');
    expect(formatShare(100)).toBe('100.0%');
    expect(formatShare(0)).toBe('0.0%');
    expect(formatShare(null)).toBe('–');
  });

  it('says what utilisation is measured against', () => {
    const whole = capacityNote({ from: '2026-03-02', to: '2026-03-08', countedTo: '2026-03-08', workingDays: 5 });
    expect(whole).toContain('for the 5 working days from 2 Mar');
    expect(whole).toContain('to 8 Mar');
    expect(whole).toContain('billable time as a share of that');
    expect(whole).toContain('holidays or time off');
    expect(whole).not.toContain('after today');
    // The total is about the team as it is now, which the Summary is not.
    expect(whole).toContain('People who have left the organisation are not listed');

    const running = capacityNote({ from: '2026-03-09', to: '2026-03-15', countedTo: '2026-03-09', workingDays: 1 });
    expect(running).toContain('for the 1 working day from');
    expect(running).toContain('Days after today are not counted, neither their capacity nor time logged ahead on them.');

    const future = capacityNote({ from: '2026-03-16', to: '2026-03-22', countedTo: null, workingDays: 0 });
    expect(future).toContain('has not begun');
    expect(future).toContain('People who have left');
  });

  it('names the download like the API does', () => {
    expect(csvName('PROJECT', '2026-03-01', '2026-03-31')).toBe('time-by-project-2026-03-01-to-2026-03-31.csv');
    expect(csvName('utilisation', '2026-03-01', '2026-03-31')).toBe('utilisation-2026-03-01-to-2026-03-31.csv');
  });
});
