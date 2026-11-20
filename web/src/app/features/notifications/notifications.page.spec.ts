import { notificationTarget } from './notifications.page';

describe('notificationTarget', () => {
  it('opens the task a mention, assignment or comment is about', () => {
    expect(notificationTarget({ type: 'MENTIONED', projectId: 3, taskId: 14 })).toEqual({
      commands: ['/projects', 3, 'list'],
      queryParams: { task: 14 },
    });
  });

  it('opens the overview of the project a budget alert is about', () => {
    expect(notificationTarget({ type: 'BUDGET_ALERT', projectId: 3 })).toEqual({ commands: ['/projects', 3, 'overview'] });
  });

  it('sends managers to the approval queue for a submitted week', () => {
    expect(notificationTarget({ type: 'TIMESHEET_SUBMITTED', weekStart: '2026-03-02' })).toEqual({ commands: ['/approvals'] });
  });

  it('opens the timesheet on the week a reminder or a review is about', () => {
    for (const type of ['TIMESHEET_REMINDER', 'TIMESHEET_REJECTED', 'TIMESHEET_APPROVED']) {
      expect(notificationTarget({ type, weekStart: '2026-03-02' })).toEqual({
        commands: ['/time'],
        queryParams: { week: '2026-03-02' },
      });
    }
  });

  it('falls back to the current week for timesheet notifications without one', () => {
    expect(notificationTarget({ type: 'TIMESHEET_REJECTED' })).toEqual({ commands: ['/time'] });
  });

  it('goes nowhere for a type it does not know', () => {
    expect(notificationTarget({ type: 'SOMETHING_NEW' })).toBeNull();
  });
});
