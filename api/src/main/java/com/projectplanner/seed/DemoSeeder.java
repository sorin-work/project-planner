package com.projectplanner.seed;

import com.projectplanner.activity.ActivityLog;
import com.projectplanner.auth.PasswordHasher;
import com.projectplanner.auth.User;
import com.projectplanner.baseline.Baseline;
import com.projectplanner.baseline.BaselinePhase;
import com.projectplanner.baseline.BaselineTask;
import com.projectplanner.client.Client;
import com.projectplanner.comment.Comment;
import com.projectplanner.notification.Notification;
import com.projectplanner.notification.NotificationType;
import com.projectplanner.notification.Notifier;
import com.projectplanner.org.Membership;
import com.projectplanner.org.Organisation;
import com.projectplanner.org.Role;
import com.projectplanner.org.RoleRate;
import com.projectplanner.project.Phase;
import com.projectplanner.project.Project;
import com.projectplanner.project.ProjectStatus;
import com.projectplanner.report.BudgetAlerts;
import com.projectplanner.schedule.WorkCalendar;
import com.projectplanner.task.Priority;
import com.projectplanner.task.Task;
import com.projectplanner.task.TaskDependency;
import com.projectplanner.task.TaskStatus;
import com.projectplanner.time.TimeEntry;
import com.projectplanner.time.Timesheet;
import com.projectplanner.time.TimesheetStatus;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Creates a demo agency with a year of history when SEED_DEMO=true. Dates are
 * relative to today so the data always looks current. Runs once: if the demo
 * user already exists nothing happens.
 */
@ApplicationScoped
public class DemoSeeder {

    public static final String DEMO_EMAIL = "demo@example.com";
    public static final String DEMO_PASSWORD = "demo-password";

    private static final Logger LOG = Logger.getLogger(DemoSeeder.class);
    private static final WorkCalendar CAL = WorkCalendar.DEFAULT;
    static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");

    private final boolean enabled;
    private final PasswordHasher hasher;
    private final ActivityLog activity;
    private final Notifier notifier;
    private final BudgetAlerts budgetAlerts;
    private final Clock clock;

    private final Random random = new Random(20251110L);
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, Client> clients = new HashMap<>();
    private final List<SeedTask> seedTasks = new ArrayList<>();
    private Organisation org;
    private LocalDate today;
    private Instant now;

    public DemoSeeder(@ConfigProperty(name = "planner.seed-demo") boolean enabled, PasswordHasher hasher,
                      ActivityLog activity, Notifier notifier, BudgetAlerts budgetAlerts, Clock clock) {
        this.enabled = enabled;
        this.hasher = hasher;
        this.activity = activity;
        this.notifier = notifier;
        this.budgetAlerts = budgetAlerts;
        this.clock = clock;
    }

    @Transactional
    void onStart(@Observes StartupEvent event) {
        if (enabled) {
            seed();
        }
    }

    void seed() {
        if (User.findByEmail(DEMO_EMAIL).isPresent()) {
            LOG.info("Demo data already present, skipping");
            return;
        }
        long started = System.currentTimeMillis();
        now = clock.instant();
        today = LocalDate.ofInstant(now, ZONE);

        createOrganisation();
        createClients();
        createProjects();
        logTime();
        calibrateBudgets();
        settleBudgetAlerts();
        createLooseEnds();
        createBaseline();
        createTimesheets();
        createConversation();
        LOG.infof("Seeded demo data in %d ms (%d tasks, %d time entries)", System.currentTimeMillis() - started,
                Task.count(), TimeEntry.count());
    }

    // --- people -----------------------------------------------------------------------------

    private void createOrganisation() {
        String hash = hasher.hash(DEMO_PASSWORD);
        user("maya", DEMO_EMAIL, "Maya Collins", hash);
        user("priya", "priya@example.com", "Priya Shah", hash);
        user("tom", "tom@example.com", "Tom Becker", hash);
        user("lena", "lena@example.com", "Lena Novak", hash);
        user("samir", "samir@example.com", "Samir Haddad", hash);
        user("jordan", "jordan@northwind.example", "Jordan Reyes", hash);

        org = new Organisation();
        org.name = "Brightline Studio";
        org.currency = "EUR";
        org.timezone = ZONE.getId();
        org.defaultHourlyRate = BigDecimal.valueOf(80);
        org.createdAt = now.minusSeconds(400L * 86400);
        org.persist();

        rate(Role.MANAGER, 95);
        rate(Role.MEMBER, 75);

        member("maya", Role.OWNER, null, 40);
        member("priya", Role.MANAGER, BigDecimal.valueOf(100), 40);
        member("tom", Role.MEMBER, BigDecimal.valueOf(70), 40);
        member("lena", Role.MEMBER, BigDecimal.valueOf(85), 32);
        member("samir", Role.MEMBER, null, 40);
    }

    private void user(String key, String email, String name, String hash) {
        User user = new User();
        user.email = email;
        user.name = name;
        user.passwordHash = hash;
        user.createdAt = now.minusSeconds(390L * 86400);
        user.persist();
        users.put(key, user);
    }

    private void rate(Role role, int amount) {
        RoleRate rate = new RoleRate();
        rate.organisationId = org.id;
        rate.role = role;
        rate.hourlyRate = BigDecimal.valueOf(amount);
        rate.persist();
    }

