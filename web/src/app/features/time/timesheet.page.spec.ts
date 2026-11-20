import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../core/auth.service';
import { Week } from '../../core/models';
import { today, weekStart } from '../../shared/dates';
import { TimesheetPage } from './timesheet.page';

const WEEK_URL = '/api/orgs/7/timesheets/week';

function week(start: string): Week {
  return { userId: 1, userName: 'Tom Becker', weekStart: start, status: 'DRAFT', entries: [], days: [], totalMinutes: 0, billableMinutes: 0 };
}

describe('TimesheetPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: 'time', component: TimesheetPage }])],
    });
    TestBed.inject(AuthService).me.set({
      user: { id: 1, email: 'tom@example.com', name: 'Tom Becker', handle: 'tom' },
      organisations: [{ organisationId: 7, organisationName: 'Brightline Studio', role: 'MEMBER', currency: 'EUR' }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  /** Answers the request for the week on screen and says which day it asked for. */
  async function answerWeek(harness: RouterTestingHarness): Promise<string> {
    await harness.fixture.whenStable();
    const request = http.expectOne((r) => r.url === WEEK_URL);
    const date = request.request.params.get('date')!;
    request.flush(week(date));
    await harness.fixture.whenStable();
    return date;
  }

  it('opens the week a link names and moves on from there', async () => {
    // A reminder links to the Monday; any day of the week does.
    const harness = await RouterTestingHarness.create('/time?week=2026-03-04');
    http.expectOne('/api/orgs/7/projects').flush([]);
    const el = harness.routeNativeElement as HTMLElement;

    expect(await answerWeek(harness)).toBe('2026-03-02');
    expect(el.querySelector('.week-label')?.textContent).toContain('2 Mar');
    // A new entry starts in the week on screen, not on today's date.
    expect(el.querySelector<HTMLInputElement>('#te-date')?.value).toBe('2026-03-02');

    el.querySelector<HTMLButtonElement>('[aria-label="Next week"]')!.click();
    expect(await answerWeek(harness)).toBe('2026-03-09');
    expect(TestBed.inject(Router).url).toBe('/time?week=2026-03-09');
    expect(el.querySelector<HTMLInputElement>('#te-date')?.value).toBe('2026-03-09');

    // The current week has no parameter, so /time keeps meaning "this week".
    [...el.querySelectorAll('button')].find((b) => b.textContent.trim() === 'This week')!.click();
    expect(await answerWeek(harness)).toBe(weekStart(today()));
    expect(TestBed.inject(Router).url).toBe('/time');
    http.verify();
  });
});
