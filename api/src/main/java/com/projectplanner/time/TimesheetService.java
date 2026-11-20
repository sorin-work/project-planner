package com.projectplanner.time;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.User;
import com.projectplanner.calendar.Holiday;
import com.projectplanner.common.ApiException;
import com.projectplanner.common.Money;
import com.projectplanner.notification.Notification;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.notification.Notifier;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Organisation;
import com.projectplanner.org.RateBook;
import com.projectplanner.org.Role;
import com.projectplanner.project.Project;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.time.MissingTimesheets.Missing;
import com.projectplanner.time.MissingTimesheets.Person;
import com.projectplanner.time.MissingTimesheets.Skip;
import com.projectplanner.time.TimeDtos.DayTotal;
import com.projectplanner.time.TimeDtos.MissingResponse;
import com.projectplanner.time.TimeDtos.RemindResponse;
import com.projectplanner.time.TimeDtos.SkippedReminder;
import com.projectplanner.time.TimeDtos.TimesheetSummary;
import com.projectplanner.time.TimeDtos.WeekResponse;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.projectplanner.common.Text.blankToNull;

@ApplicationScoped
public class TimesheetService {

    private final TimeService time;
    private final Notifier notifier;
    private final ActivityLog activity;
    private final Clock clock;

    public TimesheetService(TimeService time, Notifier notifier, ActivityLog activity, Clock clock) {
        this.time = time;
        this.notifier = notifier;
        this.activity = activity;
        this.clock = clock;
    }

