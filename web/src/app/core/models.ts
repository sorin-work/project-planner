// Shapes of the JSON the API returns. Dates are ISO strings (yyyy-mm-dd), timestamps ISO instants.

export type Role = 'OWNER' | 'MANAGER' | 'MEMBER' | 'CLIENT';
export type ProjectStatus = 'PLANNED' | 'ACTIVE' | 'ON_HOLD' | 'DONE';
export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE';
export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';
export type HealthStatus = 'ON_TRACK' | 'AT_RISK' | 'OFF_TRACK' | 'NOT_STARTED' | 'DONE';
export type TimesheetStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';

export interface ApiError {
  status: number;
  code: string;
  message: string;
  errors?: { field: string; message: string }[];
}

export interface User {
  id: number;
  email: string;
  name: string;
  handle: string;
}

export interface AuthResponse {
  token: string;
  expiresAt: string;
  user: User;
}

export interface MembershipSummary {
  organisationId: number;
  organisationName: string;
  role: Role;
  currency: string;
}

export interface Me {
  user: User;
  organisations: MembershipSummary[];
}

export interface Organisation {
  id: number;
  name: string;
  currency: string;
  timezone: string;
  defaultHourlyRate?: number;
  role: Role;
}

export interface Member {
  id: number;
  userId: number;
  name: string;
  email: string;
  handle: string;
  role: Role;
  hourlyRate?: number;
  weeklyCapacityHours: number;
  clientId?: number;
  joinedAt: string;
}

export interface Invitation {
  id: number;
  email: string;
  role: Role;
  clientId?: number;
  createdAt: string;
}

export interface RoleRate {
  role: Role;
  hourlyRate: number;
}

export interface Client {
  id: number;
  name: string;
  contactName?: string;
  contactEmail?: string;
  notes?: string;
  archived: boolean;
  projectCount: number;
}

export interface Project {
  id: number;
  organisationId: number;
  clientId?: number;
  clientName?: string;
  name: string;
  code: string;
  description?: string;
  status: ProjectStatus;
  startDate?: string;
  endDate?: string;
  budgetHours?: number;
  budgetAmount?: number;
  hourlyRate?: number;
  color: string;
  createdAt: string;
  updatedAt: string;
}

export interface HealthSummary {
  status: HealthStatus;
  progressPct: number;
  hoursBurnPct?: number;
  amountBurnPct?: number;
  overdueTasks: number;
  openTasks: number;
  scheduleVarianceHours: number;
}

export interface ProjectListItem {
  project: Project;
  health: HealthSummary;
}

export interface Phase {
  id: number;
  projectId: number;
  name: string;
  position: number;
  startDate?: string;
  endDate?: string;
}

export interface Dependency {
  id: number;
  predecessorId: number;
  successorId: number;
  lagDays: number;
}

export interface Task {
  id: number;
  projectId: number;
  phaseId?: number;
  parentId?: number;
  title: string;
  description?: string;
  status: TaskStatus;
  priority: Priority;
  estimateHours?: number;
  startDate?: string;
  dueDate?: string;
  assigneeId?: number;
  assigneeName?: string;
  milestone: boolean;
  position: number;
  completedAt?: string;
  createdAt: string;
  updatedAt: string;
  predecessors: Dependency[];
  subtaskCount: number;
  loggedHours: number;
}

export interface TaskChange {
  task: Task;
  rescheduled: Task[];
}

export interface TaskTiming {
  taskId: number;
  earlyStart: string;
  earlyFinish: string;
  lateStart: string;
  lateFinish: string;
  floatDays: number;
  critical: boolean;
}

export interface CriticalPath {
  start?: string;
  finish?: string;
  durationDays: number;
  criticalTaskIds: number[];
  tasks: TaskTiming[];
}

export interface Progress {
  totalEstimateHours: number;
  doneEstimateHours: number;
  progressPct: number;
  plannedPct: number;
  earnedHours: number;
  plannedHours: number;
  scheduleVarianceHours: number;
  spi?: number;
  overdueTasks: number;
  openTasks: number;
  doneTasks: number;
}

