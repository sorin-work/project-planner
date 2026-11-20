import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { UnsubscribePage } from './unsubscribe.page';

const TOKEN = 'dW5zdWJzY3JpYmU6NzpNRU5USU9ORUQ.aB3_-xYz0123456789abcdefghijklmnopqrstuvw';

describe('UnsubscribePage', () => {
  it('changes nothing until the button is pressed, then says what stopped', async () => {
    TestBed.configureTestingModule({
      imports: [UnsubscribePage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { fragment: `token=${TOKEN}` } } },
        { provide: Location, useValue: { replaceState: vi.fn() } },
      ],
    });
    const fixture = TestBed.createComponent(UnsubscribePage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    // Opening the link, as a mail scanner would, sends nothing.
    http.expectNone('/api/auth/unsubscribe');

    const el = fixture.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>('button')!.click();
    const request = http.expectOne('/api/auth/unsubscribe');
    expect(request.request.body).toEqual({ token: TOKEN });
    request.flush({ type: 'MENTIONED' });
    await fixture.whenStable();
    expect(el.querySelector('[role=status]')?.textContent).toContain('You no longer get mails about mentions');
  });
});
