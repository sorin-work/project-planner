package com.projectplanner.time;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.User;
import com.projectplanner.common.ApiException;
import com.projectplanner.common.Money;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.notification.Notifier;
import com.projectplanner.org.Membership;
import com.projectplanner.org.RateBook;
import com.projectplanner.org.Role;
import com.projectplanner.project.Project;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.time.TimeDtos.DayTotal;
import com.projectplanner.time.TimeDtos.TimesheetSummary;
import com.projectplanner.time.TimeDtos.WeekResponse;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        List<DayTotal> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            days.add(new DayTotal(weekStart.plusDays(i), perDay[i]));
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
                notifier.notify(m.userId, me.organisationId, me.userId, NotificationType.TIMESHEET_SUBMITTED, message, null, null);
            }
        }
        activity.record(me.organisationId, null, null, me.userId, "timesheet.submitted", message);
        return sheet;
    }

    public Timesheet approve(Membership me, Timesheet sheet) {
        checkReviewer(me, sheet);
        sheet.status = TimesheetStatus.APPROVED;
        sheet.reviewedAt = Instant.now(clock);
        sheet.reviewedBy = me.userId;
        sheet.reviewNote = null;
        User reviewer = User.findById(me.userId);
        notifier.notify(sheet.userId, sheet.organisationId, me.userId, NotificationType.TIMESHEET_APPROVED,
                reviewer.name + " approved your timesheet for the week of " + sheet.weekStart, null, null);
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
        sheet.reviewNote = note == null || note.isBlank() ? null : note.trim();
        User reviewer = User.findById(me.userId);
        String message = reviewer.name + " sent back your timesheet for the week of " + sheet.weekStart
                + (sheet.reviewNote == null ? "" : ": " + sheet.reviewNote);
        notifier.notify(sheet.userId, sheet.organisationId, me.userId, NotificationType.TIMESHEET_REJECTED, message, null, null);
        User owner = User.findById(sheet.userId);
        activity.record(sheet.organisationId, null, null, me.userId, "timesheet.rejected",
                "Sent back " + owner.name + "'s timesheet for the week of " + sheet.weekStart);
        return sheet;
    }

    public List<TimesheetSummary> summaries(long organisationId, List<Timesheet> sheets) {
        RateBook rates = RateBook.load(organisationId);
        Map<Long, Project> projects = new HashMap<>();
        List<TimesheetSummary> result = new ArrayList<>();
        for (Timesheet sheet : sheets) {
            List<TimeEntry> entries = TimeEntry.list("organisationId = ?1 and userId = ?2 and entryDate between ?3 and ?4",
                    organisationId, sheet.userId, sheet.weekStart, sheet.weekStart.plusDays(6));
            int total = 0;
            int billable = 0;
            BigDecimal amount = BigDecimal.ZERO;
            for (TimeEntry e : entries) {
                total += e.minutes;
                if (e.billable) {
                    billable += e.minutes;
                    Project p = projects.computeIfAbsent(e.projectId, id -> Project.findById(id));
                    amount = amount.add(Money.cost(e.minutes, rates.rate(p.hourlyRate, e.userId)));
                }
            }
            User user = User.findById(sheet.userId);
            result.add(new TimesheetSummary(sheet.id, sheet.userId, user.name, sheet.weekStart, sheet.status,
                    sheet.submittedAt, total, billable, Money.round(amount)));
        }
        return result;
    }

    private static void checkReviewer(Membership me, Timesheet sheet) {
        if (!me.role.canManage()) {
            throw ApiException.forbidden("Only owners and managers review timesheets");
        }
        if (sheet.status != TimesheetStatus.SUBMITTED) {
            throw ApiException.conflict("not_submitted", "Only submitted timesheets can be reviewed");
        }
    }
}
