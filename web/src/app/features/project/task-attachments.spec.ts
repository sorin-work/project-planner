import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Attachment } from '../../core/models';
import { TaskAttachments } from './task-attachments';

const brief: Attachment = {
  id: 7, taskId: 2, fileName: 'brief.pdf', contentType: 'application/pdf', sizeBytes: 14 * 1024, uploaderId: 1,
  uploaderName: 'Ann', createdAt: new Date().toISOString(), previewable: false, canDelete: true,
};
const photo: Attachment = { ...brief, id: 8, fileName: 'mock-up.png', contentType: 'image/png', previewable: true, canDelete: false };

describe('TaskAttachments', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<TaskAttachments>;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [TaskAttachments], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TaskAttachments);
    fixture.componentRef.setInput('taskId', 2);
    await fixture.whenStable();
    el = fixture.nativeElement as HTMLElement;
    http.expectOne('/api/tasks/2/attachments').flush([brief, photo]);
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  function linkFor(id: number, preview: boolean) {
    return http.expectOne((r) => r.url === `/api/attachments/${id}/link` && r.params.get('preview') === String(preview));
  }

  it('lists the files, previews the images through a link, and offers remove only where it may be used', async () => {
    linkFor(8, true).flush({ url: '/api/attachments/8/content?inline=true&signature=ab', expiresAt: '' });
    await fixture.whenStable();
    const rows = [...el.querySelectorAll('.attachments li')];
    expect(rows.map((r) => r.querySelector('button.link')?.textContent?.trim())).toEqual(['brief.pdf', 'mock-up.png']);
    expect(rows[0].textContent).toContain('14 KB, Ann');
    expect(el.querySelector<HTMLImageElement>('.attachment-preview')?.getAttribute('src')).toBe('/api/attachments/8/content?inline=true&signature=ab');
    expect(rows[0].querySelector('[aria-label="Remove brief.pdf"]')).not.toBeNull();
    expect(rows[1].querySelector('[aria-label="Remove mock-up.png"]')).toBeNull();
  });

  it('downloads through a link the api makes, since a plain link cannot carry the token', () => {
    linkFor(8, true).flush({ url: '/x', expiresAt: '' });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    el.querySelector<HTMLButtonElement>('[aria-label="Download brief.pdf"]')!.click();
    linkFor(7, false).flush({ url: '/api/attachments/7/content?signature=cd', expiresAt: '' });
    expect(click).toHaveBeenCalledTimes(1);
    expect((click.mock.contexts[0] as HTMLAnchorElement).getAttribute('href')).toBe('/api/attachments/7/content?signature=cd');
    click.mockRestore();
  });

  it('sends each file, keeps back one that is too large, and names the one the api refuses', async () => {
    linkFor(8, true).flush({ url: '/x', expiresAt: '' });
    const notes = new File(['Kick-off notes'], 'notes.txt');
    const page = new File(['<html></html>'], 'page.html');
    const huge = new File(['x'], 'huge.png');
    Object.defineProperty(huge, 'size', { value: 21 * 1024 * 1024 });
    (fixture.componentInstance as TaskAttachments).upload([notes, huge, page]);

    const first = http.expectOne('/api/tasks/2/attachments');
    expect(((first.request.body as FormData).get('file') as File).name).toBe('notes.txt');
    first.flush({ ...brief, id: 9, fileName: 'notes.txt', contentType: 'text/plain; charset=utf-8' });
    await fixture.whenStable();
    const second = http.expectOne('/api/tasks/2/attachments');
    second.flush(
      { code: 'unsupported_file_type', message: 'This kind of file cannot be attached.' },
      { status: 415, statusText: 'Unsupported Media Type' },
    );
    await fixture.whenStable();

    expect([...el.querySelectorAll('.attachments li button.link')].map((b) => b.textContent?.trim())).toContain('notes.txt');
    const alert = el.querySelector('[role="alert"]')?.textContent ?? '';
    expect(alert).toContain('huge.png is larger than 20 MB');
    expect(alert).toContain('page.html: This kind of file cannot be attached.');
  });

  it('removes a file after asking', async () => {
    linkFor(8, true).flush({ url: '/x', expiresAt: '' });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    el.querySelector<HTMLButtonElement>('[aria-label="Remove brief.pdf"]')!.click();
    const del = http.expectOne('/api/attachments/7');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    await fixture.whenStable();
    expect(el.querySelectorAll('.attachments li')).toHaveLength(1);
  });
});
