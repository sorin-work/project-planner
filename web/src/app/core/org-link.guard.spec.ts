import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from './auth.service';
import { Me } from './models';
import { orgLinkGuard, orgOfLink } from './org-link.guard';
import { OrgContext } from './org-context.service';

@Component({ template: 'page' })
class Page {}

const ME: Me = {
  user: { id: 5, email: 'maya@example.com', name: 'Maya Collins', handle: 'maya' },
  organisations: [
    { organisationId: 7, organisationName: 'Brightline Studio', role: 'OWNER', currency: 'EUR' },
    { organisationId: 8, organisationName: 'Side Project', role: 'MEMBER', currency: 'USD' },
  ],
};

describe('orgOfLink', () => {
  it('takes the organisation of the link only when the person is in it', () => {
    expect(orgOfLink('8', ME)).toBe(8);
    expect(orgOfLink('9', ME)).toBeNull();
    expect(orgOfLink('8abc', ME)).toBeNull();
    expect(orgOfLink(null, ME)).toBeNull();
    expect(orgOfLink('8', null)).toBeNull();
  });
});

describe('orgLinkGuard', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'projects/:id', component: Page, canActivate: [orgLinkGuard] }]),
      ],
    });
  });

  it('switches to the organisation of a mailed link before the page opens', async () => {
    TestBed.inject(AuthService).me.set(ME);
    const org = TestBed.inject(OrgContext);
    expect(org.orgId()).toBe(7);
    const harness = await RouterTestingHarness.create('/projects/12?org=8');
    expect(org.orgId()).toBe(8);
    // An organisation the person is not in changes nothing.
    await harness.navigateByUrl('/projects/12?org=99');
    expect(org.orgId()).toBe(8);
  });

  it('waits for the account when it is not loaded yet', async () => {
    const org = TestBed.inject(OrgContext);
    const http = TestBed.inject(HttpTestingController);
    const opening = RouterTestingHarness.create('/projects/12?org=8');
    await new Promise((resolve) => setTimeout(resolve));
    http.expectOne('/api/me').flush(ME);
    await opening;
    expect(org.orgId()).toBe(8);
  });
});
