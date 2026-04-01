import { TestBed } from '@angular/core/testing';
import { Avatar } from './avatar';
import { HealthBadge } from './health-badge';
import { ProgressBar } from './progress-bar';

describe('shared components', () => {
  it('labels health', async () => {
    const fixture = TestBed.createComponent(HealthBadge);
    fixture.componentRef.setInput('status', 'AT_RISK');
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement.querySelector('.badge');
    expect(el.textContent).toBe('At risk');
    expect(el.classList).toContain('health-at_risk');
  });

  it('caps the progress bar but flags overruns', async () => {
    const fixture = TestBed.createComponent(ProgressBar);
    fixture.componentRef.setInput('value', 130);
    fixture.componentRef.setInput('marker', 60);
    await fixture.whenStable();
    const bar: HTMLElement = fixture.nativeElement.querySelector('.bar');
    expect(bar.classList).toContain('over');
    expect((bar.querySelector('.bar-fill') as HTMLElement).style.width).toBe('100%');
    expect((bar.querySelector('.bar-marker') as HTMLElement).style.left).toBe('60%');
  });

  it('shows initials in a stable colour', async () => {
    const fixture = TestBed.createComponent(Avatar);
    fixture.componentRef.setInput('name', 'Lena Novak');
    await fixture.whenStable();
    const first: HTMLElement = fixture.nativeElement.querySelector('.avatar');
    expect(first.textContent).toBe('LN');
    const again = TestBed.createComponent(Avatar);
    again.componentRef.setInput('name', 'Lena Novak');
    await again.whenStable();
    expect((again.nativeElement.querySelector('.avatar') as HTMLElement).style.background).toBe(first.style.background);
  });
});
