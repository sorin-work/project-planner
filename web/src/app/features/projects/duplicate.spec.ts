import { copySummary, duplicateBody } from './duplicate';

describe('duplicating a project', () => {
  it('says what a copy takes along and what it leaves behind', () => {
    const text = copySummary('Website', { name: 'Website (copy)', code: 'WEB-2', startDate: '2026-06-10', phases: 3, tasks: 6, dependencies: 2 });
    expect(text).toContain('Copies 3 phases, 6 tasks and 2 links from Website');
    expect(text).toContain('Every task starts again as to do');
    expect(text).toContain('Logged time, comments and activity stay behind.');
    expect(copySummary('Site', { name: '', code: '', startDate: '', phases: 1, tasks: 1, dependencies: 1 })).toContain(
      'Copies 1 phase, 1 task and 1 link from Site',
    );
  });

  it('builds the request from the form, without a client or assignees unless they were asked for', () => {
    expect(duplicateBody({ name: '  Website for Globex ', code: ' glx-web ', clientId: null, startDate: '2026-06-10', keepAssignees: false })).toEqual({
      name: 'Website for Globex', code: 'GLX-WEB', clientId: null, startDate: '2026-06-10', keepAssignees: false,
    });
    expect(duplicateBody({ name: 'Copy', code: 'C-2', clientId: 12, startDate: '2026-06-10', keepAssignees: true })).toEqual({
      name: 'Copy', code: 'C-2', clientId: 12, startDate: '2026-06-10', keepAssignees: true,
    });
    expect(duplicateBody({})).toEqual({ name: '', code: '', clientId: null, startDate: '', keepAssignees: false });
  });
});
