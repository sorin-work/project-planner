import { TestBed } from '@angular/core/testing';
import { Member } from '../../core/models';
import { CommentBox } from './comment-box';

const member = (userId: number, name: string, handle: string): Member => ({
  id: userId, userId, name, handle, email: `${handle}@example.com`, role: 'MEMBER', weeklyCapacityHours: 40, joinedAt: '',
});

describe('CommentBox suggestions', () => {
  it('points the textarea at the list and at the person who is highlighted', async () => {
    TestBed.configureTestingModule({ imports: [CommentBox] });
    const fixture = TestBed.createComponent(CommentBox);
    fixture.componentRef.setInput('members', [member(1, 'Ann Lee', 'ann'), member(2, 'Anna Berg', 'anna')]);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const area = el.querySelector('textarea')!;
    expect(area.getAttribute('aria-controls')).toBeNull();
    const combobox = el.querySelector('[role="combobox"]')!;
    expect(combobox.getAttribute('aria-expanded')).toBe('false');

    area.value = 'Thanks @an';
    area.setSelectionRange(area.value.length, area.value.length);
    area.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    const list = el.querySelector('[role="listbox"]')!;
    expect(area.getAttribute('aria-controls')).toBe(list.id);
    expect(combobox.getAttribute('aria-expanded')).toBe('true');
    expect(combobox.getAttribute('aria-owns')).toBe(list.id);
    const options = [...list.querySelectorAll('[role="option"]')];
    expect(area.getAttribute('aria-activedescendant')).toBe(options[0].id);

    area.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await fixture.whenStable();
    expect(area.getAttribute('aria-activedescendant')).toBe(options[1].id);
    expect(options[1].getAttribute('aria-selected')).toBe('true');
  });

  it('closes the suggestions on Escape without letting the key reach the panel around it', async () => {
    TestBed.configureTestingModule({ imports: [CommentBox] });
    const fixture = TestBed.createComponent(CommentBox);
    fixture.componentRef.setInput('members', [member(1, 'Ann Lee', 'ann')]);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const outside = vi.fn();
    el.addEventListener('keydown', outside);
    const area = el.querySelector('textarea')!;
    area.value = 'Thanks @an';
    area.setSelectionRange(area.value.length, area.value.length);
    area.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    area.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
    await fixture.whenStable();
    expect(el.querySelector('[role="listbox"]')).toBeNull();
    expect(outside).not.toHaveBeenCalled();
    expect(area.value).toBe('Thanks @an');
  });
});