    private Membership member(String key, Role role, BigDecimal hourlyRate, int capacity) {
        Membership m = Membership.create(org.id, users.get(key).id, role, now.minusSeconds(380L * 86400));
        m.hourlyRate = hourlyRate;
        m.weeklyCapacityHours = BigDecimal.valueOf(capacity);
        return m;
    }

    private void createClients() {
        client("northwind", "Northwind Traders", "Jordan Reyes", "jordan@northwind.example");
        client("acme", "Acme Outdoor", "Ruth Okafor", "ruth@acme-outdoor.example");
        client("globex", "Globex Health", "Daniel Weiss", "daniel.weiss@globex.example");
        client("initech", "Initech", "Bill Lumbergh", "bill@initech.example").archived = true;

        Membership viewer = member("jordan", Role.CLIENT, null, 0);
        viewer.clientId = clients.get("northwind").id;
    }

    private Client client(String key, String name, String contact, String email) {
        Client client = new Client();
        client.organisationId = org.id;
        client.name = name;
        client.contactName = contact;
        client.contactEmail = email;
        client.createdAt = now.minusSeconds(370L * 86400);
        client.persist();
        clients.put(key, client);
        return client;
    }

    // --- projects ---------------------------------------------------------------------------

    private void createProjects() {
        initechIntranet();
        northwindWebsite();
        globexPortal();
        acmeBookingApp();
        designSystem();
        northwindBrand();
        acmeMarketingSite();
        studioOperations();
    }

    /** Where non-project time goes: planning, reviews, meetings, training. */
    private void studioOperations() {
        Project p = project(null, "Studio operations", "INT-OPS", ProjectStatus.ACTIVE, "#475569",
                "Internal time that isn't for a client: planning, reviews, studio meetings and training.",
                -370, 0, 0);
        p.endDate = null;
    }

    private void initechIntranet() {
        Plan plan = new Plan(project("initech", "Initech intranet", "INIT-NET", ProjectStatus.DONE, "#8a6d3b",
                "Replace the old SharePoint intranet with a simple staff portal: news, directory and document search.",
                -360, 340, 52000));
        plan.phase("Discovery");
        int brief = plan.task("Stakeholder interviews", 0, 5, 20, "maya");
        int audit = plan.task("Content audit", 2, 6, 24, "tom");
        plan.milestone("Discovery sign-off", brief, audit);
        plan.phase("Build");
        int design = plan.task("Page templates", 0, 8, 40, "tom", audit);
        int dir = plan.task("Staff directory with LDAP sync", 0, 10, 60, "samir", brief);
        int search = plan.task("Document search", 0, 12, 70, "lena", brief);
        int news = plan.task("News and announcements", 0, 6, 32, "samir", design);
        plan.phase("Launch");
        int uat = plan.task("User acceptance testing", 0, 5, 24, "priya", dir, search, news);
        int migrate = plan.task("Migrate legacy documents", 0, 4, 20, "lena", search);
        plan.milestone("Go live", uat, migrate);
        plan.schedule(-360, 2.8, s -> TaskStatus.DONE);
    }

    private void northwindWebsite() {
        Plan plan = new Plan(project("northwind", "Northwind website redesign", "NW-WEB", ProjectStatus.DONE, "#2f9e72",
                "New marketing site and product catalogue on a headless CMS, with a store locator.",
                -320, 600, 48000));
        plan.phase("Discovery");
        int research = plan.task("User research and analytics review", 0, 6, 30, "priya");
        int ia = plan.task("Information architecture", 0, 5, 24, "tom", research);
        plan.milestone("Sitemap approved", ia);
        plan.phase("Design");
        int wire = plan.task("Wireframes", 0, 8, 44, "tom", ia);
        int visual = plan.task("Visual design", 0, 10, 60, "tom", wire);
        int proto = plan.task("Clickable prototype", 0, 4, 18, "tom", visual);
        plan.milestone("Design sign-off", proto);
        plan.phase("Build");
        int cms = plan.task("CMS content model", 0, 6, 36, "samir", wire);
        int front = plan.task("Front-end templates", 0, 15, 110, "lena", visual);
        int catalogue = plan.task("Product catalogue import", 0, 8, 56, "samir", cms);
        int locator = plan.task("Store locator", 0, 6, 40, "samir", cms);
        int content = plan.task("Content migration", 0, 8, 48, "priya", cms);
        plan.phase("Launch");
        int qa = plan.task("QA and accessibility audit", 0, 6, 40, "lena", front, catalogue, locator);
        int seo = plan.task("Redirects and SEO checks", 0, 3, 14, "samir", content);
        plan.milestone("Launch", qa, seo);
        plan.schedule(-320, 1.7, s -> TaskStatus.DONE);
    }

