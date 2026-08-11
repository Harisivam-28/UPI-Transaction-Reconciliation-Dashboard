import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { apiFetch } from '../../api/client';
import { useAuth } from '../../context/AuthContext';

export function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  
  const navigate = useNavigate();
  const { login } = useAuth();

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim() || !password.trim()) {
      setError('Email and password are required.');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const res = await apiFetch<{ token: string }>('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password }),
      });
      login(res.token);
      navigate('/');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed. Please check your credentials.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="onboarding">
      <div className="onboarding__form-wrapper" style={{ marginTop: '4rem' }}>
        <div className="onboarding__form-card">
          <div className="onboarding__form-header">
            <span className="onboarding__form-icon">🔐</span>
            <div>
              <h3 className="onboarding__form-title">Welcome Back</h3>
              <p className="onboarding__form-subtitle">
                Sign in to manage your UPI recoveries.
              </p>
            </div>
          </div>

          <form onSubmit={handleLogin} className="onboarding__fields">
            <label className="onboarding__field">
              <span className="onboarding__field-label">Email</span>
              <input
                type="email"
                className="onboarding__input"
                placeholder="you@company.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoFocus
              />
            </label>
            <label className="onboarding__field">
              <span className="onboarding__field-label">Password</span>
              <input
                type="password"
                className="onboarding__input"
                placeholder="••••••••••••••••"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>

            {error && (
              <div className="onboarding__error">
                <span>⚠</span> {error}
              </div>
            )}

            <button
              type="submit"
              className="btn-primary btn-primary--full"
              disabled={loading || !email.trim() || !password.trim()}
            >
              {loading ? (
                <>
                  <span className="spinner spinner--sm" />
                  Signing In…
                </>
              ) : (
                'Sign In'
              )}
            </button>
            <div style={{ textAlign: 'center', marginTop: '16px', fontSize: '14px' }}>
              <span style={{ color: 'var(--text-muted)' }}>Don't have an account? </span>
              <Link to="/signup" style={{ color: 'var(--gold-400)', textDecoration: 'none' }}>
                Sign Up
              </Link>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
