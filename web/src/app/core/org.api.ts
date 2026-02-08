import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Client, Invitation, Member, Organisation, Role, RoleRate } from './models';

export interface ClientInput {
  name: string;
  contactName?: string | null;
  contactEmail?: string | null;
  notes?: string | null;
  archived?: boolean;
}

export interface MemberPatch {
  role?: Role;
  hourlyRate?: number | null;
  clearHourlyRate?: boolean;
  weeklyCapacityHours?: number;
  clientId?: number | null;
}

@Injectable({ providedIn: 'root' })
export class OrgApi {
  private readonly http = inject(HttpClient);

  organisation(orgId: number): Observable<Organisation> {
    return this.http.get<Organisation>(`/api/orgs/${orgId}`);
  }

  updateOrganisation(orgId: number, body: Partial<Organisation>): Observable<Organisation> {
    return this.http.patch<Organisation>(`/api/orgs/${orgId}`, body);
  }

  members(orgId: number): Observable<Member[]> {
    return this.http.get<Member[]>(`/api/orgs/${orgId}/members`);
  }

  updateMember(orgId: number, memberId: number, body: MemberPatch): Observable<Member> {
    return this.http.patch<Member>(`/api/orgs/${orgId}/members/${memberId}`, body);
  }

  removeMember(orgId: number, memberId: number): Observable<void> {
    return this.http.delete<void>(`/api/orgs/${orgId}/members/${memberId}`);
  }

  invitations(orgId: number): Observable<Invitation[]> {
    return this.http.get<Invitation[]>(`/api/orgs/${orgId}/invitations`);
  }

  invite(
    orgId: number,
    body: { email: string; role: Role; clientId?: number | null },
  ): Observable<{ member?: Member; invitation?: Invitation }> {
    return this.http.post<{ member?: Member; invitation?: Invitation }>(`/api/orgs/${orgId}/invitations`, body);
  }

  cancelInvitation(orgId: number, id: number): Observable<void> {
    return this.http.delete<void>(`/api/orgs/${orgId}/invitations/${id}`);
  }

  roleRates(orgId: number): Observable<RoleRate[]> {
    return this.http.get<RoleRate[]>(`/api/orgs/${orgId}/role-rates`);
  }

  setRoleRates(orgId: number, rates: RoleRate[]): Observable<RoleRate[]> {
    return this.http.put<RoleRate[]>(`/api/orgs/${orgId}/role-rates`, { rates });
  }

  clients(orgId: number, includeArchived = false): Observable<Client[]> {
    return this.http.get<Client[]>(`/api/orgs/${orgId}/clients`, { params: { archived: includeArchived } });
  }

  createClient(orgId: number, body: ClientInput): Observable<Client> {
    return this.http.post<Client>(`/api/orgs/${orgId}/clients`, body);
  }

  updateClient(orgId: number, id: number, body: ClientInput): Observable<Client> {
    return this.http.put<Client>(`/api/orgs/${orgId}/clients/${id}`, body);
  }

  deleteClient(orgId: number, id: number): Observable<void> {
    return this.http.delete<void>(`/api/orgs/${orgId}/clients/${id}`);
  }
}
