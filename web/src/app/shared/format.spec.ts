import { formatClock, formatHours, formatMinutes, formatMoney, formatPercent, initials, isDecimalHours, parseDuration } from './format';

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

  it('only takes decimal hours that are whole minutes', () => {
    expect(parseDuration('0.1')).toBe(6);
    expect(parseDuration('0,25')).toBe(15);
    expect(parseDuration('1.30')).toBe(78);
    expect(parseDuration('7.75h')).toBe(465);
    expect(parseDuration('2.50')).toBe(150);
    expect(parseDuration('0.05')).toBe(3);
    expect(parseDuration('1.5h 15m')).toBe(105);
    // 93.3 minutes, 1440.48 minutes, a third of an hour written out, and a sixtieth of a minute.
    for (const text of ['1.555', '24.008', '0.333', '0.001', '1,01', '1.555h', '0.1234']) {
      expect(parseDuration(text)).toBeNull();
      expect(isDecimalHours(text)).toBe(true);
    }
    expect(isDecimalHours('1:30')).toBe(false);
    expect(isDecimalHours('90m')).toBe(false);
  });

  it('writes minutes as hours and minutes on a clock, and reads every one of them back', () => {
    expect(formatClock(0)).toBe('0:00');
    expect(formatClock(5)).toBe('0:05');
    expect(formatClock(78)).toBe('1:18');
    expect(formatClock(480)).toBe('8:00');
    expect(formatClock(1440)).toBe('24:00');
    for (let minutes = 0; minutes <= 1440; minutes++) {
      expect(parseDuration(formatClock(minutes))).toBe(minutes);
      if (minutes > 0) {
        expect(parseDuration(formatMinutes(minutes))).toBe(minutes);
      }
    }
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