    private void globexPortal() {
        Plan plan = new Plan(project("globex", "Globex patient portal", "GLX-PORTAL", ProjectStatus.ACTIVE, "#c2410c",
                "Patient-facing portal for appointments, lab results and secure messages, integrated with the clinic system.",
                -190, 820, 79000));
        plan.p.hourlyRate = BigDecimal.valueOf(90);
        plan.phase("Discovery");
        int workshops = plan.task("Requirements workshops", 0, 6, 36, "priya");
        int compliance = plan.task("Data protection review", 2, 6, 24, "maya");
        plan.milestone("Scope agreed", workshops, compliance);
        plan.phase("Design");
        int flows = plan.task("Patient journeys and flows", 0, 8, 40, "tom", workshops);
        int ui = plan.task("UI design", 0, 12, 70, "tom", flows);
        plan.phase("Integration");
        int api = plan.task("Clinic system API client", 0, 15, 110, "samir", workshops);
        int sso = plan.task("Single sign-on with the patient ID provider", 0, 10, 64, "samir", api);
        int appts = plan.task("Appointment booking", 0, 14, 96, "lena", api, ui);
        int labs = plan.task("Lab results view", 0, 10, 60, "lena", appts);
        int messages = plan.task("Secure messaging", 0, 12, 80, "samir", sso);
        int notif = plan.task("Email and SMS reminders", 0, 6, 34, "lena", appts);
        plan.phase("Go live");
        int pen = plan.task("Penetration test fixes", 0, 6, 40, "samir", messages, labs);
        int pilot = plan.task("Pilot with two clinics", 0, 10, 30, "priya", pen, notif);
        plan.milestone("Public launch", pilot);
        // Behind schedule: a lot of work that should be done isn't.
        plan.schedule(-190, 2.2, s -> {
            if (s.due.isBefore(today.minusDays(25))) {
                return TaskStatus.DONE;
            }
            if (!s.start.isAfter(today)) {
                return random.nextInt(3) == 0 ? TaskStatus.IN_REVIEW : TaskStatus.IN_PROGRESS;
            }
            return TaskStatus.TODO;
        });
        plan.subtasks(api, "Patient lookup endpoint", "Appointments endpoint", "Results endpoint", "Retry and timeout handling");
        plan.subtasks(appts, "Pick a clinic and doctor", "Available slots calendar", "Cancel and reschedule");
    }

    private void acmeBookingApp() {
        Plan plan = new Plan(project("acme", "Acme booking app", "ACME-APP", ProjectStatus.ACTIVE, "#4f6bed",
                "iOS and Android app for booking guided hikes and gear rental, with offline trail maps.",
                -110, 900, 76500));
        plan.phase("Discovery");
        int kickoff = plan.task("Kick-off and goals", 0, 2, 10, "maya");
        int research = plan.task("Competitor and user research", 0, 6, 30, "tom", kickoff);
        plan.milestone("Discovery complete", research);
        plan.phase("Design");
        int flows = plan.task("Booking flow wireframes", 0, 7, 36, "tom", research);
        int brand = plan.task("App visual language", 0, 8, 44, "tom", research);
        int proto = plan.task("Prototype testing with five users", 0, 5, 22, "priya", flows, brand);
        plan.milestone("Design sign-off", proto);
        plan.phase("Build");
        int setup = plan.task("Project setup and CI", 0, 3, 16, "samir", kickoff);
        int auth = plan.task("Accounts and sign-in", 0, 6, 40, "samir", setup);
        int catalog = plan.task("Tours catalogue", 0, 8, 56, "lena", setup, flows);
        int booking = plan.task("Booking and payments", 0, 12, 90, "samir", auth, catalog, proto);
        int rental = plan.task("Gear rental", 0, 8, 54, "lena", booking);
        int maps = plan.task("Offline trail maps", 0, 12, 80, "lena", catalog);
        int push = plan.task("Push notifications", 0, 4, 24, "samir", booking);
        plan.phase("Release");
        int beta = plan.task("Beta with Acme staff", 0, 8, 30, "priya", rental, maps, push);
        int store = plan.task("App store submission", 0, 3, 12, "samir", beta);
        plan.milestone("Launch", store);
        plan.schedule(-110, 2.4, s -> {
            if (s.due.isBefore(today.minusDays(2))) {
                // Two tasks slipped past their due date.
                return s.title.equals("Accounts and sign-in") || s.title.equals("Tours catalogue")
                        ? TaskStatus.IN_PROGRESS : TaskStatus.DONE;
            }
            if (!s.start.isAfter(today)) {
                return TaskStatus.IN_PROGRESS;
            }
            return TaskStatus.TODO;
        });
        // Push notifications were pulled forward to get a head start while payments are still
        // being built. Nothing stops that, so the plan has one dependency that does not hold.
        plan.startEarly(push, 3);
        plan.subtasks(booking, "Stripe payment sheet", "Booking confirmation screen", "Refunds and cancellations");
        plan.subtasks(maps, "Tile download manager", "GPS track overlay");
        plan.priority(booking, Priority.URGENT);
        plan.priority(maps, Priority.HIGH);
        plan.priority(auth, Priority.HIGH);
    }

    private void designSystem() {
        Plan plan = new Plan(project(null, "Studio design system", "INT-DS", ProjectStatus.ACTIVE, "#7c3aed",
                "Shared component library and tokens for all client work. Internal and non-billable.",
                -80, 240, 0));
        plan.p.budgetAmount = null;
        plan.phase("Foundations");
        int tokens = plan.task("Colour, type and spacing tokens", 0, 5, 20, "tom");
        int icons = plan.task("Icon set", 0, 6, 18, "tom", tokens);
        plan.phase("Components");
        int forms = plan.task("Form components", 0, 8, 40, "lena", tokens);
        int tables = plan.task("Data tables", 0, 6, 30, "samir", forms);
        int docs = plan.task("Documentation site", 0, 8, 32, "lena", forms);
        plan.milestone("v1 release", icons, tables, docs);
        plan.schedule(-80, 3.5, s -> {
            if (s.due.isBefore(today)) {
                return TaskStatus.DONE;
            }
            return s.start.isAfter(today) ? TaskStatus.TODO : TaskStatus.IN_PROGRESS;
        });
    }

