import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { Observable, concatMap, from, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { messageOf } from '../../core/errors';
import { Attachment } from '../../core/models';
import { TaskApi } from '../../core/task.api';
import { timeAgo } from '../../shared/dates';
import { filesToSend, formatBytes } from './attachment-files';

/**
 * The files on a task: a list, an upload button and a drop target. A file is opened
 * through a link the API makes for two minutes, since a plain link cannot carry the
 * token. Images get a small preview the same way.
 */
@Component({
  selector: 'app-task-attachments',
  template: `
    <section class="panel-section" aria-labelledby="ta-title">
      <h3 id="ta-title">Files</h3>
      @if (error()) {
        <p class="alert error" role="alert">{{ error() }}</p>
      }
      <ul class="attachments">
        @for (a of attachments(); track a.id) {
          <li>
            @if (previews()[a.id]; as src) {
              <img class="attachment-preview" [src]="src" alt="" />
            }
            <button type="button" class="link" (click)="open(a)" [attr.aria-label]="'Download ' + a.fileName">{{ a.fileName }}</button>
            <span class="muted small grow">
              {{ size(a.sizeBytes) }}{{ a.uploaderName ? ', ' + a.uploaderName : '' }}, {{ timeAgo(a.createdAt) }}
            </span>
            @if (a.canDelete) {
              <button type="button" class="link small" (click)="remove(a)" [attr.aria-label]="'Remove ' + a.fileName">remove</button>
            }
          </li>
        } @empty {
          @if (loaded()) {
            <li class="muted small">No files yet.</li>
          }
        }
      </ul>
      <div class="drop-target" [class.over]="over()" (dragover)="dragOver($event)" (dragleave)="over.set(false)" (drop)="drop($event)">
        <button type="button" class="small" (click)="fileInput.click()">Attach files</button>
        <input #fileInput class="sr-only" type="file" multiple tabindex="-1" aria-hidden="true" (change)="picked($event)" />
        <span class="muted small">or drop them here. Images, PDF, text and office documents, up to 20 MB each.</span>
        @if (uploading(); as count) {
          <span class="muted small" role="status">Uploading {{ count }} file{{ count === 1 ? '' : 's' }}…</span>
        }
      </div>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TaskAttachments {
  private readonly api = inject(TaskApi);

  readonly taskId = input.required<number>();

  protected readonly attachments = signal<Attachment[]>([]);
  protected readonly loaded = signal(false);
  protected readonly previews = signal<Record<number, string>>({});
  protected readonly error = signal<string | null>(null);
  protected readonly uploading = signal(0);
  protected readonly over = signal(false);
  protected readonly size = formatBytes;
  protected readonly timeAgo = timeAgo;

  constructor() {
    effect(() => {
      const taskId = this.taskId();
      this.attachments.set([]);
      this.previews.set({});
      this.loaded.set(false);
      this.error.set(null);
      this.api.attachments(taskId).subscribe({
        next: (list) => {
          if (taskId === this.taskId()) {
            this.attachments.set(list);
            this.loaded.set(true);
            list.filter((a) => a.previewable).forEach((a) => this.preview(a));
          }
        },
        error: (err: unknown) => this.error.set(messageOf(err)),
      });
    });
  }

  picked(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files) {
      this.upload(input.files);
    }
    input.value = '';
  }

  dragOver(event: DragEvent): void {
    event.preventDefault();
    this.over.set(true);
  }

  drop(event: DragEvent): void {
    event.preventDefault();
    this.over.set(false);
    if (event.dataTransfer?.files.length) {
      this.upload(event.dataTransfer.files);
    }
  }

  /** Sends the files one after the other. A file the API refuses is named and the rest still go. */
  upload(files: ArrayLike<File>): void {
    const taskId = this.taskId();
    const { send, refused } = filesToSend(files);
    const problems = [...refused];
    this.error.set(problems.length ? problems.join(' ') : null);
    if (!send.length) {
      return;
    }
    this.uploading.set(send.length);
    from(send)
      .pipe(
        concatMap(
          (file): Observable<Attachment | null> =>
            this.api.uploadAttachment(taskId, file).pipe(
              catchError((err: unknown) => {
                problems.push(`${file.name}: ${messageOf(err)}`);
                return of(null);
              }),
            ),
        ),
      )
      .subscribe({
        next: (attachment) => {
          this.uploading.update((n) => Math.max(0, n - 1));
          if (attachment && taskId === this.taskId()) {
            this.attachments.update((all) => [...all, attachment]);
            this.loaded.set(true);
            if (attachment.previewable) {
              this.preview(attachment);
            }
          }
          this.error.set(problems.length ? problems.join(' ') : null);
        },
      });
  }

  /** Downloads the file: the API's link answers with Content-Disposition: attachment. */
  open(attachment: Attachment): void {
    this.error.set(null);
    this.api.attachmentLink(attachment.id).subscribe({
      next: (link) => {
        const anchor = document.createElement('a');
        anchor.href = link.url;
        anchor.rel = 'noopener';
        anchor.click();
      },
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  remove(attachment: Attachment): void {
    if (!confirm(`Remove ${attachment.fileName} from this task?`)) {
      return;
    }
    this.api.deleteAttachment(attachment.id).subscribe({
      next: () => this.attachments.update((all) => all.filter((a) => a.id !== attachment.id)),
      error: (err: unknown) => this.error.set(messageOf(err)),
    });
  }

  private preview(attachment: Attachment): void {
    this.api.attachmentLink(attachment.id, true).subscribe({
      next: (link) => this.previews.update((all) => ({ ...all, [attachment.id]: link.url })),
      // No preview is no loss: the name is still there to download.
      error: () => undefined,
    });
  }
}