export interface BudgetView {
  budgetHours?: number;
  loggedHours: number;
  billableHours: number;
  nonBillableHours: number;
  remainingHours?: number;
  hoursBurnPct?: number;
  budgetAmount?: number;
  spentAmount?: number;
  remainingAmount?: number;
  amountBurnPct?: number;
}

export interface OverdueTask {
  id: number;
  title: string;
  dueDate: string;
  assigneeId?: number;
  daysOverdue: number;
}

export interface ProjectHealth {
  projectId: number;
  status: HealthStatus;
  asOf: string;
  progress: Progress;
  budget: BudgetView;
  overdue: OverdueTask[];
  plannedEnd?: string;
  forecastEnd?: string;
  endVarianceDays?: number;
}

export interface Comment {
  id: number;
  taskId: number;
  authorId: number;
  authorName: string;
  authorHandle: string;
  body: string;
  createdAt: string;
  editedAt?: string;
}

export interface ActivityEntry {
  id: number;
  projectId?: number;
  taskId?: number;
  actorId?: number;
  actorName?: string;
  action: string;
  summary: string;
  createdAt: string;
}

export interface Notification {
  id: number;
  organisationId: number;
  type: string;
  message: string;
  actorName?: string;
  projectId?: number;
  taskId?: number;
  /** Monday of the week a timesheet notification is about. */
  weekStart?: string;
  read: boolean;
  createdAt: string;
}

export interface TimeEntry {
  id: number;
  userId: number;
  userName: string;
  projectId: number;
  projectName: string;
  projectCode: string;
  taskId?: number;
  taskTitle?: string;
  date: string;
  minutes: number;
  description?: string;
  billable: boolean;
  startedAt?: string;
  endedAt?: string;
  running: boolean;
  locked: boolean;
}

export interface Week {
  timesheetId?: number;
  userId: number;
  userName: string;
  weekStart: string;
  status: TimesheetStatus;
  submittedAt?: string;
  reviewedAt?: string;
  reviewedByName?: string;
  reviewNote?: string;
  entries: TimeEntry[];
  days: { date: string; minutes: number }[];
  totalMinutes: number;
  billableMinutes: number;
}

export interface TimesheetSummary {
  id: number;
  userId: number;
  userName: string;
  weekStart: string;
  status: TimesheetStatus;
  submittedAt?: string;
  totalMinutes: number;
  billableMinutes: number;
  billableAmount: number;
}

/** Somebody whose week is still a draft or was sent back. */
export interface MissingTimesheet {
  userId: number;
  userName: string;
  status: TimesheetStatus;
  totalMinutes: number;
  weeklyCapacityHours: number;
  remindedToday: boolean;
}

export interface MissingTimesheets {
  weekStart: string;
  /** The most recent week that is over by the organisation's date. */
  latestWeekStart: string;
  people: MissingTimesheet[];
}

export type ReminderSkip = 'NOT_MISSING' | 'YOURSELF' | 'ALREADY_REMINDED' | 'DAILY_LIMIT';

export interface RemindResult {
  weekStart: string;
  reminded: number[];
  skipped: { userId: number; reason: ReminderSkip }[];
}

export interface WeekLoad {
  weekStart: string;
  allocatedHours: number;
  capacityHours: number;
  overallocated: boolean;
  taskIds: number[];
}

export interface MemberWorkload {
  userId: number;
  name: string;
  capacityHours: number;
  weeks: WeekLoad[];
  unscheduledHours: number;
}

export interface WorkloadTask {
  id: number;
  title: string;
  projectId: number;
  projectCode: string;
  projectColor: string;
  startDate?: string;
  dueDate?: string;
  estimateHours: number;
}

export interface Workload {
  from: string;
  weeks: number;
  members: MemberWorkload[];
  tasks: WorkloadTask[];
}

export interface Dashboard {
  today: string;
  overdue: Task[];
  dueSoon: Task[];
  inProgress: Task[];
  activeProjects: ProjectListItem[];
  minutesThisWeek: number;
  billableMinutesThisWeek: number;
  pendingApprovals: number;
  /** Last week's timesheets that have time in them and were not submitted. Zero unless you manage. */
  missingTimesheets: number;
  lastWeekTimesheet?: TimesheetStatus;
}
