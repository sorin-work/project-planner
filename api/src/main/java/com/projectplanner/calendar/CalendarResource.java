package com.projectplanner.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectplanner.common.ApiException;
import com.projectplanner.common.Fails;
import com.projectplanner.org.Membership;
import com.projectplanner.org.OrgAccess;
import io.quarkus.panache.common.Parameters;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import static com.projectplanner.common.Text.blankToNull;

/**
 * Holidays and time off. A holiday is a day nobody works: it is left out of every count of
 * working days, so plans, progress and capacity skip it. Time off is one person's days away:
 * it only lowers their capacity. Neither moves a plan that is already there.
 */
@Path("/api")
@Authenticated
@Tag(name = "Calendar")
public class CalendarResource {

    /** No list of holidays or time off is asked for over more than this many days. */
    static final int MAX_RANGE_DAYS = 800;

    private final OrgAccess access;
    private final OrgCalendars calendars;
    private final Clock clock;

    public CalendarResource(OrgAccess access, OrgCalendars calendars, Clock clock) {
        this.access = access;
        this.calendars = calendars;
        this.clock = clock;
    }

    @GET
    @Path("/orgs/{orgId}/holidays")
    @Operation(summary = "List the holidays",
            description = "For every member, client viewers included, since their timeline shades them too. "
                    + "All of them by date, or those from and to the dates given, both included.")
    @Fails(status = 400, code = "invalid_range", when = "to is before from, or the range is longer than 800 days")
    public List<HolidayResponse> holidays(@PathParam("orgId") long orgId,
                                          @QueryParam("from") LocalDate from, @QueryParam("to") LocalDate to) {
        access.member(orgId);
        checkRange(from, to);
        Parameters params = Parameters.with("org", orgId);
        String query = "organisationId = :org" + bound("date >= :from", "from", from, params) + bound("date <= :to", "to", to, params);
        return Holiday.<Holiday>list(query + " order by date", params).stream().map(HolidayResponse::of).toList();
    }

    @POST
    @Path("/orgs/{orgId}/holidays")
    @Transactional
    @ResponseStatus(201)
    @Operation(summary = "Add a holiday",
            description = "For owners and managers. Plans are not moved: a task across the day is one working day shorter.")
    @Fails(status = 409, code = "holiday_taken", when = "the organisation already has a holiday on that date")
    public HolidayResponse addHoliday(@PathParam("orgId") long orgId, @Valid @NotNull HolidayRequest request) {
        access.manager(orgId);
        Holiday holiday = new Holiday();
        holiday.organisationId = orgId;
        apply(holiday, request);
        holiday.persist();
        calendars.changed(orgId);
        return HolidayResponse.of(holiday);
    }

    @PUT
    @Path("/orgs/{orgId}/holidays/{holidayId}")
    @Transactional
    @Operation(summary = "Change a holiday", description = "For owners and managers.")
    @Fails(status = 409, code = "holiday_taken", when = "the organisation already has a holiday on that date")
    public HolidayResponse updateHoliday(@PathParam("orgId") long orgId, @PathParam("holidayId") long holidayId,
                                         @Valid @NotNull HolidayRequest request) {
        access.manager(orgId);
        Holiday holiday = holidayIn(orgId, holidayId);
        apply(holiday, request);
        calendars.changed(orgId);
        return HolidayResponse.of(holiday);
    }

    @DELETE
    @Path("/orgs/{orgId}/holidays/{holidayId}")
    @Transactional
    @ResponseStatus(204)
    @Operation(summary = "Remove a holiday", description = "For owners and managers.")
    public void deleteHoliday(@PathParam("orgId") long orgId, @PathParam("holidayId") long holidayId) {
        access.manager(orgId);
        holidayIn(orgId, holidayId).delete();
        calendars.changed(orgId);
    }

    @GET
    @Path("/orgs/{orgId}/time-off")
    @Operation(summary = "List time off",
            description = "For the team, everybody's, since the workload shows it anyway. Optionally for one person, "
                    + "and only what touches the range from and to, both included.")
    @Fails(status = 400, code = "invalid_range", when = "to is before from, or the range is longer than 800 days")
    public List<TimeOffResponse> timeOff(@PathParam("orgId") long orgId,
                                         @Parameter(description = "Only this person's.") @QueryParam("userId") Long userId,
                                         @QueryParam("from") LocalDate from, @QueryParam("to") LocalDate to) {
        access.team(orgId);
        checkRange(from, to);
        Parameters params = Parameters.with("org", orgId);
        String query = "organisationId = :org" + bound("lastDay >= :from", "from", from, params)
                + bound("firstDay <= :to", "to", to, params) + bound("userId = :user", "user", userId, params);
        return TimeOff.<TimeOff>list(query + " order by firstDay, id", params).stream().map(TimeOffResponse::of).toList();
    }

    @POST
    @Path("/orgs/{orgId}/time-off")
    @Transactional
    @ResponseStatus(201)
    @Operation(summary = "Add time off",
            description = "Your own, or for owners and managers anybody's on the team. It lowers capacity and moves no plan.")
    @Fails(status = 400, code = "invalid_dates", when = "lastDay is before firstDay, or the time off is longer than a year")
    @Fails(status = 400, code = "unknown_member", when = "userId is not somebody on the team")
    @Fails(status = 409, code = "time_off_overlaps", when = "the person already has time off on one of the days")
    public TimeOffResponse addTimeOff(@PathParam("orgId") long orgId, @Valid @NotNull TimeOffRequest request) {
        Membership me = access.team(orgId);
        long userId = request.userId() == null ? me.userId : request.userId();
        checkWhose(me, userId);
        TimeOff timeOff = new TimeOff();
        timeOff.organisationId = orgId;
        timeOff.userId = userId;
        timeOff.createdAt = Instant.now(clock);
        apply(timeOff, request);
        timeOff.persist();
        return TimeOffResponse.of(timeOff);
    }

