import { Delivery, NotificationType } from '../../core/models';

/** The order and the names of the types on the settings page. */
export const NOTIFICATION_LABELS: Record<NotificationType, string> = {
  MENTIONED: 'Somebody mentions you',
  ASSIGNED: 'A task is assigned to you',
  COMMENTED: 'A comment on a task assigned to you',
  TIMESHEET_SUBMITTED: 'A timesheet waits for your approval',
  TIMESHEET_APPROVED: 'Your timesheet is approved',
  TIMESHEET_REJECTED: 'Your timesheet is sent back',
  TIMESHEET_REMINDER: 'A reminder to submit your timesheet',
  BUDGET_ALERT: 'A project passes a budget threshold',
  DAILY_SUMMARY: 'A summary on working mornings: tasks due and timesheets to review',
};

/** What the stop link of a mail is about, as in "you no longer get mails about ...". */
export const NOTIFICATION_ABOUT: Record<NotificationType, string> = {
  MENTIONED: 'mentions',
  ASSIGNED: 'tasks assigned to you',
  COMMENTED: 'comments on your tasks',
  TIMESHEET_SUBMITTED: 'timesheets waiting for approval',
  TIMESHEET_APPROVED: 'approved timesheets',
  TIMESHEET_REJECTED: 'timesheets sent back',
  TIMESHEET_REMINDER: 'timesheet reminders',
  BUDGET_ALERT: 'budget alerts',
  DAILY_SUMMARY: 'the morning summary',
};

/** Types that are only ever mailed: on or off, nothing in the app. */
export function mailOnly(type: NotificationType): boolean {
  return type === 'DAILY_SUMMARY';
}

/** The choices the settings card offers for a type. */
export function deliveriesFor(type: NotificationType): Delivery[] {
  return mailOnly(type) ? ['EMAIL', 'OFF'] : ['EMAIL', 'IN_APP', 'OFF'];
}

/** The name of a choice for a type. */
export function deliveryLabel(type: NotificationType, delivery: Delivery): string {
  if (mailOnly(type)) {
    return delivery === 'EMAIL' ? 'By mail' : 'Not at all';
  }
  return DELIVERY_LABELS[delivery];
}

/** What the stop page says once the mails of a type stopped. */
export function stoppedText(type: NotificationType): string {
  return mailOnly(type)
    ? `You no longer get ${NOTIFICATION_ABOUT[type]}.`
    : `You no longer get mails about ${NOTIFICATION_ABOUT[type]}. They still show in the app.`;
}

export const DELIVERY_LABELS: Record<Delivery, string> = {
  EMAIL: 'By mail and in the app',
  IN_APP: 'In the app',
  OFF: 'Not at all',
};
