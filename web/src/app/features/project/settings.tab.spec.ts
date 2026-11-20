import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Baseline, Project } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { ProjectStore } from './project.store';
import { SettingsTab } from './settings.tab';

const agreed: Baseline = { id: 30, projectId: 7, name: 'Agreed', reference: true, createdAt: '2026-03-02T09:00:00Z', createdByName: 'Ann', tasks: 4 };
const comparison = {
  baseline: agreed, tasks: [], phases: [], projectEnd: { change: 'UNCHANGED' }, planEnd: { change: 'UNCHANGED' },
  counts: { unchanged: 4, slipped: 0, pulledIn: 0, added: 0, removed: 0 }, baselineEstimateHours: 0, currentEstimateHours: 0,
};

describe('SettingsTab baselines', () => {
  let http: HttpTestingController;
  let store: ProjectStore;
  let fixture: ComponentFixture<SettingsTab>;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [SettingsTab],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        ProjectStore,
        { provide: OrgContext, useValue: { orgId: () => 3, canManage: () => true, isTeam: () => true } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(ProjectStore);
    store.project.set({ id: 7, organisationId: 3, name: 'Site', code: 'SITE', status: 'ACTIVE' } as Project);
    fixture = TestBed.createComponent(SettingsTab);
    await fixture.whenStable();
    el = fixture.nativeElement as HTMLElement;
    http.expectOne((r) => r.url === '/api/orgs/3/clients').flush([]);
  });

  afterEach(() => http.verify());

  function saveButton(): HTMLButtonElement {
    return [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Save baseline'))!;
  }

  async function type(name: string): Promise<void> {
    const input = el.querySelector<HTMLInputElement>('input[name="baseline"]')!;
    input.value = name;
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  it('saves a baseline with a name and compares with it, since it is the first one', async () => {
    expect(saveButton().disabled).toBe(true);
    await type('Signed off 3 March');
    saveButton().click();
    const post = http.expectOne('/api/projects/7/baselines');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ name: 'Signed off 3 March' });
    post.flush({ ...agreed, name: 'Signed off 3 March' });
    http.expectOne('/api/projects/7/baselines').flush([{ ...agreed, name: 'Signed off 3 March' }]);
    http.expectOne('/api/baselines/30/comparison').flush(comparison);
    await fixture.whenStable();
    expect(store.comparedId()).toBe(30);
    expect(el.querySelector('.baseline-list')!.textContent).toContain('4 tasks');
    expect(el.querySelector('.baseline-list')!.textContent).toContain('by Ann');
    expect(el.querySelector<HTMLInputElement>('input[name="baseline"]')!.value).toBe('');
  });

  it('shows what the api refuses', async () => {
    store.baselines.set([agreed]);
    await fixture.whenStable();
    await type('Agreed');
    saveButton().click();
    http.expectOne('/api/projects/7/baselines').flush(
      { code: 'baseline_name_taken', message: 'This project already has a baseline called Agreed' },
      { status: 409, statusText: 'Conflict' },
    );
    http.expectOne('/api/projects/7/baselines').flush([agreed]);
    await fixture.whenStable();
    expect(el.querySelector('[role="alert"]')!.textContent).toContain('already has a baseline called Agreed');
  });

  it('makes another baseline the reference and compares with it', async () => {
    const later: Baseline = { ...agreed, id: 31, name: 'Replanned', reference: false };
    store.baselines.set([agreed, later]);
    await fixture.whenStable();
    [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Make reference'))!.click();
    const patch = http.expectOne('/api/baselines/31');
    expect(patch.request.body).toEqual({ reference: true });
    patch.flush({ ...later, reference: true });
    http.expectOne('/api/baselines/31/comparison').flush({ ...comparison, baseline: later });
    http.expectOne('/api/projects/7/baselines').flush([{ ...agreed, reference: false }, { ...later, reference: true }]);
    http.expectOne('/api/baselines/31/comparison').flush({ ...comparison, baseline: later });
    expect(store.comparedId()).toBe(31);
  });
});
