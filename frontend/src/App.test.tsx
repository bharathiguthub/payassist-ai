import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { App } from './App';
import { loggedInAs, server } from './test/server';
import { renderWithProviders } from './test/render';

describe('App authentication and role-based navigation', () => {
  it('shows the SSO sign-in page when there is no session', async () => {
    renderWithProviders(<App />);

    expect(await screen.findByRole('button', { name: /sign in with company sso/i })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument();
  });

  it('shows a sign-in error after a failed login redirect', async () => {
    renderWithProviders(<App />, { route: '/?error=login_failed' });

    expect(await screen.findByRole('alert')).toHaveTextContent(/sign-in failed/i);
  });

  it('gives a care agent payments only', async () => {
    loggedInAs(['CARE_AGENT']);
    renderWithProviders(<App />);

    expect(await screen.findByRole('link', { name: 'Payments' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Supervisor' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Audit log' })).not.toBeInTheDocument();
    expect(screen.getByText('Alice Agent')).toBeInTheDocument();
  });

  it('gives a supervisor the supervisor overview but not the audit log', async () => {
    loggedInAs(['CARE_SUPERVISOR']);
    renderWithProviders(<App />);

    expect(await screen.findByRole('link', { name: 'Supervisor' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Audit log' })).not.toBeInTheDocument();
  });

  it('gives an admin every section', async () => {
    loggedInAs(['ADMIN']);
    renderWithProviders(<App />);

    expect(await screen.findByRole('link', { name: 'Supervisor' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Audit log' })).toBeInTheDocument();
  });

  it('shows refund approvals to supervisors and admins only', async () => {
    loggedInAs(['CARE_SUPERVISOR']);
    renderWithProviders(<App />);
    expect(await screen.findByRole('link', { name: 'Refund approvals' })).toHaveAttribute('href', '/refunds/approvals');
  });

  it('blocks a care agent who navigates directly to the approval queue', async () => {
    loggedInAs(['CARE_AGENT']);
    renderWithProviders(<App />, { route: '/refunds/approvals' });

    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Refund approvals' })).not.toBeInTheDocument();
  });

  it('blocks a care agent who navigates directly to an admin route', async () => {
    loggedInAs(['CARE_AGENT']);
    renderWithProviders(<App />, { route: '/admin/audit' });

    expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  });

  it('explains when a signed-in user has no PayAssist role', async () => {
    loggedInAs([]);
    renderWithProviders(<App />);

    expect(await screen.findByRole('heading', { name: 'No access' })).toBeInTheDocument();
  });

  it('shows an outage message when the backend fails', async () => {
    server.use(http.get('/api/v1/me', () => new HttpResponse(null, { status: 503 })));
    renderWithProviders(<App />);

    expect(await screen.findByRole('alert')).toHaveTextContent(/unavailable/i);
  });
});
