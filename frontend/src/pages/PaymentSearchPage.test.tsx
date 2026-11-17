import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { PaymentSearchPage } from './PaymentSearchPage';
import { samplePayments, server } from '../test/server';
import { renderWithProviders } from '../test/render';

describe('PaymentSearchPage', () => {
  it('lists payments returned by the API', async () => {
    renderWithProviders(<PaymentSearchPage />);

    expect(await screen.findByRole('link', { name: 'PAY-000001' })).toHaveAttribute('href', '/payments/PAY-000001');
    expect(screen.getByText('2 payments found')).toBeInTheDocument();
    expect(within(screen.getByRole('table')).getByText('Failed')).toBeInTheDocument();
    expect(screen.getByText('US$84.19')).toBeInTheDocument();
  });

  it('sends the search term and status filter to the API', async () => {
    const requests: URL[] = [];
    server.use(
      http.get('/api/v1/payments', ({ request }) => {
        requests.push(new URL(request.url));
        return HttpResponse.json(samplePayments);
      }),
    );
    renderWithProviders(<PaymentSearchPage />);
    await screen.findByText('2 payments found');

    await userEvent.type(screen.getByLabelText(/payment or customer reference/i), 'CUS-00002');
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'FAILED');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));

    await vi.waitFor(() => {
      const last = requests.at(-1)!;
      expect(last.searchParams.get('q')).toBe('CUS-00002');
      expect(last.searchParams.get('status')).toBe('FAILED');
    });
  });

  it('rejects invalid characters before calling the API', async () => {
    let calls = 0;
    server.use(
      http.get('/api/v1/payments', () => {
        calls += 1;
        return HttpResponse.json(samplePayments);
      }),
    );
    renderWithProviders(<PaymentSearchPage />);
    await screen.findByText('2 payments found');
    const callsBefore = calls;

    await userEvent.type(screen.getByLabelText(/payment or customer reference/i), "x' OR 1=1");
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/letters, digits and hyphens/i);
    expect(calls).toBe(callsBefore);
  });

  it('shows an empty state', async () => {
    server.use(
      http.get('/api/v1/payments', () =>
        HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      ),
    );
    renderWithProviders(<PaymentSearchPage />);

    expect(await screen.findByText('0 payments found')).toBeInTheDocument();
  });

  it('surfaces API errors', async () => {
    server.use(
      http.get('/api/v1/payments', () =>
        HttpResponse.json({ title: 'Forbidden', status: 403 }, { status: 403 }),
      ),
    );
    renderWithProviders(<PaymentSearchPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not load payments/i);
  });
});
