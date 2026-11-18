import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router';
import { ApiError } from './api/client';
import { App } from './App';
import './styles.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Don't retry auth/validation failures; they won't fix themselves.
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 2,
      refetchOnWindowFocus: false,
    },
  },
});

// A 401 from any API call means the server session expired: re-check who is logged in,
// which brings the user back to the login page.
queryClient.getQueryCache().subscribe((event) => {
  const error = event.query.state.error;
  if (event.type === 'updated' && error instanceof ApiError && error.status === 401 && event.query.queryKey[0] !== 'me') {
    void queryClient.invalidateQueries({ queryKey: ['me'] });
  }
});
// The same for form submissions (refund requests, approvals, dispute actions).
queryClient.getMutationCache().subscribe((event) => {
  const error = event.mutation?.state.error;
  if (event.type === 'updated' && error instanceof ApiError && error.status === 401) {
    void queryClient.invalidateQueries({ queryKey: ['me'] });
  }
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