    private void northwindBrand() {
        Plan plan = new Plan(project("northwind", "Northwind brand refresh", "NW-BRAND", ProjectStatus.ON_HOLD, "#0e7490",
                "Updated logo, packaging guidelines and a brand book. Paused while Northwind reorganises its marketing team.",
                -55, 220, 19000));
        plan.phase("Research");
        int audit = plan.task("Brand audit", 0, 5, 24, "tom");
        int interviews = plan.task("Customer interviews", 0, 6, 20, "priya");
        plan.phase("Concepts");
        int concepts = plan.task("Logo concepts", 0, 10, 50, "tom", audit, interviews);
        int packaging = plan.task("Packaging guidelines", 0, 8, 40, "tom", concepts);
        int book = plan.task("Brand book", 0, 6, 30, "tom", packaging);
        plan.milestone("Brand launch", book);
        plan.schedule(-55, 2.2, s -> s.due.isBefore(today.minusDays(14)) ? TaskStatus.DONE
                : s.start.isAfter(today) ? TaskStatus.TODO : TaskStatus.IN_PROGRESS);
    }

    private void acmeMarketingSite() {
        Plan plan = new Plan(project("acme", "Acme summer campaign site", "ACME-MKT", ProjectStatus.PLANNED, "#d97706",
                "Campaign microsite for the summer tour season, launching with the booking app.",
                20, 260, 21000));
        plan.phase("Plan");
        int brief = plan.task("Campaign brief", 0, 3, 8, "maya");
        int copy = plan.task("Copywriting", 0, 6, 30, "priya", brief);
        int design = plan.task("Page design", 0, 8, 40, "tom", brief);
        plan.phase("Build");
        int build = plan.task("Build and CMS setup", 0, 8, 50, "lena", design);
        int tracking = plan.task("Analytics and tracking", 0, 2, 8, "samir", build);
        plan.milestone("Campaign live", build, copy, tracking);
        plan.schedule(20, 2.4, s -> TaskStatus.TODO);
    }

    private Project project(String clientKey, String name, String code, ProjectStatus status, String color,
                            String description, int startOffset, int budgetHours, int budgetAmount) {
        Project p = new Project();
        p.organisationId = org.id;
        p.clientId = clientKey == null ? null : clients.get(clientKey).id;
        p.name = name;
        p.code = code;
        p.status = status;
        p.color = color;
        p.description = description;
        p.startDate = CAL.onOrAfter(today.plusDays(startOffset));
        p.budgetHours = budgetHours > 0 ? BigDecimal.valueOf(budgetHours) : null;
        p.budgetAmount = budgetAmount > 0 ? BigDecimal.valueOf(budgetAmount) : null;
        p.createdAt = now.minusSeconds(Math.max(1, 30 - startOffset) * 86400L);
        p.updatedAt = p.createdAt;
        p.persist();
        return p;
    }

    /** Builds a project's phases and tasks, then lays them out so every dependency holds. */
    private final class Plan {
        final Project p;
        final List<SeedTask> tasks = new ArrayList<>();
        final List<Phase> phases = new ArrayList<>();

        Plan(Project p) {
            this.p = p;
        }

        void phase(String name) {
            Phase phase = new Phase();
            phase.projectId = p.id;
            phase.name = name;
            phase.position = phases.size();
            phase.persist();
            phases.add(phase);
        }

        int task(String title, int offset, int duration, int estimate, String assignee, int... deps) {
            SeedTask t = new SeedTask(p, phases.getLast(), title, offset, duration, estimate, assignee, deps, false);
            tasks.add(t);
            return tasks.size() - 1;
        }

        int milestone(String title, int... deps) {
            SeedTask t = new SeedTask(p, phases.getLast(), title, 0, 1, 0, null, deps, true);
            tasks.add(t);
            return tasks.size() - 1;
        }

        void priority(int index, Priority priority) {
            tasks.get(index).entity.priority = priority;
        }

        /** Moves a scheduled task some working days earlier and leaves what it waits for where it is. */
        void startEarly(int index, int workingDays) {
            SeedTask t = tasks.get(index);
            t.start = CAL.addWorkingDays(t.start, -workingDays);
            t.due = CAL.addWorkingDays(t.due, -workingDays);
            t.entity.startDate = t.start;
            t.entity.dueDate = t.due;
        }

