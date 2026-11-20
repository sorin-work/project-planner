import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../../core/auth.service';
import { HolidaysCard } from './holidays.card';

describe('HolidaysCard', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(AuthService).me.set({
      user: { id: 5, email: 'maya@example.com', name: 'Maya Collins', handle: 'maya' },
      organisations: [{ organisationId: 7, organisationName: 'Brightline Studio', role: 'MANAGER', currency: 'EUR' }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists, adds and removes holidays, and says why one was refused', async () => {
    const fixture = TestBed.createComponent(HolidaysCard);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/orgs/7/holidays').flush([{ id: 1, date: '2026-12-25', name: 'Christmas' }]);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Christmas');

    const card = fixture.componentInstance as unknown as { date: string; name: string; add(): void };
    card.date = '2026-12-26';
    card.name = 'Boxing Day';
    card.add();
    const add = http.expectOne('/api/orgs/7/holidays');
    expect(add.request.body).toEqual({ date: '2026-12-26', name: 'Boxing Day' });
    add.flush({ id: 2, date: '2026-12-26', name: 'Boxing Day' });
    card.date = '2026-12-26';
    card.name = 'Again';
    card.add();
    http.expectOne('/api/orgs/7/holidays').flush(
      { status: 409, code: 'holiday_taken', message: 'There is already a holiday on 2026-12-26' },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(el.textContent).toContain('Boxing Day');
    expect(el.querySelector('[role="alert"]')!.textContent).toContain('There is already a holiday');

    el.querySelector<HTMLButtonElement>('button[aria-label="Remove Christmas"]')!.click();
    http.expectOne('/api/orgs/7/holidays/1').flush(null);
    await fixture.whenStable();
    expect(el.textContent).not.toContain('Christmas');
  });
});
