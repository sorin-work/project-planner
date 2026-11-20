import { DuplicateDefaults } from '../../core/models';
import { DuplicateInput } from '../../core/project.api';

function count(number: number, what: string): string {
  return `${number} ${what}${number === 1 ? '' : 's'}`;
}

/** What a copy takes along and what it leaves behind, in words, for above the form. */
export function copySummary(sourceName: string, defaults: DuplicateDefaults): string {
  const plan = `${count(defaults.phases, 'phase')}, ${count(defaults.tasks, 'task')} and ${count(defaults.dependencies, 'link')}`;
  return (
    `Copies ${plan} from ${sourceName}, with estimates, priorities, budgets, colour and rate. ` +
    'Every task starts again as to do, and the dates move so that the plan begins on the start date. ' +
    'Logged time, comments and activity stay behind.'
  );
}

/** The request for a copy from what the form holds. Codes are stored in upper case. */
export function duplicateBody(form: {
  name?: string | null;
  code?: string | null;
  clientId?: number | null;
  startDate?: string | null;
  keepAssignees?: boolean | null;
}): DuplicateInput {
  return {
    name: (form.name ?? '').trim(),
    code: (form.code ?? '').trim().toUpperCase(),
    clientId: form.clientId ?? null,
    startDate: form.startDate ?? '',
    keepAssignees: form.keepAssignees === true,
  };
}
