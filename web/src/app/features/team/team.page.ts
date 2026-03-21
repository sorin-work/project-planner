import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { messageOf } from '../../core/errors';
import { Client, Invitation, Member, Role, RoleRate } from '../../core/models';
import { OrgApi } from '../../core/org.api';
import { OrgContext } from '../../core/org-context.service';
import { Avatar } from '../../shared/avatar';
import { ROLE_LABELS, formatMoney } from '../../shared/format';

@Component({
  selector: 'app-team-page',
  imports: [FormsModule, Avatar],
  templateUrl: './team.page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TeamPage {
  private readonly api = inject(OrgApi);
  protected readonly org = inject(OrgContext);

  protected readonly members = signal<Member[]>([]);
  protected readonly invitations = signal<Invitation[]>([]);
  protected readonly clients = signal<Client[]>([]);
  protected readonly rates = signal<Record<string, number | null>>({ MANAGER: null, MEMBER: null });
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected invite = { email: '', role: 'MEMBER' as Role, clientId: null as number | null };
  protected readonly roles: Role[] = ['OWNER', 'MANAGER', 'MEMBER', 'CLIENT'];
  protected readonly inviteRoles: Role[] = ['MANAGER', 'MEMBER', 'CLIENT'];
  protected readonly rateRoles: Role[] = ['OWNER', 'MANAGER', 'MEMBER'];
  protected readonly roleLabels = ROLE_LABELS;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      if (orgId === null) {
        return;
      }
      this.load(orgId);
    });
  }

  money(value: number | undefined): string {
    return formatMoney(value, this.org.currency());
  }

  clientName(id: number | undefined): string {
    return this.clients().find((c) => c.id === id)?.name ?? '';
  }

  updateMember(member: Member, patch: Parameters<OrgApi['updateMember']>[2]): void {
    this.error.set(null);
    this.api.updateMember(this.org.id(), member.id, patch).subscribe({
      next: (updated) => this.members.update((all) => all.map((m) => (m.id === updated.id ? updated : m))),
      error: (err: unknown) => {
        this.error.set(messageOf(err));
        this.load(this.org.id());
      },
    });
  }

  setRate(member: Member, value: string): void {
    this.updateMember(member, value === '' ? { clearHourlyRate: true } : { hourlyRate: Number(value) });
  }

  sendInvite(): void {
    this.error.set(null);
    this.notice.set(null);
    const orgId = this.org.id();
    this.api.invite(orgId, { ...this.invite, clientId: this.invite.role === 'CLIENT' ? this.invite.clientId : null }).subscribe({
      next: (res) => {
        this.notice.set(
          res.member
            ? `${res.member.name} already had an account and has been added.`
            : `Invitation saved. ${this.invite.email} joins automatically when they sign up with that address.`,
        );
        this.invite = { email: '', role: 'MEMBER', clientId: null };
        this.load(orgId);
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  saveRates(): void {
    const rates: RoleRate[] = Object.entries(this.rates())
      .filter(([, v]) => v !== null && v !== undefined && `${v}` !== '')
      .map(([role, v]) => ({ role: role as Role, hourlyRate: Number(v) }));
    this.api.setRoleRates(this.org.id(), rates).subscribe({
      next: () => this.notice.set('Role rates saved.'),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  setRoleRate(role: Role, value: string): void {
    this.rates.update((r) => ({ ...r, [role]: value === '' ? null : Number(value) }));
  }

  private load(orgId: number): void {
    this.api.members(orgId).subscribe({ next: (m) => this.members.set(m), error: (err: unknown) => this.error.set(messageOf(err)) });
    if (this.org.canManage()) {
      this.api.invitations(orgId).subscribe({ next: (i) => this.invitations.set(i) });
      this.api.clients(orgId).subscribe({ next: (c) => this.clients.set(c) });
      this.api.roleRates(orgId).subscribe({
        next: (rates) => {
          const map: Record<string, number | null> = { OWNER: null, MANAGER: null, MEMBER: null };
          rates.forEach((r) => (map[r.role] = r.hourlyRate));
          this.rates.set(map);
        },
      });
    }
  }
}
