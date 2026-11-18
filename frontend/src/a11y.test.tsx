import { screen } from '@testing-library/react';
import { axe } from './test/axe';
import { App } from './App';
import type { Role } from './api/types';
import { loggedInAs } from './test/server';
import { renderWithProviders } from './test/render';

/**
 * Automated accessibility checks (axe-core) on whole pages including the app shell. jsdom has no layout, so colour
 * contrast is not checked here; the Playwright suite runs axe in a real browser for that.
 */
describe('Accessibility (axe)', () => {
  const pages: [string, Role[], string, string][] = [
    ['payment search', ['CARE_AGENT'], '/payments', 'Payment investigation'],
    ['refund approvals', ['CARE_SUPERVISOR'], '/refunds/approvals', 'Refund approvals'],
    ['disputes', ['CARE_AGENT'], '/disputes', 'Disputes'],
    ['audit log', ['ADMIN'], '/admin/audit', 'Audit log'],
  ];

  it.each(pages)('%s has no violations', async (_name, roles, route, heading) => {
    loggedInAs(roles);
    const { container } = renderWithProviders(<App />, { route });
    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('the sign-in page has no violations', async () => {
    const { container } = renderWithProviders(<App />);
    expect(await screen.findByRole('button', { name: 'Sign in with company SSO' })).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('offers a skip link to the main content', async () => {
    loggedInAs(['CARE_AGENT']);
    renderWithProviders(<App />, { route: '/payments' });
    expect(await screen.findByRole('link', { name: 'Skip to main content' })).toHaveAttribute('href', '#main');
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main');
  });
});
