import { formatHours, formatMinutes, formatMoney, formatPercent, initials, parseDuration } from './format';

describe('format', () => {
  it('formats minutes as hours and minutes', () => {
    expect(formatMinutes(0)).toBe('0h');
    expect(formatMinutes(25)).toBe('25m');
    expect(formatMinutes(60)).toBe('1h');
    expect(formatMinutes(450)).toBe('7h 30m');
    expect(formatMinutes(-90)).toBe('-1h 30m');
  });

  it('formats decimal hours', () => {
    expect(formatHours(7.5)).toBe('7.5h');
    expect(formatHours(8)).toBe('8h');
    expect(formatHours(0.04)).toBe('0h');
    expect(formatHours(undefined)).toBe('–');
  });

  it('parses the ways people type durations', () => {
    expect(parseDuration('1h 30m')).toBe(90);
    expect(parseDuration('2h')).toBe(120);
    expect(parseDuration('45m')).toBe(45);
    expect(parseDuration('45 min')).toBe(45);
    expect(parseDuration('1.5')).toBe(90);
    expect(parseDuration('1,25')).toBe(75);
    expect(parseDuration('1.5h')).toBe(90);
    expect(parseDuration('1:15')).toBe(75);
    expect(parseDuration('')).toBeNull();
    expect(parseDuration('soon')).toBeNull();
    expect(parseDuration('1:75')).toBeNull();
  });

  it('formats money and percentages', () => {
    expect(formatMoney(1234.5, 'EUR')).toBe('€1,235');
    expect(formatMoney(null, 'EUR')).toBe('–');
    expect(formatPercent(42.6)).toBe('43%');
    expect(formatPercent(undefined)).toBe('–');
  });

  it('makes initials', () => {
    expect(initials('Maya Collins')).toBe('MC');
    expect(initials('Cher')).toBe('C');
    expect(initials('  ana maria pop ')).toBe('AP');
    expect(initials('')).toBe('?');
  });
});
