import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ProjectListItem } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { ProjectsPage } from './projects.page';

function item(id: number, name: string, code: string, status: 'ACTIVE' | 'DONE'): ProjectListItem {
  return {
    project: { id, organisationId: 1, name, code, status, color: '#4f6bed', createdAt: '', updatedAt: '' },
    health: { status: 'ON_TRACK', progressPct: 0, overdueTasks: 0, openTasks: 0, scheduleVarianceHours: 0 },
  };
}

const website = item(4, 'Website', 'WEB', 'ACTIVE');
const intranet = item(5, 'Intranet', 'NET', 'DONE');

describe('ProjectsPage, copying a plan', () => {
  it('offers every project to copy from, not only the ones the filters leave in the list', async () => {
    TestBed.configureTestingModule({
      imports: [ProjectsPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: OrgContext, useValue: { orgId: () => 1, id: () => 1, canManage: () => true } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ProjectsPage);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    http.expectOne((r) => r.url === '/api/orgs/1/projects' && !r.params.has('status')).flush([website, intranet]);
    http.expectOne((r) => r.url === '/api/orgs/1/clients').flush([]);
    await fixture.whenStable();

    // The list is narrowed to active projects.
    const status = el.querySelector<HTMLSelectElement>('.filters select')!;
    status.value = 'ACTIVE';
    status.dispatchEvent(new Event('change'));
    http.expectOne((r) => r.url === '/api/orgs/1/projects' && r.params.get('status') === 'ACTIVE').flush([website]);
    await fixture.whenStable();
    expect(el.querySelectorAll('tbody tr')).toHaveLength(1);

    // A finished project is as good a template as an active one.
    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('New project'))!.click();
    http.expectOne((r) => r.url === '/api/orgs/1/projects' && r.params.keys().length === 0).flush([website, intranet]);
    await fixture.whenStable();
    const options = [...el.querySelectorAll('#np-copy option')].map((o) => o.textContent?.trim());
    expect(options).toEqual(['Nothing, start empty', 'Website (WEB)', 'Intranet (NET)']);
    expect(el.querySelectorAll('tbody tr')).toHaveLength(1);

    // Picking one swaps the full form for the copy form, which asks the API what to propose.
    const copy = el.querySelector<HTMLSelectElement>('#np-copy')!;
    copy.selectedIndex = 2;
    copy.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    http.expectOne('/api/projects/5/duplicate').flush({ name: 'Intranet (copy)', code: 'NET-2', startDate: '2026-06-10', phases: 1, tasks: 2, dependencies: 1, attachments: 0, attachmentBytes: 0 });
    await fixture.whenStable();
    expect(el.querySelector<HTMLInputElement>('#df-code')!.value).toBe('NET-2');
    expect(el.querySelector('app-project-form')).toBeNull();
    http.verify();
  });
});

describe('ProjectsPage, schedule issues', () => {
  it('marks a plan with issues and links the marker to its timeline', async () => {
    TestBed.configureTestingModule({
      imports: [ProjectsPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: OrgContext, useValue: { orgId: () => 1, id: () => 1, canManage: () => true } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ProjectsPage);
    await fixture.whenStable();
    const broken = { ...website, health: { ...website.health, scheduleIssues: 3 } };
    const fine = { ...intranet, health: { ...intranet.health, scheduleIssues: 0 } };
    http.expectOne((r) => r.url === '/api/orgs/1/projects').flush([broken, fine]);
    http.expectOne((r) => r.url === '/api/orgs/1/clients').flush([]);
    await fixture.whenStable();

    const markers = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>('.issue-marker');
    expect(markers).toHaveLength(1);
    expect(markers[0].textContent.trim()).toBe('3 schedule issues');
    expect(markers[0].getAttribute('href')).toBe('/projects/4/timeline');
  });
});
