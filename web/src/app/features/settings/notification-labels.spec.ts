import { deliveriesFor, deliveryLabel, mailOnly, stoppedText } from './notification-labels';

describe('notification labels', () => {
  it('offers only on and off for the morning summary, which is never in the app', () => {
    expect(mailOnly('DAILY_SUMMARY')).toBe(true);
    expect(mailOnly('MENTIONED')).toBe(false);
    expect(deliveriesFor('DAILY_SUMMARY')).toEqual(['EMAIL', 'OFF']);
    expect(deliveriesFor('ASSIGNED')).toEqual(['EMAIL', 'IN_APP', 'OFF']);
    expect(deliveryLabel('DAILY_SUMMARY', 'EMAIL')).toBe('By mail');
    expect(deliveryLabel('ASSIGNED', 'IN_APP')).toBe('In the app');
  });

  it('does not promise the app for a kind that is only mailed', () => {
    expect(stoppedText('DAILY_SUMMARY')).toBe('You no longer get the morning summary.');
    expect(stoppedText('MENTIONED')).toBe('You no longer get mails about mentions. They still show in the app.');
  });
});
