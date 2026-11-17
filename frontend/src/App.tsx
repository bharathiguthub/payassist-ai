import { Navigate, Route, Routes, useSearchParams } from 'react-router';
import { useCurrentUser } from './auth/auth';
import { RequireRole } from './auth/RequireRole';
import { Layout } from './components/Layout';
import { AuditPage } from './pages/AuditPage';
import { LoginPage } from './pages/LoginPage';
import { NoAccessPage } from './pages/NoAccessPage';
import { PaymentDetailPage } from './pages/PaymentDetailPage';
import { PaymentSearchPage } from './pages/PaymentSearchPage';
import { SupervisorPage } from './pages/SupervisorPage';

export function App() {
  const { data: user, isPending, isError } = useCurrentUser();
  const [params] = useSearchParams();

  if (isPending) return <p className="centered">Loading…</p>;
  if (isError) {
    return (
      <p className="centered error" role="alert">
        PayAssist is unavailable. Please try again shortly.
      </p>
    );
  }
  if (!user) return <LoginPage error={params.get('error')} />;
  if (user.roles.length === 0) return <NoAccessPage user={user} />;

  return (
    <Routes>
      <Route element={<Layout user={user} />}>
        <Route index element={<Navigate to="/payments" replace />} />
        <Route path="payments" element={<PaymentSearchPage />} />
        <Route path="payments/:paymentRef" element={<PaymentDetailPage />} />
        <Route
          path="supervisor"
          element={
            <RequireRole roles={['CARE_SUPERVISOR', 'ADMIN']}>
              <SupervisorPage />
            </RequireRole>
          }
        />
        <Route
          path="admin/audit"
          element={
            <RequireRole roles={['ADMIN']}>
              <AuditPage />
            </RequireRole>
          }
        />
        <Route path="*" element={<Navigate to="/payments" replace />} />
      </Route>
    </Routes>
  );
}
