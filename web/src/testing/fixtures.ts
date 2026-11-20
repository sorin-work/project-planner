import { HttpRequest } from '@angular/common/http';
import {
  ActivityEntry, Attachment, Baseline, BaselineComparison, Client, Comment, CriticalPath, Dashboard, Member, Notification, Phase,
  Project, ProjectHealth, ProjectListItem, ScheduleIssue, Task, TimeEntry, Week, Workload,
} from '../app/core/models';
import { addDays, today, weekStart } from '../app/shared/dates';
import { FakeRoute } from './fake-api';

/**
 * Answers for rendering every page of the app: one organisation, two people, one
 * project with a small plan that has a phase, a subtask, a link, a milestone, a
 * schedule issue, a baseline and a file. Dates are counted from today, so that
 * every page has something current to show.
 */
const now = new Date().toISOString();
const monday = weekStart(today());
const day = (offset: number) => addDays(monday, offset);

export const PROJECT: Project = {
  id: 7, organisationId: 3, clientId: 5, clientName: 'Acme', name: 'Website', code: 'WEB', status: 'ACTIVE',
  startDate: day(-7), endDate: day(28), budgetHours: 200, budgetAmount: 20000, color: '#4f6bed', createdAt: now, updatedAt: now,
};

function task(id: number, title: string, start: number, due: number, extra: Partial<Task> = {}): Task {
  return {
    id, projectId: 7, title, status: 'TODO', priority: 'MEDIUM', milestone: false, position: id, createdAt: now, updatedAt: now,
    predecessors: [], subtaskCount: 0, loggedHours: 0, startDate: day(start), dueDate: day(due), estimateHours: 8, ...extra,
  };
}

export const TASKS: Task[] = [
  task(1, 'Design', 0, 4, { phaseId: 11, status: 'IN_PROGRESS', assigneeId: 1, assigneeName: 'Ann Lee' }),
  task(2, 'Build', 3, 9, {
    phaseId: 11, assigneeId: 2, assigneeName: 'Bo Kim', subtaskCount: 1,
    predecessors: [{ id: 50, predecessorId: 1, successorId: 2, lagDays: 0, type: 'FS' }],
  }),
  task(3, 'Endpoints', 3, 4, { phaseId: 11, parentId: 2 }),
  task(4, 'Launch', 14, 14, { milestone: true, predecessors: [{ id: 51, predecessorId: 2, successorId: 4, lagDays: 2, type: 'SS' }] }),
];

const PHASES: Phase[] = [{ id: 11, projectId: 7, name: 'Build phase', position: 0, startDate: day(0), endDate: day(9) } as Phase];

const MEMBERS: Member[] = [
  { id: 21, userId: 1, name: 'Ann Lee', email: 'ann@example.com', handle: 'ann', role: 'OWNER', weeklyCapacityHours: 40, joinedAt: now },
  { id: 22, userId: 2, name: 'Bo Kim', email: 'bo@example.com', handle: 'bo', role: 'MEMBER', weeklyCapacityHours: 32, joinedAt: now },
];

const CLIENTS: Client[] = [{ id: 5, name: 'Acme', contactName: 'Ruth', archived: false, projectCount: 1 }];

const LIST_ITEM: ProjectListItem = {
  project: PROJECT,
  health: { status: 'AT_RISK', progressPct: 40, hoursBurnPct: 55, amountBurnPct: 50, overdueTasks: 1, openTasks: 3, scheduleVarianceHours: -4 },
};

const HEALTH: ProjectHealth = {
  projectId: 7, status: 'AT_RISK', asOf: today(),
  progress: { totalEstimateHours: 32, doneEstimateHours: 8, progressPct: 25, plannedPct: 40, earnedHours: 8, plannedHours: 12,
    scheduleVarianceHours: -4, spi: 0.67, overdueTasks: 1, openTasks: 3, doneTasks: 1 },
  budget: { budgetHours: 200, loggedHours: 60, billableHours: 50, nonBillableHours: 10, remainingHours: 140, hoursBurnPct: 30,
    budgetAmount: 20000, spentAmount: 6000, remainingAmount: 14000, amountBurnPct: 30 },
  overdue: [{ id: 1, title: 'Design', dueDate: day(-1), assigneeId: 1, daysOverdue: 1 }],
  plannedEnd: day(28), forecastEnd: day(30), endVarianceDays: 2,
};

const CRITICAL: CriticalPath = {
  start: day(0), finish: day(14), durationDays: 11, criticalTaskIds: [1, 2, 4],
  tasks: TASKS.map((t) => ({ taskId: t.id, earlyStart: t.startDate!, earlyFinish: t.dueDate!, lateStart: t.startDate!,
    lateFinish: t.dueDate!, floatDays: 0, critical: true })),
};

