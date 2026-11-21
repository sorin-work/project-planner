import { TestBed } from '@angular/core/testing';
import { Member, Project, Role } from '../../core/models';
import { ProjectInput } from '../../core/project.api';
import { ProjectForm } from './project-form';

function member(userId: number, name: string, role: Role): Member {
  return { id: userId * 10, userId, name, email: `${name}@example.com`, handle: name, role, weeklyCapacityHours: 40, joinedAt: '2026-01-01T00:00:00Z' };
}

describe('ProjectForm, the lead', () => {
  async function render(project: Project | null) {
    TestBed.configureTestingModule({ imports: [ProjectForm] });
    const fixture = TestBed.createComponent(ProjectForm);
    fixture.componentRef.setInput('members', [member(5, 'Lena', 'MEMBER'), member(6, 'Priya', 'MANAGER'), member(9, 'Cleo', 'CLIENT')]);
    fixture.componentRef.setInput('project', project);
    await fixture.whenStable();
    const saved: ProjectInput[] = [];
    fixture.componentInstance.saved.subscribe((input) => saved.push(input));
    return { fixture, el: fixture.nativeElement as HTMLElement, saved };
  }

  it('offers the team but not client viewers, and sends who was picked', async () => {
    const { fixture, el, saved } = await render({ id: 1, organisationId: 1, name: 'Site', code: 'SITE', status: 'ACTIVE', color: '#4f6bed', createdAt: '', updatedAt: '' });
    const select = el.querySelector<HTMLSelectElement>('#pf-lead')!;
    expect([...select.options].map((o) => o.textContent!.trim())).toEqual(['Nobody', 'Lena', 'Priya']);
    select.selectedIndex = 1;
    select.dispatchEvent(new Event('change'));
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(saved[0].leadId).toBe(5);
  });

  it('keeps the current lead, and sends null for nobody', async () => {
    const { fixture, el, saved } = await render({
      id: 1, organisationId: 1, name: 'Site', code: 'SITE', status: 'ACTIVE', color: '#4f6bed', createdAt: '', updatedAt: '', leadId: 6, leadName: 'Priya',
    });
    const select = el.querySelector<HTMLSelectElement>('#pf-lead')!;
    expect(select.options[select.selectedIndex].textContent!.trim()).toBe('Priya');
    select.selectedIndex = 0;
    select.dispatchEvent(new Event('change'));
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(saved[0].leadId).toBeNull();
  });
});
