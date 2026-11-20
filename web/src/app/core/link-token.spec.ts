import { signedTokenFromFragment, tokenFromFragment } from './link-token';

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

describe('signedTokenFromFragment', () => {
  it('reads a token with its signature after the dot', () => {
    const token = 'dW5zdWJzY3JpYmU6NzpNRU5USU9ORUQ.aB3_-xYz0123456789abcdefghijklmnopqrstuvw';
    expect(signedTokenFromFragment(`token=${token}`)).toBe(token);
    expect(signedTokenFromFragment('token=no-dot-here-at-all-xxxxxxxx')).toBeNull();
    expect(signedTokenFromFragment(null)).toBeNull();
  });
});
