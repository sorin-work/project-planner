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
  /** Last week's missing timesheets are chased on the first working day of the week. */
  autoReminders: boolean;
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
  /** When the link in the last invitation mail stops working. Missing when no mail was sent. */
  linkExpiresAt?: string;
}

/** What the server offers before signing in. */
export interface AuthOptions {
  mail: boolean;
  inviteLinkRequired: boolean;
}

/** What an invitation link is for. */
export interface InvitationLink {
  email: string;
  organisationName: string;
  role: Role;
  hasAccount: boolean;
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

/** What the API offers for a copy of a project, and how much there is to copy. */
export interface DuplicateDefaults {
  name: string;
  code: string;
  startDate: string;
  phases: number;
  tasks: number;
  dependencies: number;
  /** The files on the tasks, which are only copied when asked for. */
  attachments: number;
  attachmentBytes: number;
}

/** A file attached to a task. Its bytes are fetched through a short-lived link. */
export interface Attachment {
  id: number;
  taskId: number;
  fileName: string;
  /** What the server found the file to be, not what the browser said. */
  contentType: string;
  sizeBytes: number;
  uploaderId?: number;
  uploaderName?: string;
  createdAt: string;
  /** An image that may be shown in the page. Everything else is a download. */
  previewable: boolean;
  /** Whoever attached it, owners and managers. */
  canDelete: boolean;
}

/** A link to the bytes of an attachment. It needs no token and works until {@code expiresAt}. */
export interface AttachmentLink {
  url: string;
  expiresAt: string;
}

/** A saved copy of a plan's dates. Only its name and whether it is the reference can change. */
export interface Baseline {
  id: number;
  projectId: number;
  name: string;
  /** The baseline the project is compared against unless another one is picked. */
  reference: boolean;
  projectStart?: string;
  projectEnd?: string;
  createdBy?: number;
  createdByName?: string;
  createdAt: string;
  /** How many tasks it holds. */
  tasks: number;
}

/** How a task, a phase or a date compares with a baseline. The API decides it, in working days. */
export type BaselineChange = 'UNCHANGED' | 'SLIPPED' | 'PULLED_IN' | 'ADDED' | 'REMOVED';

export interface BaselineTaskChange {
  taskId: number;
  title: string;
  milestone: boolean;
  change: BaselineChange;
  existedThen: boolean;
  existsNow: boolean;
  phaseName?: string;
  baselineStart?: string;
  baselineDue?: string;
  currentStart?: string;
  currentDue?: string;
  /** Working days, later being positive. Only when the task has dates in both. */
  startSlipDays?: number;
  finishSlipDays?: number;
}

export interface BaselinePhaseChange {
  phaseId: number;
  name: string;
  change: BaselineChange;
  existedThen: boolean;
  existsNow: boolean;
  baselineStart?: string;
  baselineEnd?: string;
  currentStart?: string;
  currentEnd?: string;
  startSlipDays?: number;
  finishSlipDays?: number;
}

export interface BaselineDateChange {
  baseline?: string;
  current?: string;
  slipDays?: number;
  change: BaselineChange;
}

export interface BaselineComparison {
  baseline: Baseline;
  tasks: BaselineTaskChange[];
  phases: BaselinePhaseChange[];
  /** The project's own end date, then and now. */
  projectEnd: BaselineDateChange;
  /** The latest due date of any task, then and now. */
  planEnd: BaselineDateChange;
  counts: { unchanged: number; slipped: number; pulledIn: number; added: number; removed: number };
  baselineEstimateHours: number;
  currentEstimateHours: number;
}

export interface HealthSummary {
  status: HealthStatus;
  progressPct: number;
  hoursBurnPct?: number;
  amountBurnPct?: number;
  overdueTasks: number;
  openTasks: number;
  scheduleVarianceHours: number;
  /** How many issues the timeline lists for the plan. In the project list only. */
  scheduleIssues?: number;
}

export interface ProjectListItem {
  project: Project;
  health: HealthSummary;
}

/** A project as a select needs it, from `/projects/options`: nothing is worked out for it. */
export interface ProjectOption {
  id: number;
  code: string;
  name: string;
  status: ProjectStatus;
  clientId?: number;
  clientName?: string;
}

export interface Phase {
  id: number;
  projectId: number;
  name: string;
  position: number;
  startDate?: string;
  endDate?: string;
}

/**
 * How a task waits for another: finish-to-start, start-to-start, finish-to-finish or
 * start-to-finish. The first letter is the predecessor's end that counts, the second
 * the successor's.
 */
export type DependencyType = 'FS' | 'SS' | 'FF' | 'SF';

export interface Dependency {
  id: number;
  predecessorId: number;
  successorId: number;
  /** Working days. Negative is a lead. */
  lagDays: number;
  type: DependencyType;
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
  /** Missing for a finished task, which stays at its own dates and has no float. */
  floatDays?: number | null;
  critical: boolean;
}

export interface CriticalPath {
  start?: string;
  finish?: string;
  durationDays: number;
  criticalTaskIds: number[];
  tasks: TaskTiming[];
}

export type ScheduleIssueType = 'STARTS_TOO_EARLY' | 'WAITS_FOR_UNSCHEDULED' | 'AFTER_PROJECT_END' | 'OUTSIDE_PARENT';

export interface ScheduleIssue {
  type: ScheduleIssueType;
  taskId: number;
  /** The task it waits for, or the parent for OUTSIDE_PARENT. */
  relatedTaskId?: number;
  /** Working days too early, too late or outside the parent. */
  days?: number;
  /** Where to move the task to fix a STARTS_TOO_EARLY issue. The API works both dates out. */
  suggestedStart?: string;
  suggestedDue?: string;
  /** What that move would get the task reported for instead: AFTER_PROJECT_END, OUTSIDE_PARENT or both. */
  suggestionCauses?: ScheduleIssueType[];
  /** For the two link issues: the link's type. With FF and SF it is the finish that is too early. */
  linkType?: DependencyType;
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
  /** How many issues the timeline lists for the plan. */
  scheduleIssues: number;
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
  /**
   * Another project the entry is about, such as the one a plan was copied from. The
   * API only sends it to a reader who may see that project.
   */
  relatedProjectId?: number;
  relatedProjectName?: string;
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
  /** Each day of the week, with the name of the organisation's holiday when it is one. */
  days: { date: string; minutes: number; holiday?: string }[];
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
  /** The week's capacity: the weekly hours less a fifth for each holiday and each day off. */
  capacityHours: number;
  /** Time off on every working day of the week. */
  away: boolean;
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

/** A week as it was in the approval queue. */
export interface SheetToApprove {
  id: number;
  submittedAt: string;
}

export type ApprovalSkip = 'NOT_FOUND' | 'OWN_WEEK' | 'NOT_SUBMITTED' | 'CHANGED';

export interface ApproveResult {
  approved: number[];
  skipped: { timesheetId: number; reason: ApprovalSkip }[];
}

export type ReportGroupBy = 'PROJECT' | 'CLIENT' | 'MEMBER' | 'TASK';
/** A range the API works out from the organisation's date. */
export type ReportPreset = 'this_week' | 'last_week' | 'this_month' | 'last_month';

/** Time is in whole minutes and money has two decimals, so a column adds up to its total. */
export interface TimeReportRow {
  key: string;
  label: string;
  minutes: number;
  billableMinutes: number;
  /** Only for owners and managers. */
  billableAmount?: number | null;
}

export interface TimeReport {
  from: string;
  to: string;
  groupBy: ReportGroupBy;
  rows: TimeReportRow[];
  total: TimeReportRow;
}

export interface UtilisationRow {
  userId?: number | null;
  name: string;
  minutes: number;
  billableMinutes: number;
  capacityMinutes: number;
  /** Billable time as a share of capacity. Missing when there is no capacity to compare with. */
  utilisationPct?: number | null;
}

export interface UtilisationReport {
  from: string;
  to: string;
  /** The last day time and capacity were counted for: the end of the range, or today when it runs into the future. */
  countedTo?: string | null;
  workingDays: number;
  rows: UtilisationRow[];
  total: UtilisationRow;
}

export interface WeekLoad {
  weekStart: string;
  allocatedHours: number;
  /** The week's capacity: the weekly hours, less a fifth for each holiday and each day off. */
  capacityHours: number;
  overallocated: boolean;
  taskIds: number[];
  holidays: number;
  daysOff: number;
}

/** A day nobody in the organisation works. */
export interface Holiday {
  id: number;
  date: string;
  name: string;
}

/** Days one person is away, both included. It lowers their capacity and moves no plan. */
export interface TimeOff {
  id: number;
  userId: number;
  firstDay: string;
  lastDay: string;
  note?: string;
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

export type SearchHitType = 'PROJECT' | 'TASK' | 'COMMENT';

/** One thing search found. `taskId` is the task to open, for a task and for a comment on one. */
export interface SearchHit {
  type: SearchHitType;
  id: number;
  projectId: number;
  projectCode: string;
  taskId?: number;
  title: string;
  snippet?: string;
}

export interface SearchResponse {
  query: string;
  projects: SearchHit[];
  tasks: SearchHit[];
  comments: SearchHit[];
}

export type NotificationType =
  | 'MENTIONED'
  | 'ASSIGNED'
  | 'COMMENTED'
  | 'TIMESHEET_SUBMITTED'
  | 'TIMESHEET_APPROVED'
  | 'TIMESHEET_REJECTED'
  | 'TIMESHEET_REMINDER'
  | 'BUDGET_ALERT'
  /** Mail only: on (EMAIL) or OFF, never a notification in the app. */
  | 'DAILY_SUMMARY';

/** How somebody gets one type of notification: mailed (and in the app), in the app only, or not at all. */
export type Delivery = 'EMAIL' | 'IN_APP' | 'OFF';

export interface NotificationPreference {
  type: NotificationType;
  delivery: Delivery;
  byDefault: Delivery;
}

export interface NotificationPreferences {
  mailAvailable: boolean;
  preferences: NotificationPreference[];
}
