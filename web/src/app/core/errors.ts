import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from './models';

/** A message that can be shown to the user for any failed request. */
export function messageOf(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const body = error.error as Partial<ApiError> | null;
    if (body && typeof body === 'object' && body.message) {
      if (body.errors?.length) {
        return body.errors.map((e) => `${e.field} ${e.message}`).join(', ');
      }
      return body.message;
    }
    if (error.status === 0) {
      return 'Cannot reach the server. Check your connection and try again.';
    }
    return `Request failed (${error.status})`;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return 'Something went wrong';
}

/** Field-level validation messages keyed by field name. */
export function fieldErrors(error: unknown): Record<string, string> {
  const result: Record<string, string> = {};
  if (error instanceof HttpErrorResponse) {
    const body = error.error as Partial<ApiError> | null;
    for (const e of body?.errors ?? []) {
      result[e.field] = e.message;
    }
  }
  return result;
}
