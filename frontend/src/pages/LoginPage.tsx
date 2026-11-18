import { login } from '../auth/auth';

export function LoginPage({ error }: { error?: string | null }) {
  return (
    <main className="login">
      <section className="card login-card">
        <h1>
          PayAssist <span>AI</span>
        </h1>
        <p>Customer care workspace for payment investigations.</p>
        {error && (
          <p className="error" role="alert">
            Sign-in failed. Please try again.
          </p>
        )}
        <button type="button" onClick={login}>
          Sign in with company SSO
        </button>
        <small>Authentication is handled by Keycloak. PayAssist never sees your password.</small>
      </section>
    </main>
  );
}
