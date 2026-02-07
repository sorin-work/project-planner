import { HealthStatus, Priority, ProjectStatus, Role, TaskStatus, TimesheetStatus } from '../core/models';

/** 450 -> "7h 30m", 60 -> "1h", 25 -> "25m". */
export function formatMinutes(minutes: number): string {
  if (!minutes) {
    return '0h';
  }
  const sign = minutes < 0 ? '-' : '';
  const abs = Math.abs(minutes);
  const h = Math.floor(abs / 60);
  const m = abs % 60;
  if (h === 0) {
    return `${sign}${m}m`;
  }
  return m === 0 ? `${sign}${h}h` : `${sign}${h}h ${m}m`;
}

/** Decimal hours with at most one decimal: 7.5 -> "7.5h". */
export function formatHours(hours: number | null | undefined): string {
  if (hours === null || hours === undefined) {
    return '–';
  }
  const rounded = Math.round(hours * 10) / 10;
  return `${Number.isInteger(rounded) ? rounded.toFixed(0) : rounded.toFixed(1)}h`;
}

/** "1h 30m", "90m", "1.5" or "1:30" into minutes; null when it can't be read. */
export function parseDuration(input: string): number | null {
  const text = input.trim().toLowerCase();
  if (!text) {
    return null;
  }
  const colon = /^(\d+):([0-5]\d)$/.exec(text);
  if (colon) {
    return Number(colon[1]) * 60 + Number(colon[2]);
  }
  const units = /^(?:(\d+(?:\.\d+)?)\s*h)?\s*(?:(\d+)\s*m(?:in)?)?$/.exec(text);
  if (units && (units[1] || units[2])) {
    const hours = units[1] ? Number(units[1]) : 0;
    const minutes = units[2] ? Number(units[2]) : 0;
    return Math.round(hours * 60 + minutes);
  }
  const decimal = /^\d+(?:\.\d+)?$/.exec(text);
  if (decimal) {
    return Math.round(Number(text) * 60);
  }
  return null;
}

export function formatMoney(amount: number | null | undefined, currency: string): string {
  if (amount === null || amount === undefined) {
    return '–';
  }
  return new Intl.NumberFormat('en-GB', { style: 'currency', currency, maximumFractionDigits: 0 }).format(amount);
}

export function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '–';
  }
  return `${Math.round(value)}%`;
}

export const TASK_STATUS_LABELS: Record<TaskStatus, string> = {
  TODO: 'To do',
  IN_PROGRESS: 'In progress',
  IN_REVIEW: 'In review',
  DONE: 'Done',
};

export const TASK_STATUSES: TaskStatus[] = ['TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE'];

export const PRIORITY_LABELS: Record<Priority, string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  URGENT: 'Urgent',
};

export const PRIORITIES: Priority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];

export const PROJECT_STATUS_LABELS: Record<ProjectStatus, string> = {
  PLANNED: 'Planned',
  ACTIVE: 'Active',
  ON_HOLD: 'On hold',
  DONE: 'Done',
};

export const PROJECT_STATUSES: ProjectStatus[] = ['PLANNED', 'ACTIVE', 'ON_HOLD', 'DONE'];

export const HEALTH_LABELS: Record<HealthStatus, string> = {
  ON_TRACK: 'On track',
  AT_RISK: 'At risk',
  OFF_TRACK: 'Off track',
  NOT_STARTED: 'Not started',
  DONE: 'Done',
};

export const ROLE_LABELS: Record<Role, string> = {
  OWNER: 'Owner',
  MANAGER: 'Manager',
  MEMBER: 'Member',
  CLIENT: 'Client viewer',
};

export const TIMESHEET_LABELS: Record<TimesheetStatus, string> = {
  DRAFT: 'Draft',
  SUBMITTED: 'Submitted',
  APPROVED: 'Approved',
  REJECTED: 'Sent back',
};

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return '?';
  }
  const first = parts[0][0];
  const last = parts.length > 1 ? parts[parts.length - 1][0] : '';
  return (first + last).toUpperCase();
}