const ISSUES: ScheduleIssue[] = [
  { type: 'STARTS_TOO_EARLY', taskId: 2, relatedTaskId: 1, days: 2, suggestedStart: day(7), suggestedDue: day(11), linkType: 'FS' },
];

const BASELINE: Baseline = { id: 31, projectId: 7, name: 'Signed off', reference: true, createdAt: now, createdByName: 'Ann Lee', tasks: 4 };

const COMPARISON: BaselineComparison = {
  baseline: BASELINE,
  tasks: TASKS.map((t) => ({
    taskId: t.id, title: t.title, milestone: t.milestone, change: t.id === 2 ? 'SLIPPED' : 'UNCHANGED', existedThen: true, existsNow: true,
    baselineStart: t.startDate, baselineDue: t.id === 2 ? day(8) : t.dueDate, currentStart: t.startDate, currentDue: t.dueDate,
    startSlipDays: 0, finishSlipDays: t.id === 2 ? 1 : 0,
  })),
  phases: [],
  projectEnd: { baseline: day(28), current: day(28), slipDays: 0, change: 'UNCHANGED' },
  planEnd: { baseline: day(13), current: day(14), slipDays: 1, change: 'SLIPPED' },
  counts: { unchanged: 3, slipped: 1, pulledIn: 0, added: 0, removed: 0 },
  baselineEstimateHours: 32,
  currentEstimateHours: 32,
};

const ACTIVITY: ActivityEntry[] = [
  { id: 1, projectId: 7, taskId: 1, actorId: 1, actorName: 'Ann Lee', action: 'task.created', summary: 'Created task Design', createdAt: now },
];

const COMMENTS: Comment[] = [
  { id: 1, taskId: 2, authorId: 2, authorName: 'Bo Kim', authorHandle: 'bo', body: 'Started on the API, @ann', createdAt: now },
];

const FILES: Attachment[] = [
  { id: 41, taskId: 2, fileName: 'brief.pdf', contentType: 'application/pdf', sizeBytes: 14336, uploaderId: 1, uploaderName: 'Ann Lee',
    createdAt: now, previewable: false, canDelete: true },
];

const ENTRY: TimeEntry = {
  id: 61, userId: 1, userName: 'Ann Lee', projectId: 7, projectName: 'Website', projectCode: 'WEB', taskId: 1, taskTitle: 'Design',
  date: day(0), minutes: 90, billable: true, running: false, locked: false,
};

const WEEK: Week = {
  userId: 1, userName: 'Ann Lee', weekStart: monday, status: 'DRAFT', entries: [ENTRY],
  days: [0, 1, 2, 3, 4, 5, 6].map((d) => ({ date: day(d), minutes: d === 0 ? 90 : 0, holiday: d === 2 ? 'Founders day' : undefined })),
  totalMinutes: 90, billableMinutes: 90,
};

const WORKLOAD: Workload = {
  from: monday, weeks: 8,
  members: MEMBERS.map((m, i) => ({
    userId: m.userId, name: m.name, capacityHours: m.weeklyCapacityHours, unscheduledHours: 2,
    weeks: [0, 1, 2, 3, 4, 5, 6, 7].map((w) => ({ weekStart: day(w * 7), allocatedHours: (w + i) % 3 === 0 ? 45 : 20,
      capacityHours: m.weeklyCapacityHours, overallocated: (w + i) % 3 === 0, taskIds: [1], holidays: 0, daysOff: 0 })),
  })),
  tasks: [{ id: 1, title: 'Design', projectId: 7, projectCode: 'WEB', projectColor: '#4f6bed', startDate: day(0), dueDate: day(4), estimateHours: 8 }],
};

const DASHBOARD: Dashboard = {
  today: today(), overdue: [TASKS[0]], dueSoon: [TASKS[1]], inProgress: [TASKS[0]], activeProjects: [LIST_ITEM],
  minutesThisWeek: 90, billableMinutesThisWeek: 90, pendingApprovals: 1, missingTimesheets: 1, lastWeekTimesheet: 'SUBMITTED',
};

const NOTIFICATIONS: Notification[] = [
  { id: 1, organisationId: 3, type: 'MENTION', message: 'Bo Kim mentioned you on Build', actorName: 'Bo Kim', projectId: 7, taskId: 2,
    read: false, createdAt: now },
];

const ME = {
  user: { id: 1, email: 'ann@example.com', name: 'Ann Lee', handle: 'ann' },
  organisations: [{ organisationId: 3, organisationName: 'Brightline', role: 'OWNER', currency: 'EUR' }],
};

