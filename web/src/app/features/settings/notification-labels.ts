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
};

export const DELIVERY_LABELS: Record<Delivery, string> = {
  EMAIL: 'By mail and in the app',
  IN_APP: 'In the app',
  OFF: 'Not at all',
};