        void subtasks(int index, String... titles) {
            SeedTask parent = tasks.get(index);
            double each = parent.estimate / (double) titles.length;
            LocalDate d = parent.start;
            int days = Math.max(1, CAL.workingDaysBetween(parent.start, parent.due) / titles.length);
            for (int i = 0; i < titles.length; i++) {
                Task sub = new Task();
                sub.projectId = p.id;
                sub.phaseId = parent.entity.phaseId;
                sub.parentId = parent.entity.id;
                sub.title = titles[i];
                sub.estimateHours = BigDecimal.valueOf(Math.round(each * 2) / 2.0);
                sub.assigneeId = parent.entity.assigneeId;
                sub.startDate = d;
                sub.dueDate = i == titles.length - 1 ? parent.due : CAL.addWorkingDays(d, days - 1);
                if (sub.dueDate.isBefore(sub.startDate)) {
                    sub.dueDate = sub.startDate;
                }
                sub.status = parent.entity.status == TaskStatus.DONE || sub.dueDate.isBefore(today) ? TaskStatus.DONE
                        : sub.startDate.isAfter(today) ? TaskStatus.TODO : TaskStatus.IN_PROGRESS;
                sub.completedAt = sub.status == TaskStatus.DONE ? atNoon(sub.dueDate) : null;
                sub.position = i;
                sub.createdAt = parent.entity.createdAt;
                sub.updatedAt = parent.entity.updatedAt;
                sub.persist();
                d = CAL.nextWorkingDay(sub.dueDate);
                if (d.isAfter(parent.due)) {
                    d = parent.due;
                }
            }
        }

        /** Lays tasks out in order, each starting after its dependencies, and saves them. */
        void schedule(int projectStartOffset, double scale, java.util.function.Function<SeedTask, TaskStatus> status) {
            LocalDate projectStart = CAL.onOrAfter(today.plusDays(projectStartOffset));
            Map<Integer, Integer> perStatus = new HashMap<>();
            LocalDate end = projectStart;
            for (SeedTask t : tasks) {
                LocalDate start = CAL.addWorkingDays(projectStart, (int) Math.round(t.offset * scale));
                for (int dep : t.deps) {
                    LocalDate after = CAL.nextWorkingDay(tasks.get(dep).due);
                    if (after.isAfter(start)) {
                        start = after;
                    }
                }
                t.start = start;
                t.due = t.milestone ? start : CAL.addWorkingDays(start, Math.max(1, (int) Math.round(t.duration * scale)) - 1);
                if (t.due.isAfter(end)) {
                    end = t.due;
                }
            }
            for (SeedTask t : tasks) {
                Task e = t.entity;
                e.startDate = t.start;
                e.dueDate = t.due;
                e.status = status.apply(t);
                e.completedAt = e.status == TaskStatus.DONE ? atNoon(t.due) : null;
                e.position = perStatus.merge(e.status.ordinal(), 1, Integer::sum) - 1;
                e.createdAt = atNoon(projectStart.minusDays(7));
                e.updatedAt = e.completedAt != null ? e.completedAt : now.minusSeconds(86400L * (1 + random.nextInt(5)));
                e.persist();
            }
            for (SeedTask t : tasks) {
                for (int dep : t.deps) {
                    TaskDependency d = new TaskDependency();
                    d.predecessorId = tasks.get(dep).entity.id;
                    d.successorId = t.entity.id;
                    d.createdAt = t.entity.createdAt;
                    d.persist();
                }
            }
            p.startDate = projectStart;
            p.endDate = p.status == ProjectStatus.ACTIVE && p.code.equals("GLX-PORTAL")
                    // The client's deadline, which the plan no longer meets.
                    ? CAL.addWorkingDays(end, -12)
                    : end;
            seedTasks.addAll(tasks);
        }
    }

    private final class SeedTask {
        final Project project;
        final String title;
        final int offset;
        final int duration;
        final int estimate;
        final String assignee;
        final int[] deps;
        final boolean milestone;
        final Task entity = new Task();
        LocalDate start;
        LocalDate due;

        SeedTask(Project project, Phase phase, String title, int offset, int duration, int estimate, String assignee,
                 int[] deps, boolean milestone) {
            this.project = project;
            this.title = title;
            this.offset = offset;
            this.duration = duration;
            this.estimate = estimate;
            this.assignee = assignee;
            this.deps = deps;
            this.milestone = milestone;
            entity.projectId = project.id;
            entity.phaseId = phase.id;
            entity.title = title;
            entity.milestone = milestone;
            entity.estimateHours = milestone ? null : BigDecimal.valueOf(estimate);
            entity.assigneeId = assignee == null ? null : users.get(assignee).id;
            entity.priority = Priority.MEDIUM;
        }
    }

    // --- time -------------------------------------------------------------------------------