const taskFrom = (req: HttpRequest<unknown>) => TASKS.find((t) => req.url.endsWith(`/tasks/${t.id}`)) ?? TASKS[0];

export const FIXTURES: FakeRoute[] = [
  { path: /^\/me$/, body: ME },
  { path: /^\/notifications\/unread-count$/, body: { count: 3 } },
  { path: /^\/notifications$/, body: NOTIFICATIONS },
  { path: /^\/orgs\/3$/, body: { id: 3, name: 'Brightline', currency: 'EUR', timezone: 'Europe/London', defaultHourlyRate: 90, role: 'OWNER' } },
  { path: /^\/orgs\/3\/timer$/, body: null },
  { path: /^\/orgs\/3\/dashboard$/, body: DASHBOARD },
  { path: /^\/orgs\/3\/activity$/, body: ACTIVITY },
  { path: /^\/orgs\/3\/clients$/, body: CLIENTS },
  { path: /^\/orgs\/3\/members$/, body: MEMBERS },
  { path: /^\/orgs\/3\/invitations$/, body: [{ id: 1, email: 'new@example.com', role: 'MEMBER', createdAt: now }] },
  { path: /^\/orgs\/3\/role-rates$/, body: [{ role: 'MEMBER', hourlyRate: 80 }] },
  { path: /^\/orgs\/3\/holidays$/, body: [{ id: 1, date: day(2), name: 'Founders day' }] },
  { path: /^\/orgs\/3\/time-off$/, body: [{ id: 1, userId: 2, firstDay: day(7), lastDay: day(8), note: 'Away' }] },
  { path: /^\/orgs\/3\/projects$/, body: [LIST_ITEM] },
  { path: /^\/orgs\/3\/projects\/options$/, body: [{ id: 7, code: 'WEB', name: 'Website', status: 'ACTIVE', clientId: 5, clientName: 'Acme' }] },
  { path: /^\/orgs\/3\/timesheets\/week$/, body: WEEK },
  { path: /^\/orgs\/3\/timesheets$/, body: [{ id: 1, userId: 2, userName: 'Bo Kim', weekStart: day(-7), status: 'SUBMITTED', submittedAt: now,
    totalMinutes: 2400, billableMinutes: 2000, billableAmount: 2666.67 }] },
  { path: /^\/orgs\/3\/timesheets\/missing$/, body: { weekStart: day(-7), latestWeekStart: day(-7),
    people: [{ userId: 2, userName: 'Bo Kim', status: 'DRAFT', totalMinutes: 600, weeklyCapacityHours: 32, remindedToday: false }] } },
  { path: /^\/orgs\/3\/workload$/, body: WORKLOAD },
  { path: /^\/orgs\/3\/reports\/time$/, body: { from: day(-7), to: day(-1), groupBy: 'PROJECT',
    rows: [{ key: '7', label: 'WEB Website', minutes: 600, billableMinutes: 540, billableAmount: 900 }],
    total: { key: 'total', label: 'Total', minutes: 600, billableMinutes: 540, billableAmount: 900 } } },
  { path: /^\/orgs\/3\/reports\/utilisation$/, body: { from: day(-7), to: day(-1), workingDays: 5,
    rows: [{ userId: 1, name: 'Ann Lee', minutes: 2400, billableMinutes: 2000, capacityMinutes: 2400, utilisationPct: 83 }],
    total: { name: 'Total', minutes: 2400, billableMinutes: 2000, capacityMinutes: 2400, utilisationPct: 83 } } },
  { path: /^\/projects\/7$/, body: PROJECT },
  { path: /^\/projects\/7\/tasks$/, body: TASKS },
  { path: /^\/projects\/7\/phases$/, body: PHASES },
  { path: /^\/projects\/7\/critical-path$/, body: CRITICAL },
  { path: /^\/projects\/7\/schedule-issues$/, body: ISSUES },
  { path: /^\/projects\/7\/baselines$/, body: [BASELINE] },
  { path: /^\/baselines\/31\/comparison$/, body: COMPARISON },
  { path: /^\/projects\/7\/health$/, body: HEALTH },
  { path: /^\/projects\/7\/activity$/, body: ACTIVITY },
  { path: /^\/projects\/7\/duplicate$/, body: { name: 'Website (copy)', code: 'WEB-2', startDate: day(7), phases: 1, tasks: 4,
    dependencies: 2, attachments: 1, attachmentBytes: 14336 } },
  { path: /^\/tasks\/\d+$/, body: taskFrom },
  { path: /^\/tasks\/\d+\/comments$/, body: COMMENTS },
  { path: /^\/tasks\/\d+\/attachments$/, body: FILES },
];
