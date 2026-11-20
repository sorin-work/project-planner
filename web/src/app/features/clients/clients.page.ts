import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { messageOf } from '../../core/errors';
import { Client } from '../../core/models';
import { ClientInput, OrgApi } from '../../core/org.api';
import { OrgContext } from '../../core/org-context.service';

@Component({
  selector: 'app-clients-page',
  imports: [FormsModule],
  template: `
    <div class="page-header">
      <h1>Clients</h1>
      <label><input type="checkbox" [(ngModel)]="showArchived" (ngModelChange)="load()" /> Show archived</label>
    </div>
    @if (error()) {
      <p class="alert error" role="alert">{{ error() }}</p>
    }
    <div class="grid-2 wide-left">
      <section class="card table-wrap">
        <table class="table">
          <thead><tr><th>Name</th><th>Contact</th><th class="num">Projects</th><th><span class="sr-only">Actions</span></th></tr></thead>
          <tbody>
            @for (c of clients(); track c.id) {
              <tr [class.muted]="c.archived">
                <td>{{ c.name }} @if (c.archived) { <span class="badge">archived</span> }</td>
                <td>{{ c.contactName }} @if (c.contactEmail) { <span class="muted small">{{ c.contactEmail }}</span> }</td>
                <td class="num">{{ c.projectCount }}</td>
                <td class="actions-cell">
                  <button type="button" class="link small" (click)="edit(c)">edit</button>
                  <button type="button" class="link small danger" (click)="remove(c)">{{ c.projectCount ? 'archive' : 'delete' }}</button>
                </td>
              </tr>
            } @empty {
              <tr><td colspan="4" class="muted">No clients yet.</td></tr>
            }
          </tbody>
        </table>
      </section>
      <section class="card">
        <h2>{{ editingId() ? 'Edit client' : 'Add client' }}</h2>
        <form class="form-grid" (ngSubmit)="save()">
          <div class="field span-2"><label for="c-name">Name</label><input id="c-name" name="name" [(ngModel)]="form.name" required /></div>
          <div class="field"><label for="c-contact">Contact</label><input id="c-contact" name="contact" [(ngModel)]="form.contactName" /></div>
          <div class="field"><label for="c-email">Email</label><input id="c-email" name="email" type="email" [(ngModel)]="form.contactEmail" /></div>
          <div class="field span-2"><label for="c-notes">Notes</label><textarea id="c-notes" name="notes" rows="3" [(ngModel)]="form.notes"></textarea></div>
          @if (editingId()) {
            <label class="span-2 checkbox"><input type="checkbox" name="archived" [(ngModel)]="form.archived" /> Archived</label>
          }
          <div class="actions span-2">
            @if (editingId()) { <button type="button" (click)="reset()">Cancel</button> }
            <button type="submit" class="primary" [disabled]="!form.name.trim()">{{ editingId() ? 'Save' : 'Add client' }}</button>
          </div>
        </form>
      </section>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClientsPage {
  private readonly api = inject(OrgApi);
  private readonly org = inject(OrgContext);

  protected readonly clients = signal<Client[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly editingId = signal<number | null>(null);
  protected showArchived = false;
  protected form: Required<ClientInput> & { name: string } = blank();

  constructor() {
    effect(() => {
      if (this.org.orgId() !== null) {
        this.load();
      }
    });
  }

  load(): void {
    this.api.clients(this.org.id(), this.showArchived).subscribe({
      next: (c) => this.clients.set(c),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  edit(client: Client): void {
    this.editingId.set(client.id);
    this.form = {
      name: client.name,
      contactName: client.contactName ?? '',
      contactEmail: client.contactEmail ?? '',
      notes: client.notes ?? '',
      archived: client.archived,
    };
  }

  reset(): void {
    this.editingId.set(null);
    this.form = blank();
  }

  save(): void {
    const orgId = this.org.id();
    const id = this.editingId();
    const request = id ? this.api.updateClient(orgId, id, this.form) : this.api.createClient(orgId, this.form);
    request.subscribe({
      next: () => {
        this.reset();
        this.load();
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  remove(client: Client): void {
    const verb = client.projectCount ? 'Archive' : 'Delete';
    if (!confirm(`${verb} ${client.name}?`)) {
      return;
    }
    this.api.deleteClient(this.org.id(), client.id).subscribe({
      next: () => this.load(),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }
}

function blank() {
  return { name: '', contactName: '', contactEmail: '', notes: '', archived: false };
}