    /**
     * A working year for each person: on each working day they log time against
     * the tasks they had on that day, with a couple of holidays and some
     * non-billable admin time.
     */
    private void logTime() {
        Project operations = project("INT-OPS");
        LocalDate first = today.minusDays(365);
        for (String key : List.of("maya", "priya", "tom", "lena", "samir")) {
            User user = users.get(key);
            List<LocalDate> holidays = holidays(key);
            for (LocalDate d = first; d.isBefore(today); d = d.plusDays(1)) {
                LocalDate day = d;
                if (!CAL.isWorkingDay(day) || holidays.stream().anyMatch(h -> !day.isBefore(h) && day.isBefore(h.plusDays(10)))) {
                    continue;
                }
                List<SeedTask> active = seedTasks.stream()
                        .filter(t -> !t.milestone && key.equals(t.assignee))
                        .filter(t -> !day.isBefore(t.start) && !day.isAfter(t.due))
                        .filter(t -> t.project.status != ProjectStatus.ON_HOLD || day.isBefore(today.minusDays(14)))
                        .toList();
                int budget = key.equals("lena") ? 390 : key.equals("maya") ? 240 : 450;
                budget += random.nextInt(4) * 15 - 30;
                if (!active.isEmpty()) {
                    int slices = Math.min(active.size(), 1 + random.nextInt(2));
                    for (int i = 0; i < slices && budget > 0; i++) {
                        SeedTask t = active.get(random.nextInt(active.size()));
                        int minutes = i == slices - 1 ? budget : roundTo15(budget * (0.4 + random.nextDouble() * 0.3));
                        entry(user, t.project, t.entity, day, minutes, describe(t), t.project.clientId != null);
                        budget -= minutes;
                    }
                }
                if (key.equals("maya") || key.equals("priya")) {
                    // Account management, sales and reviews.
                    entry(user, operations, null, day, 60 + 15 * random.nextInt(5), "Team planning and reviews", false);
                } else if (active.isEmpty()) {
                    entry(user, operations, null, day, Math.max(60, budget), BENCH_WORK[random.nextInt(BENCH_WORK.length)], false);
                } else if (random.nextInt(5) == 0) {
                    entry(user, operations, null, day, 30, "Studio meeting", false);
                }
            }
        }
    }

    /**
     * Sets each project's budget from the time actually logged, so burn tells the
     * intended story (a finished project slightly over, an active one burning ahead of
     * progress, ...), and scales task estimates to match the budget.
     */
    private void calibrateBudgets() {
        Map<String, Double> burn = Map.of("INIT-NET", 1.04, "NW-WEB", 0.94, "GLX-PORTAL", 1.06,
                "ACME-APP", 0.55, "INT-DS", 0.58, "NW-BRAND", 0.45);
        for (var target : burn.entrySet()) {
            Project p = project(target.getKey());
            long minutes = TimeEntry.<TimeEntry>list("projectId", p.id).stream().mapToLong(e -> e.minutes).sum();
            double budget = Math.max(40, Math.round(minutes / 60.0 / target.getValue() / 10) * 10.0);
            p.budgetHours = BigDecimal.valueOf(budget);
            if (p.budgetAmount != null) {
                p.budgetAmount = BigDecimal.valueOf(Math.round(budget * 88 / 500) * 500L);
            }
            List<Task> tasks = Task.list("projectId", p.id);
            double estimated = tasks.stream().filter(t -> t.parentId == null).mapToDouble(Task::estimate).sum();
            if (estimated <= 0) {
                continue;
            }
            double factor = budget * 0.9 / estimated;
            for (Task t : tasks) {
                if (t.estimateHours != null) {
                    t.estimateHours = BigDecimal.valueOf(Math.max(1, Math.round(t.estimate() * factor * 2) / 2.0));
                }
            }
        }
    }

    /**
     * The projects arrive with a year of time on them. Their alert levels start where their
     * burn is, so that the next hour logged does not announce something that happened months
     * ago. Only the patient portal, which is over its budget, has told the managers, two days ago.
     */
    private void settleBudgetAlerts() {
        for (Project p : Project.<Project>list("organisationId = ?1 order by id", org.id)) {
            if (p.code.equals("GLX-PORTAL")) {
                budgetAlerts.check(p);
            } else {
                budgetAlerts.settle(p);
            }
        }
        Notification.update("createdAt = ?1 where organisationId = ?2 and type = ?3",
                now.minusSeconds(2 * 86400L + 5400), org.id, NotificationType.BUDGET_ALERT);
    }

    /** A few things on the owner's own plate, so the dashboard has something to say. */
    private void createLooseEnds() {
        Project globex = project("GLX-PORTAL");
        Project ops = project("INT-OPS");
        Project acme = project("ACME-APP");
        looseEnd(globex, "Send change request for the extra lab integration", CAL.addWorkingDays(today, -3), 3, Priority.HIGH);
        looseEnd(ops, "Prepare quarterly review for the team", CAL.addWorkingDays(today, 2), 4, Priority.MEDIUM);
        looseEnd(acme, "Agree beta test group with Ruth", CAL.addWorkingDays(today, 4), 2, Priority.MEDIUM);
    }

    /**
     * The Acme plan as it was signed off at the kick-off: build and release were planned a
     * week earlier than they are now, so the timeline has grey bars to the left of the real
     * ones and the overview a launch that is late. Written as rows, the way a baseline that
     * was taken back then would have stored them. The loose ends are newer, so they are added since.
     */
    private void createBaseline() {
        Project acme = project("ACME-APP");
        Instant takenAt = now.minusSeconds(100 * 86400L);
        Baseline baseline = new Baseline();
        baseline.projectId = acme.id;
        baseline.name = "Signed off at kick-off";
        baseline.reference = true;
        baseline.projectStart = acme.startDate;
        baseline.projectEnd = acme.endDate;
        baseline.createdBy = users.get("maya").id;
        baseline.createdAt = takenAt;
        baseline.persist();

        Map<Long, String> phaseNames = new HashMap<>();
        for (Phase phase : Phase.forProject(acme.id)) {
            phaseNames.put(phase.id, phase.name);
            BaselinePhase then = new BaselinePhase();
            then.baselineId = baseline.id;
            then.phaseId = phase.id;
            then.name = phase.name;
            then.startDate = planned(phase.name, phase.startDate);
            then.endDate = planned(phase.name, phase.endDate);
            then.persist();
        }
        for (Task task : Task.<Task>list("projectId", acme.id)) {
            if (task.createdAt.isAfter(takenAt)) {
                continue;
            }
            String phase = phaseNames.get(task.phaseId);
            BaselineTask then = new BaselineTask();
            then.baselineId = baseline.id;
            then.taskId = task.id;
            then.parentTaskId = task.parentId;
            then.phaseId = task.phaseId;
            then.phaseName = phase;
            then.title = task.title;
            then.startDate = planned(phase, task.startDate);
            then.dueDate = planned(phase, task.dueDate);
            then.estimateHours = task.estimateHours;
            then.milestone = task.milestone;
            then.persist();
        }
    }

