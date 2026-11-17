import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router';
import { PaymentDetailPage } from './PaymentDetailPage';
import { renderWithProviders } from '../test/render';
import { loggedInAs, server } from '../test/server';
import { sampleDetails, sampleSummary, sampleTimeline } from '../test/investigation-fixtures';

function renderDetail(ref = 'PAY-000001') {
  return renderWithProviders(
    <Routes>
      <Route path="/payments/:paymentRef" element={<PaymentDetailPage />} />
    </Routes>,
    { route: `/payments/${ref}` },
  );
}

function stubInvestigationApis() {
  server.use(
    http.get('/api/v1/payments/PAY-000001', () => HttpResponse.json(sampleDetails)),
    http.get('/api/v1/payments/PAY-000001/timeline', () => HttpResponse.json(sampleTimeline)),
    http.get('/api/v1/payments/PAY-000001/investigation-summary', () => HttpResponse.json(sampleSummary)),
  );
}

describe('PaymentDetailPage investigation', () => {
  it('shows the timeline in the order returned, with amounts, reasons and correlation IDs', async () => {
    stubInvestigationApis();
    renderDetail();

    const timeline = await screen.findByRole('list', { name: /events for PAY-000001, oldest first/i });
    const items = within(timeline).getAllByRole('listitem');
    expect(items.map((li) => within(li).getByText(/^(Created|Dispute updated|Refund recorded)$/).textContent)).toEqual([
      'Created',
      'Dispute updated',
      'Refund recorded',
    ]);
    expect(items).toHaveLength(3);
    const [created, late, refund] = items as [HTMLElement, HTMLElement, HTMLElement];
    expect(within(refund).getByText('US$20.00')).toBeInTheDocument();
    expect(within(refund).getByText(/Reason CUSTOMER_REQUEST: Requested by the customer\./)).toBeInTheDocument();
    expect(within(refund).getByText(/correlation corr-abc-123/)).toBeInTheDocument();
    expect(within(late).getByText('Recorded late')).toBeInTheDocument();
    expect(within(created).queryByText('Recorded late')).not.toBeInTheDocument();
  });

  it('shows deterministic findings with the events they cite', async () => {
    stubInvestigationApis();
    renderDetail();

    const findings = await screen.findByRole('list', { name: 'Findings' });
    expect(within(findings).getByText(/1 refund\(s\) totalling 20.00 USD recorded/)).toBeInTheDocument();
    expect(within(findings).getByText('(events #2)')).toBeInTheDocument();
    expect(screen.getByText(/No inference or prediction\./)).toBeInTheDocument();
    expect(screen.getByText('Refund recorded, Dispute opened')).toBeInTheDocument();
  });

  it('shows an explicit empty state when nothing needs attention', async () => {
    stubInvestigationApis();
    server.use(
      http.get('/api/v1/payments/PAY-000001/investigation-summary', () =>
        HttpResponse.json({ ...sampleSummary, findings: [] }),
      ),
    );
    renderDetail();

    expect(await screen.findByText(/No findings/)).toBeInTheDocument();
  });

  it('reports a timeline error without hiding the rest of the page', async () => {
    stubInvestigationApis();
    server.use(
      http.get('/api/v1/payments/PAY-000001/timeline', () =>
        HttpResponse.json({ title: 'Forbidden', status: 403 }, { status: 403 }),
      ),
    );
    renderDetail();

    expect(await screen.findByText('You do not have permission to view this timeline.')).toBeInTheDocument();
    expect(await screen.findByRole('list', { name: 'Findings' })).toBeInTheDocument();
    expect(screen.getByText('Demo Travel Co')).toBeInTheDocument();
  });

  it('does not request the timeline or summary when the payment does not exist', async () => {
    let investigationCalls = 0;
    server.use(
      http.get('/api/v1/payments/PAY-999999', () =>
        HttpResponse.json({ title: 'Resource not found', status: 404 }, { status: 404 }),
      ),
      http.get('/api/v1/payments/PAY-999999/:part', () => {
        investigationCalls += 1;
        return HttpResponse.json({});
      }),
    );
    renderDetail('PAY-999999');

    expect(await screen.findByText('Payment not found.')).toBeInTheDocument();
    expect(investigationCalls).toBe(0);
    expect(screen.queryByText('Timeline')).not.toBeInTheDocument();
  });

  it('shows loading states while data is fetched', async () => {
    stubInvestigationApis();
    renderDetail();
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(await screen.findByText('Loading timeline…')).toBeInTheDocument();
  });

  it('offers the refund request form to agents but not to read-only admins', async () => {
    stubInvestigationApis();
    loggedInAs(['CARE_AGENT']);
    const { unmount } = renderDetail();
    expect(await screen.findByRole('heading', { name: 'Request a refund' })).toBeInTheDocument();
    expect(await screen.findByText('No refund requests for this payment.')).toBeInTheDocument();
    unmount();

    loggedInAs(['ADMIN']);
    renderDetail();
    expect(await screen.findByRole('heading', { name: 'Refund requests' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Request a refund' })).not.toBeInTheDocument();
  });
});
