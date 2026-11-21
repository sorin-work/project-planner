import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Invoice, InvoiceDraftInput, InvoiceSummary } from './models';

/** Invoices from approved time, for owners and managers. */
@Injectable({ providedIn: 'root' })
export class InvoiceApi {
  private readonly http = inject(HttpClient);

  private base(orgId: number): string {
    return `/api/orgs/${orgId}/invoices`;
  }

  list(orgId: number): Observable<InvoiceSummary[]> {
    return this.http.get<InvoiceSummary[]>(this.base(orgId));
  }

  get(orgId: number, id: number): Observable<Invoice> {
    return this.http.get<Invoice>(`${this.base(orgId)}/${id}`);
  }

  draft(orgId: number, input: InvoiceDraftInput): Observable<Invoice> {
    return this.http.post<Invoice>(this.base(orgId), input);
  }

  change(orgId: number, id: number, change: { taxRatePercent?: number; notes?: string }): Observable<Invoice> {
    return this.http.patch<Invoice>(`${this.base(orgId)}/${id}`, change);
  }

  changeLine(orgId: number, id: number, lineId: number, description: string): Observable<Invoice> {
    return this.http.patch<Invoice>(`${this.base(orgId)}/${id}/lines/${lineId}`, { description });
  }

  removeLine(orgId: number, id: number, lineId: number): Observable<Invoice> {
    return this.http.delete<Invoice>(`${this.base(orgId)}/${id}/lines/${lineId}`);
  }

  delete(orgId: number, id: number): Observable<void> {
    return this.http.delete<void>(`${this.base(orgId)}/${id}`);
  }

  issue(orgId: number, id: number): Observable<Invoice> {
    return this.http.post<Invoice>(`${this.base(orgId)}/${id}/issue`, {});
  }

  credit(orgId: number, id: number): Observable<Invoice> {
    return this.http.post<Invoice>(`${this.base(orgId)}/${id}/credit`, {});
  }

  csv(orgId: number, id: number): Observable<Blob> {
    return this.http.get(`${this.base(orgId)}/${id}/lines.csv`, { responseType: 'blob' });
  }
}