    /** A date as the signed-off plan had it: a week earlier in build and release, the same before. */
    private static LocalDate planned(String phase, LocalDate date) {
        boolean slipped = "Build".equals(phase) || "Release".equals(phase);
        return date == null || !slipped ? date : CAL.addWorkingDays(date, -5);
    }

    private void looseEnd(Project project, String title, LocalDate due, int estimate, Priority priority) {
        Task t = new Task();
        t.projectId = project.id;
        t.title = title;
        t.assigneeId = users.get("maya").id;
        t.priority = priority;
        t.estimateHours = BigDecimal.valueOf(estimate);
        t.startDate = CAL.addWorkingDays(due, -1);
        t.dueDate = due;
        t.status = due.isBefore(today) ? TaskStatus.IN_PROGRESS : TaskStatus.TODO;
        t.position = 99;
        t.createdAt = now.minusSeconds(6 * 86400L);
        t.updatedAt = t.createdAt;
        t.persist();
    }

    private static final String[] BENCH_WORK = {"Training and research", "Pitch preparation", "Internal tooling", "Portfolio write-up"};

    private List<LocalDate> holidays(String key) {
        int base = switch (key) {
            case "maya" -> 200;
            case "priya" -> 150;
            case "tom" -> 100;
            case "lena" -> 240;
            default -> 60;
        };
        return List.of(today.minusDays(base), today.minusDays(base + 120 + key.length() * 7));
    }

    private String describe(SeedTask t) {
        String[] verbs = {"", "Worked on ", "Continued ", "Review: ", "Fixes for "};
        String verb = verbs[random.nextInt(verbs.length)];
        return verb.isEmpty() ? null : verb + t.title.substring(0, 1).toLowerCase() + t.title.substring(1);
    }

    private void entry(User user, Project project, Task task, LocalDate date, int minutes, String description, boolean billable) {
        if (minutes <= 0) {
            return;
        }
        TimeEntry e = new TimeEntry();
        e.organisationId = org.id;
        e.userId = user.id;
        e.projectId = project.id;
        e.taskId = task == null ? null : task.id;
        e.entryDate = date;
        e.minutes = minutes;
        e.description = description;
        e.billable = billable;
        e.createdAt = atNoon(date).plusSeconds(6 * 3600);
        e.persist();
    }

    private static int roundTo15(double minutes) {
        return (int) Math.max(15, Math.round(minutes / 15) * 15);
    }

    /** Old weeks are approved; last week is waiting for review, with one sent back. */
    private void createTimesheets() {
        LocalDate thisWeek = WorkCalendar.weekStart(today);
        LocalDate firstWeek = WorkCalendar.weekStart(today.minusDays(365));
        User maya = users.get("maya");
        for (String key : List.of("maya", "priya", "tom", "lena", "samir")) {
            User user = users.get(key);
            for (LocalDate week = firstWeek; week.isBefore(thisWeek); week = week.plusWeeks(1)) {
                long entries = TimeEntry.count("userId = ?1 and entryDate between ?2 and ?3", user.id, week, week.plusDays(6));
                if (entries == 0) {
                    continue;
                }
                boolean lastWeek = week.equals(thisWeek.minusWeeks(1));
                Timesheet sheet = new Timesheet();
                sheet.organisationId = org.id;
                sheet.userId = user.id;
                sheet.weekStart = week;
                sheet.submittedAt = atNoon(week.plusDays(4)).plusSeconds(5 * 3600);
                if (lastWeek && key.equals("samir")) {
                    sheet.status = TimesheetStatus.REJECTED;
                    sheet.reviewedAt = now.minusSeconds(3 * 3600);
                    sheet.reviewedBy = users.get("priya").id;
                    sheet.reviewNote = "Tuesday has 3 hours on the booking flow that belong to Globex, can you move them?";
                } else if (lastWeek && key.equals("tom")) {
                    sheet.status = TimesheetStatus.DRAFT;
                    sheet.submittedAt = null;
                } else if (lastWeek && !key.equals("maya")) {
                    sheet.status = TimesheetStatus.SUBMITTED;
                } else {
                    sheet.status = TimesheetStatus.APPROVED;
                    sheet.reviewedAt = sheet.submittedAt.plusSeconds(2 * 86400);
                    sheet.reviewedBy = key.equals("maya") ? users.get("priya").id : maya.id;
                }
                sheet.persist();
            }
        }
    }

    // --- comments, notifications, activity ---------------------------------------------------

