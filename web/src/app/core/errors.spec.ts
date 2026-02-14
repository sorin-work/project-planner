import { HttpErrorResponse } from '@angular/common/http';
import { fieldErrors, messageOf } from './errors';

describe('errors', () => {
  it('uses the API message', () => {
    const err = new HttpErrorResponse({ status: 409, error: { status: 409, code: 'dependency_cycle', message: 'That would create a loop' } });
    expect(messageOf(err)).toBe('That would create a loop');
  });

  it('lists validation errors', () => {
    const err = new HttpErrorResponse({
      status: 400,
      error: { status: 400, code: 'validation_failed', message: 'Some fields are invalid', errors: [{ field: 'email', message: 'must be a well-formed email address' }] },
    });
    expect(messageOf(err)).toBe('email must be a well-formed email address');
    expect(fieldErrors(err)).toEqual({ email: 'must be a well-formed email address' });
  });

});
