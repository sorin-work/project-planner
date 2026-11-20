import { Invitation } from '../../core/models';
import { inviteNotice } from './team.page';

describe('inviteNotice', () => {
  const invitation: Invitation = { id: 1, email: 'pat@example.com', role: 'MEMBER', createdAt: '2026-11-20T10:00:00Z' };

  it('says a mail went out when the invitation has a link', () => {
    expect(inviteNotice({ invitation: { ...invitation, linkExpiresAt: '2026-11-27T10:00:00Z' } }, 'pat@example.com')).toBe(
      'Invitation sent to pat@example.com. The link in it works for seven days.',
    );
  });

  it('says how to join when the server sends no mail', () => {
    expect(inviteNotice({ invitation }, 'pat@example.com')).toContain('joins automatically when they sign up');
  });
});