    public WeekResponse week(long organisationId, long userId, LocalDate anyDay) {
        LocalDate weekStart = WorkCalendar.weekStart(anyDay);
        LocalDate weekEnd = weekStart.plusDays(6);
        List<TimeEntry> entries = TimeEntry.list(
                "organisationId = ?1 and userId = ?2 and entryDate between ?3 and ?4 order by entryDate, id",
                organisationId, userId, weekStart, weekEnd);
        int[] perDay = new int[7];
        int total = 0;
        int billable = 0;
        for (TimeEntry entry : entries) {
            perDay[(int) (entry.entryDate.toEpochDay() - weekStart.toEpochDay())] += entry.minutes;
            total += entry.minutes;
            if (entry.billable) {
                billable += entry.minutes;
            }
        }
        // The grid marks the holidays of the week in its header. Time can still be logged on them.
        Map<LocalDate, String> holidays = new HashMap<>();
        Holiday.<Holiday>list("organisationId = ?1 and date between ?2 and ?3", organisationId, weekStart, weekEnd)
                .forEach(h -> holidays.put(h.date, h.name));
        List<DayTotal> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = weekStart.plusDays(i);
            days.add(new DayTotal(day, perDay[i], holidays.get(day)));
        }
        Timesheet sheet = Timesheet.find(organisationId, userId, weekStart).orElse(null);
        User user = User.findById(userId);
        String reviewer = null;
        if (sheet != null && sheet.reviewedBy != null) {
            User reviewedBy = User.findById(sheet.reviewedBy);
            reviewer = reviewedBy == null ? null : reviewedBy.name;
        }
        return new WeekResponse(sheet == null ? null : sheet.id, userId, user.name, weekStart,
                sheet == null ? TimesheetStatus.DRAFT : sheet.status,
                sheet == null ? null : sheet.submittedAt,
                sheet == null ? null : sheet.reviewedAt,
                reviewer,
                sheet == null ? null : sheet.reviewNote,
                time.toResponses(entries), days, total, billable);
    }

    public Timesheet submit(Membership me, LocalDate anyDay) {
        LocalDate weekStart = WorkCalendar.weekStart(anyDay);
        if (TimeEntry.runningFor(me.organisationId, me.userId)
                .filter(e -> WorkCalendar.weekStart(time.dayOf(e)).equals(weekStart))
                .isPresent()) {
            throw ApiException.conflict("timer_running", "Stop the running timer before submitting this week");
        }
        long entries = TimeEntry.count("organisationId = ?1 and userId = ?2 and entryDate between ?3 and ?4",
                me.organisationId, me.userId, weekStart, weekStart.plusDays(6));
        if (entries == 0) {
            throw ApiException.badRequest("empty_timesheet", "There is no time logged in this week");
        }
        Timesheet sheet = Timesheet.find(me.organisationId, me.userId, weekStart).orElseGet(() -> {
            Timesheet created = new Timesheet();
            created.organisationId = me.organisationId;
            created.userId = me.userId;
            created.weekStart = weekStart;
            return created;
        });
        if (sheet.status.locked()) {
            throw ApiException.conflict("already_submitted", "This week has already been " + sheet.status.name().toLowerCase());
        }
        sheet.status = TimesheetStatus.SUBMITTED;
        sheet.submittedAt = Instant.now(clock);
        sheet.reviewedAt = null;
        sheet.reviewedBy = null;
        sheet.reviewNote = null;
        sheet.persist();

        User user = User.findById(me.userId);
        String message = user.name + " submitted their timesheet for the week of " + weekStart;
        for (Membership m : Membership.forOrganisation(me.organisationId)) {
            if (m.role.canManage()) {
                notifier.notifyAboutWeek(m.userId, me.organisationId, me.userId, NotificationType.TIMESHEET_SUBMITTED,
                        message, weekStart);
            }
        }
        activity.record(me.organisationId, null, null, me.userId, "timesheet.submitted", message);
        return sheet;
    }

    /** Takes a submitted week back to draft so it can be corrected before review. */
    public Timesheet withdraw(Membership me, LocalDate anyDay) {
        LocalDate weekStart = WorkCalendar.weekStart(anyDay);
        Timesheet sheet = Timesheet.find(me.organisationId, me.userId, weekStart)
                .orElseThrow(() -> ApiException.notFound("Timesheet"));
        if (sheet.status != TimesheetStatus.SUBMITTED) {
            throw ApiException.conflict("not_submitted", "Only submitted timesheets can be withdrawn");
        }
        sheet.status = TimesheetStatus.DRAFT;
        sheet.submittedAt = null;
        return sheet;
    }

    public Timesheet approve(Membership me, Timesheet sheet) {
        checkReviewer(me, sheet);
        sheet.status = TimesheetStatus.APPROVED;
        sheet.reviewedAt = Instant.now(clock);
        sheet.reviewedBy = me.userId;
        sheet.reviewNote = null;
        User reviewer = User.findById(me.userId);
        notifier.notifyAboutWeek(sheet.userId, sheet.organisationId, me.userId, NotificationType.TIMESHEET_APPROVED,
                reviewer.name + " approved your timesheet for the week of " + sheet.weekStart, sheet.weekStart);
        User owner = User.findById(sheet.userId);
        activity.record(sheet.organisationId, null, null, me.userId, "timesheet.approved",
                "Approved " + owner.name + "'s timesheet for the week of " + sheet.weekStart);
        return sheet;
    }

    public Timesheet reject(Membership me, Timesheet sheet, String note) {
        checkReviewer(me, sheet);
        sheet.status = TimesheetStatus.REJECTED;
        sheet.reviewedAt = Instant.now(clock);
        sheet.reviewedBy = me.userId;
        sheet.reviewNote = blankToNull(note);
        User reviewer = User.findById(me.userId);
        String message = reviewer.name + " sent back your timesheet for the week of " + sheet.weekStart
                + (sheet.reviewNote == null ? "" : ": " + sheet.reviewNote);
        notifier.notifyAboutWeek(sheet.userId, sheet.organisationId, me.userId, NotificationType.TIMESHEET_REJECTED,
                message, sheet.weekStart);
        User owner = User.findById(sheet.userId);
        activity.record(sheet.organisationId, null, null, me.userId, "timesheet.rejected",
                "Sent back " + owner.name + "'s timesheet for the week of " + sheet.weekStart);
        return sheet;
    }

    /**
     * The team members whose week is still a draft or was sent back. Without a day it is
     * the most recent week that is over; a week that is still running is not late yet.
     */
    public MissingResponse missing(long organisationId, LocalDate anyDay) {
        Organisation org = Organisation.findById(organisationId);
        return missing(org, org.today(clock), anyDay);
    }

    private MissingResponse missing(Organisation org, LocalDate today, LocalDate anyDay) {
        long organisationId = org.id;
        LocalDate latest = MissingTimesheets.latestEndedWeek(today);
        LocalDate weekStart = anyDay == null ? latest : WorkCalendar.weekStart(anyDay);
        if (weekStart.isAfter(latest)) {
            throw ApiException.badRequest("week_not_over", "The week of " + weekStart + " has not ended yet");
        }

        List<Membership> team = Membership.forOrganisation(organisationId).stream().filter(m -> m.role.isTeam()).toList();
        Set<Long> userIds = team.stream().map(m -> m.userId).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        User.<User>list("id in ?1", userIds).forEach(u -> names.put(u.id, u.name));
        List<Person> people = team.stream()
                .map(m -> new Person(m.userId, names.get(m.userId), LocalDate.ofInstant(m.createdAt, org.zone()),
                        m.weeklyCapacityHours))
                .toList();

        Map<Long, TimesheetStatus> statuses = new HashMap<>();
        Timesheet.<Timesheet>list("organisationId = ?1 and weekStart = ?2", organisationId, weekStart)
                .forEach(sheet -> statuses.put(sheet.userId, sheet.status));
        Map<Long, Integer> minutes = new HashMap<>();
        TimeEntry.<TimeEntry>list("organisationId = ?1 and entryDate between ?2 and ?3",
                        organisationId, weekStart, weekStart.plusDays(6))
                .forEach(entry -> minutes.merge(entry.userId, entry.minutes, Integer::sum));
        Set<Long> reminded = Notification.<Notification>list(
                        "userId in ?1 and organisationId = ?2 and type = ?3 and weekStart = ?4 and createdAt >= ?5",
                        userIds, organisationId, NotificationType.TIMESHEET_REMINDER, weekStart, startOfDay(org, today))
                .stream().map(n -> n.userId).collect(Collectors.toSet());

        return new MissingResponse(weekStart, latest, MissingTimesheets.find(weekStart, people, statuses, minutes, reminded));
    }

    /**
     * Reminds each of {@code userIds} whose week is missing, and says who was left out and why.
     * Whoever asks, nobody is reminded about the same week twice on the same day, or more than
     * {@link MissingTimesheets#MAX_REMINDERS_PER_DAY} times a day in all.
     */
    public RemindResponse remind(Membership me, LocalDate anyDay, Collection<Long> userIds) {
        Organisation org = Organisation.findById(me.organisationId);
        LocalDate today = org.today(clock);
        MissingResponse week = missing(org, today, anyDay);
        Map<Long, Missing> missing = new HashMap<>();
        week.people().forEach(person -> missing.put(person.userId(), person));
        Instant since = startOfDay(org, today);
        User actor = User.findById(me.userId);
        String message = actor.name + " asked you to submit your timesheet for the week of " + week.weekStart();

        List<Long> reminded = new ArrayList<>();
        List<SkippedReminder> skipped = new ArrayList<>();
        // In id order, so two requests for the same people take the locks below in the same order.
        for (Long userId : new TreeSet<>(userIds)) {
            Optional<Skip> skip = MissingTimesheets.skipReason(missing.get(userId), me.userId);
            if (skip.isEmpty()) {
                skip = MissingTimesheets.reminderLimit(week.weekStart(), weeksRemindedSince(me.organisationId, userId, since));
            }
            if (skip.isPresent()) {
                skipped.add(new SkippedReminder(userId, skip.get()));
                continue;
            }
            notifier.notifyAboutWeek(userId, me.organisationId, me.userId, NotificationType.TIMESHEET_REMINDER,
                    message, week.weekStart());
            reminded.add(userId);
        }
        return new RemindResponse(week.weekStart(), reminded, skipped);
    }

    /**
     * The weeks this person has been reminded about from {@code since} on. Takes a lock on their
     * membership first: requests that arrive together wait for each other here, so the second
     * one sees the reminder the first one wrote.
     */
    private static List<LocalDate> weeksRemindedSince(long organisationId, long userId, Instant since) {
        Membership.find("organisationId = ?1 and userId = ?2", organisationId, userId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).list();
        return Notification.<Notification>list("userId = ?1 and organisationId = ?2 and type = ?3 and createdAt >= ?4",
                        userId, organisationId, NotificationType.TIMESHEET_REMINDER, since)
                .stream().map(n -> n.weekStart).toList();
    }

    private static Instant startOfDay(Organisation org, LocalDate day) {
        return day.atStartOfDay(org.zone()).toInstant();
    }

    public List<TimesheetSummary> summaries(long organisationId, List<Timesheet> sheets) {
        List<TimesheetSummary> result = new ArrayList<>();
        if (sheets.isEmpty()) {
            return result;
        }
        // Everything at once: the entries of exactly the queued weeks in one query, then the projects
        // they are on and the people, so that a long queue costs what a short one does, and a week
        // between two queued ones is not read.
        Set<Long> userIds = sheets.stream().map(sheet -> sheet.userId).collect(Collectors.toSet());
        StringBuilder weeks = new StringBuilder();
        Parameters params = Parameters.with("org", organisationId);
        for (int i = 0; i < sheets.size(); i++) {
            Timesheet sheet = sheets.get(i);
            weeks.append(i == 0 ? "" : " or ").append("(userId = :u").append(i).append(" and entryDate between :f").append(i)
                    .append(" and :t").append(i).append(')');
            params.and("u" + i, sheet.userId).and("f" + i, sheet.weekStart).and("t" + i, sheet.weekStart.plusDays(6));
        }
        Map<Long, Map<LocalDate, List<TimeEntry>>> byWeek = new HashMap<>();
        TimeEntry.<TimeEntry>list("organisationId = :org and (" + weeks + ")", params)
                .forEach(e -> byWeek.computeIfAbsent(e.userId, id -> new HashMap<>())
                        .computeIfAbsent(WorkCalendar.weekStart(e.entryDate), week -> new ArrayList<>()).add(e));
        Set<Long> projectIds = byWeek.values().stream().flatMap(weeks -> weeks.values().stream()).flatMap(List::stream)
                .filter(e -> e.billable).map(e -> e.projectId).collect(Collectors.toSet());
        Map<Long, Project> projects = new HashMap<>();
        if (!projectIds.isEmpty()) {
            Project.<Project>list("id in ?1", projectIds).forEach(p -> projects.put(p.id, p));
        }
        Map<Long, String> names = new HashMap<>();
        User.<User>list("id in ?1", userIds).forEach(u -> names.put(u.id, u.name));
        RateBook rates = RateBook.load(organisationId);
        for (Timesheet sheet : sheets) {
            List<TimeEntry> entries = byWeek.getOrDefault(sheet.userId, Map.of()).getOrDefault(sheet.weekStart, List.of());
            int total = 0;
            int billable = 0;
            BigDecimal amount = BigDecimal.ZERO;
            for (TimeEntry e : entries) {
                total += e.minutes;
                if (e.billable) {
                    billable += e.minutes;
                    // Priced and rounded entry by entry, as everywhere else.
                    amount = amount.add(Money.cost(e.minutes, rates.rate(projects.get(e.projectId).hourlyRate, e.userId)));
                }
            }
            result.add(new TimesheetSummary(sheet.id, sheet.userId, names.get(sheet.userId), sheet.weekStart, sheet.status,
                    sheet.submittedAt, total, billable, Money.round(amount)));
        }
        return result;
    }

    private static void checkReviewer(Membership me, Timesheet sheet) {
        if (!me.role.canManage()) {
            throw ApiException.forbidden("Only owners and managers review timesheets");
        }
        if (Objects.equals(sheet.userId, me.userId) && me.role != Role.OWNER) {
            throw ApiException.forbidden("Ask another manager to review your own timesheet");
        }
        if (sheet.status != TimesheetStatus.SUBMITTED) {
            throw ApiException.conflict("not_submitted", "Only submitted timesheets can be reviewed");
        }
    }
}