    private void createConversation() {
        Task booking = task("ACME-APP", "Booking and payments");
        Task auth = task("ACME-APP", "Accounts and sign-in");
        Task appts = task("GLX-PORTAL", "Appointment booking");
        Task messaging = task("GLX-PORTAL", "Secure messaging");
        Task concepts = task("NW-BRAND", "Logo concepts");

        comment(auth, "samir", 4, "Apple sign-in needs the new entitlement, waiting on Acme's developer account. @maya can you chase Ruth?");
        comment(auth, "maya", 3, "Emailed Ruth this morning, she says by Thursday.");
        comment(booking, "priya", 2, "@samir @lena Stripe confirmed we can use the payment sheet in test mode until the account review is done.");
        comment(booking, "samir", 1, "Great, I'll wire it up behind a feature flag so the beta isn't blocked.");
        comment(appts, "lena", 6, "The clinic API returns slots in local time without an offset. I've asked Daniel to confirm it's always Europe/Berlin.");
        comment(appts, "priya", 5, "@lena confirmed by Daniel, all clinics are in Germany. @maya this pushes the pilot by a week, I've updated the plan.");
        comment(messaging, "samir", 2, "Pen test found that attachments weren't virus scanned. Fixing before the pilot.");
        comment(concepts, "jordan", 20, "Thanks for the concepts! We're pausing until the new marketing lead starts, @priya I'll be in touch.");

        Project acme = project("ACME-APP");
        Project globex = project("GLX-PORTAL");
        User samir = users.get("samir");
        User priya = users.get("priya");
        LocalDate lastWeek = WorkCalendar.weekStart(today).minusWeeks(1);
        notification("maya", samir, NotificationType.MENTIONED, samir.name + " mentioned you on " + auth.title, acme, auth, null, 4, true);
        notification("maya", priya, NotificationType.MENTIONED, priya.name + " mentioned you on " + appts.title, globex, appts, null, 5, false);
        notification("maya", priya, NotificationType.TIMESHEET_SUBMITTED, priya.name + " submitted their timesheet for the week of "
                + lastWeek, null, null, lastWeek, 1, false);
        User lena = users.get("lena");
        notification("maya", lena, NotificationType.TIMESHEET_SUBMITTED, lena.name + " submitted their timesheet for the week of "
                + lastWeek, null, null, lastWeek, 1, false);
        notification("samir", priya, NotificationType.TIMESHEET_REJECTED, priya.name + " sent back your timesheet for the week of "
                + lastWeek, null, null, lastWeek, 0, false);
        // Tom's week is still a draft and Priya has reminded him today, so he can't be reminded again until tomorrow.
        Notification reminder = notification("tom", priya, NotificationType.TIMESHEET_REMINDER,
                priya.name + " asked you to submit your timesheet for the week of " + lastWeek, null, null, lastWeek, 0, false);
        Instant startOfToday = today.atStartOfDay(ZONE).toInstant();
        if (reminder.createdAt.isBefore(startOfToday)) {
            // Seeded just after midnight: an hour ago was yesterday, and yesterday's reminder would not count.
            reminder.createdAt = startOfToday;
        }

        long maya = users.get("maya").id;
        activity.record(org.id, globex.id, appts.id, priya.id, "task.rescheduled", "Rescheduled Appointment booking after the time zone question");
        activity.record(org.id, globex.id, null, priya.id, "task.cascade", "Pushed back 6 dependent tasks to keep dependencies in order");
        activity.record(org.id, acme.id, booking.id, samir.id, "task.status", "Moved Booking and payments to in progress");
        activity.record(org.id, acme.id, auth.id, maya, "comment.added", "Maya Collins commented on Accounts and sign-in");
        activity.record(org.id, null, null, maya, "timesheet.approved", "Approved timesheets for the week of " + WorkCalendar.weekStart(today).minusWeeks(2));
    }

    private Task task(String projectCode, String title) {
        return seedTasks.stream().filter(t -> t.project.code.equals(projectCode) && t.title.equals(title))
                .findFirst().orElseThrow().entity;
    }

    private Project project(String code) {
        return Project.find("organisationId = ?1 and code = ?2", org.id, code).firstResult();
    }

    private void comment(Task task, String author, int daysAgo, String body) {
        Comment c = new Comment();
        c.taskId = task.id;
        c.authorId = users.get(author).id;
        c.body = body;
        c.createdAt = now.minusSeconds(daysAgo * 86400L + random.nextInt(8 * 3600));
        c.persist();
    }

    private Notification notification(String to, User actor, NotificationType type, String message, Project project, Task task,
                                      LocalDate week, int daysAgo, boolean read) {
        if (week != null) {
            notifier.notifyAboutWeek(users.get(to).id, org.id, actor.id, type, message, week);
        } else {
            notifier.notify(users.get(to).id, org.id, actor.id, type, message, project == null ? null : project.id,
                    task == null ? null : task.id);
        }
        Notification n = Notification.<Notification>find("order by id desc").firstResult();
        n.createdAt = now.minusSeconds(daysAgo * 86400L + 3600);
        n.readAt = read ? n.createdAt.plusSeconds(1800) : null;
        return n;
    }

    private Instant atNoon(LocalDate date) {
        return date.atTime(12, 0).atZone(ZONE).toInstant();
    }
}
