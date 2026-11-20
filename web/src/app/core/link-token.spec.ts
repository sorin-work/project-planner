import { tokenFromFragment } from './link-token';

describe('tokenFromFragment', () => {
  const token = 'aB3_-xYz0123456789abcdefghijklmnopqrstuvwxy';

  it('reads the token after #token=', () => {
    expect(tokenFromFragment(`token=${token}`)).toBe(token);
    expect(tokenFromFragment(`#token=${token}`)).toBe(token);
  });

  it('gives nothing for a missing, short or strange token', () => {
    expect(tokenFromFragment(null)).toBeNull();
    expect(tokenFromFragment('')).toBeNull();
    expect(tokenFromFragment('other=1')).toBeNull();
    expect(tokenFromFragment('token=short')).toBeNull();
    expect(tokenFromFragment('token=<script>alert(1)</script>xxxxxxxxxx')).toBeNull();
  });
});