    @PUT
    @Path("/orgs/{orgId}/time-off/{timeOffId}")
    @Transactional
    @Operation(summary = "Change time off", description = "Your own, or for owners and managers anybody's. It stays with the same person.")
    @Fails(status = 400, code = "invalid_dates", when = "lastDay is before firstDay, or the time off is longer than a year")
    @Fails(status = 409, code = "time_off_overlaps", when = "the person already has time off on one of the days")
    public TimeOffResponse updateTimeOff(@PathParam("orgId") long orgId, @PathParam("timeOffId") long timeOffId,
                                         @Valid @NotNull TimeOffRequest request) {
        Membership me = access.team(orgId);
        TimeOff timeOff = timeOffIn(orgId, timeOffId);
        checkWhose(me, timeOff.userId);
        apply(timeOff, request);
        return TimeOffResponse.of(timeOff);
    }

    @DELETE
    @Path("/orgs/{orgId}/time-off/{timeOffId}")
    @Transactional
    @ResponseStatus(204)
    @Operation(summary = "Remove time off", description = "Your own, or for owners and managers anybody's.")
    public void deleteTimeOff(@PathParam("orgId") long orgId, @PathParam("timeOffId") long timeOffId) {
        Membership me = access.team(orgId);
        TimeOff timeOff = timeOffIn(orgId, timeOffId);
        checkWhose(me, timeOff.userId);
        timeOff.delete();
    }

    /** " and " + {@code condition} when there is a value for it, with the value added to {@code params}. */
    private static String bound(String condition, String name, Object value, Parameters params) {
        if (value == null) {
            return "";
        }
        params.and(name, value);
        return " and " + condition;
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && (to.isBefore(from) || to.toEpochDay() - from.toEpochDay() > MAX_RANGE_DAYS)) {
            throw ApiException.badRequest("invalid_range", "Ask for at most " + MAX_RANGE_DAYS + " days, with to not before from");
        }
    }

    private static void apply(Holiday holiday, HolidayRequest request) {
        boolean taken = Holiday.<Holiday>find("organisationId = ?1 and date = ?2", holiday.organisationId, request.date())
                .stream().anyMatch(h -> !Objects.equals(h.id, holiday.id));
        if (taken) {
            throw ApiException.conflict("holiday_taken", "There is already a holiday on " + request.date());
        }
        holiday.date = request.date();
        holiday.name = request.name().trim();
    }

    private static void apply(TimeOff timeOff, TimeOffRequest request) {
        if (request.lastDay().isBefore(request.firstDay()) || request.lastDay().isAfter(request.firstDay().plusYears(1))) {
            throw ApiException.badRequest("invalid_dates", "Time off ends on or after its first day, within a year");
        }
        boolean overlaps = TimeOff.<TimeOff>find("organisationId = ?1 and userId = ?2 and firstDay <= ?3 and lastDay >= ?4",
                        timeOff.organisationId, timeOff.userId, request.lastDay(), request.firstDay())
                .stream().anyMatch(t -> !Objects.equals(t.id, timeOff.id));
        if (overlaps) {
            throw ApiException.conflict("time_off_overlaps", "There is already time off on some of these days");
        }
        timeOff.firstDay = request.firstDay();
        timeOff.lastDay = request.lastDay();
        timeOff.note = blankToNull(request.note());
    }

    /** People manage their own time off, owners and managers everybody's on the team. */
    private static void checkWhose(Membership me, long userId) {
        if (userId == me.userId) {
            return;
        }
        if (!me.role.canManage()) {
            throw ApiException.forbidden("You can only change your own time off");
        }
        boolean onTeam = Membership.find(me.organisationId, userId).map(m -> m.role.isTeam()).orElse(false);
        if (!onTeam) {
            throw ApiException.badRequest("unknown_member", "Nobody on the team has this id");
        }
    }

    private static Holiday holidayIn(long orgId, long holidayId) {
        Holiday holiday = Holiday.findById(holidayId);
        if (holiday == null || !Objects.equals(holiday.organisationId, orgId)) {
            throw ApiException.notFound("Holiday");
        }
        return holiday;
    }

    private static TimeOff timeOffIn(long orgId, long timeOffId) {
        TimeOff timeOff = TimeOff.findById(timeOffId);
        if (timeOff == null || !Objects.equals(timeOff.organisationId, orgId)) {
            throw ApiException.notFound("Time off");
        }
        return timeOff;
    }

    public record HolidayRequest(@NotNull LocalDate date, @NotBlank @Size(max = 120) String name) {
    }

    public record HolidayResponse(long id, LocalDate date, String name) {
        static HolidayResponse of(Holiday h) {
            return new HolidayResponse(h.id, h.date, h.name);
        }
    }

    public record TimeOffRequest(Long userId, @NotNull LocalDate firstDay, @NotNull LocalDate lastDay, @Size(max = 500) String note) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimeOffResponse(long id, long userId, LocalDate firstDay, LocalDate lastDay, String note) {
        static TimeOffResponse of(TimeOff t) {
            return new TimeOffResponse(t.id, t.userId, t.firstDay, t.lastDay, t.note);
        }
    }
}
