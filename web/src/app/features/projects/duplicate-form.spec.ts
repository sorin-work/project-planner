import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Client } from '../../core/models';
import { DuplicateForm } from './duplicate-form';

const clients: Client[] = [
  { id: 4, name: 'Acme', archived: false, projectCount: 2 },
  { id: 5, name: 'Globex', archived: false, projectCount: 1 },
];
const defaults = { name: 'Website (copy)', code: 'WEB-2', startDate: '2026-06-10', phases: 3, tasks: 6, dependencies: 2, attachments: 0, attachmentBytes: 0 };

describe('DuplicateForm', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<DuplicateForm>;
  let el: HTMLElement;
  const created = vi.fn();

  beforeEach(async () => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({ imports: [DuplicateForm], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DuplicateForm);
    fixture.componentRef.setInput('source', { id: 7, name: 'Website', clientId: 4 });
    fixture.componentRef.setInput('clients', clients);
    fixture.componentInstance.created.subscribe(created);
    await fixture.whenStable();
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => http.verify());

  const input = (id: string) => el.querySelector<HTMLInputElement>(`#${id}`)!;

  function type(id: string, value: string): void {
    input(id).value = value;
    input(id).dispatchEvent(new Event('input'));
  }

  async function proposed(): Promise<void> {
    http.expectOne('/api/projects/7/duplicate').flush(defaults);
    await fixture.whenStable();
  }

  async function submit(): Promise<void> {
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('fills in what the API proposes and copies neither the client nor the assignees unless asked', async () => {
    await proposed();
    expect(input('df-name').value).toBe('Website (copy)');
    expect(input('df-code').value).toBe('WEB-2');
    expect(input('df-start').value).toBe('2026-06-10');
    expect(input('df-assignees').checked).toBe(false);
    expect(el.textContent).toContain('Copies 3 phases, 6 tasks and 2 links from Website');
    const options = [...el.querySelectorAll('#df-client option')].map((o) => o.textContent?.trim());
    expect(options).toEqual(['Internal (no client)', 'Acme (as in the source)', 'Globex']);
    expect(el.querySelector<HTMLSelectElement>('#df-client')!.selectedIndex).toBe(0);

    await submit();
    const post = http.expectOne('/api/projects/7/duplicate');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ name: 'Website (copy)', code: 'WEB-2', clientId: null, startDate: '2026-06-10', keepAssignees: false, copyAttachments: false });
    post.flush({ id: 31, name: 'Website (copy)', code: 'WEB-2' });
    await fixture.whenStable();
    expect(created).toHaveBeenCalledWith(expect.objectContaining({ id: 31 }));
  });

  it('offers the files only when the source has some, and copies them only when ticked', async () => {
    expect(el.querySelector('#df-files')).toBeNull();
    http.expectOne('/api/projects/7/duplicate').flush({ ...defaults, attachments: 3, attachmentBytes: 2.1 * 1024 * 1024 });
    await fixture.whenStable();
    expect(el.textContent).toContain('Copy the 3 files (2.1 MB) attached to the tasks');
    expect(input('df-files').checked).toBe(false);
    input('df-files').click();
    await submit();
    expect(http.expectOne('/api/projects/7/duplicate').request.body).toEqual(expect.objectContaining({ copyAttachments: true }));
  });

  it('sends what was changed: another name and code, the client and the assignees', async () => {
    await proposed();
    type('df-name', 'Website for Globex');
    type('df-code', 'glx-web');
    type('df-start', '2026-09-01');
    const client = el.querySelector<HTMLSelectElement>('#df-client')!;
    client.selectedIndex = 2;
    client.dispatchEvent(new Event('change'));
    input('df-assignees').click();
    await submit();
    const post = http.expectOne('/api/projects/7/duplicate');
    expect(post.request.body).toEqual({ name: 'Website for Globex', code: 'GLX-WEB', clientId: 5, startDate: '2026-09-01', keepAssignees: true, copyAttachments: false });
    post.flush({ id: 32 });
  });

  it('shows that the code is taken and keeps the form as it was', async () => {
    await proposed();
    type('df-code', 'WEB');
    await submit();
    http.expectOne('/api/projects/7/duplicate').flush(
      { status: 409, code: 'code_taken', message: 'Another project already uses the code WEB' },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Another project already uses the code WEB');
    expect(input('df-code').value).toBe('WEB');
    expect(created).not.toHaveBeenCalled();
    expect(el.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(false);
  });

  it('asks for a start date and a valid code before sending anything', async () => {
    await proposed();
    type('df-start', '');
    type('df-code', 'not a code');
    await submit();
    expect(el.textContent).toContain('Pick the day the copied plan begins on');
    expect(el.textContent).toContain('Up to 16 letters, digits or dashes');
  });

  it('asks again when another project is picked, and ignores the answer for the one before', async () => {
    const first = http.expectOne('/api/projects/7/duplicate');
    fixture.componentRef.setInput('source', { id: 9, name: 'Portal' });
    await fixture.whenStable();
    http.expectOne('/api/projects/9/duplicate').flush({ ...defaults, name: 'Portal (copy)', code: 'PORTAL-2' });
    first.flush(defaults);
    await fixture.whenStable();
    expect(input('df-name').value).toBe('Portal (copy)');
    expect(input('df-code').value).toBe('PORTAL-2');
    expect(el.textContent).toContain('from Portal');
  });
});
