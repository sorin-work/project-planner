import { Injectable, computed, inject, signal } from '@angular/core';
import { AuthService } from './auth.service';
import { MembershipSummary, Role } from './models';

const ORG_KEY = 'pp.orgId';

/** Which organisation the user is working in, and what they may do there. */
@Injectable({ providedIn: 'root' })
export class OrgContext {
  private readonly auth = inject(AuthService);
  private readonly selectedId = signal<number | null>(readSelected());

  readonly memberships = computed<MembershipSummary[]>(() => this.auth.me()?.organisations ?? []);

  readonly current = computed<MembershipSummary | null>(() => {
    const all = this.memberships();
    return all.find((m) => m.organisationId === this.selectedId()) ?? all[0] ?? null;
  });

  readonly orgId = computed(() => this.current()?.organisationId ?? null);
  readonly role = computed<Role | null>(() => this.current()?.role ?? null);
  readonly currency = computed(() => this.current()?.currency ?? 'EUR');
  readonly canManage = computed(() => this.role() === 'OWNER' || this.role() === 'MANAGER');
  readonly isOwner = computed(() => this.role() === 'OWNER');
  readonly isTeam = computed(() => this.role() !== null && this.role() !== 'CLIENT');

  select(orgId: number): void {
    this.selectedId.set(orgId);
    try {
      localStorage.setItem(ORG_KEY, String(orgId));
    } catch {
      // Private mode: the choice just isn't remembered.
    }
  }

  /** The current org id; only call this where the shell has made sure there is one. */
  id(): number {
    const id = this.orgId();
    if (id === null) {
      throw new Error('No organisation selected');
    }
    return id;
  }
}

function readSelected(): number | null {
  try {
    const value = localStorage.getItem(ORG_KEY);
    return value ? Number(value) : null;
  } catch {
    return null;
  }
}
