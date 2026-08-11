import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { apiFetch } from '../../api/client';
import { useAuth } from '../../context/AuthContext';

export function SignUp() {
  const [businessName, setBusinessName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  
  const navigate = useNavigate();
  const { login } = useAuth();

  const handleSignUp = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!businessName.trim() || !email.trim() || !password.trim()) {
      setError('All fields are required.');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const res = await apiFetch<{ token: string }>('/auth/signup', {
        method: 'POST',
        body: JSON.stringify({ businessName, email, password }),
      });
      login(res.token);
      navigate('/onboarding');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sign up failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="onboarding">
      <div className="onboarding__form-wrapper" style={{ marginTop: '4rem' }}>
        <div className="onboarding__form-card">
          <div className="onboarding__form-header">
            <span className="onboarding__form-icon">✨</span>
            <div>
              <h3 className="onboarding__form-title">Create Account</h3>
              <p className="onboarding__form-subtitle">
                Start automating your UPI refund tracking.
              </p>
            </div>
          </div>

          <form onSubmit={handleSignUp} className="onboarding__fields">
            <label className="onboarding__field">
              <span className="onboarding__field-label">Business Name</span>
              <input
                type="text"
                className="onboarding__input"
                placeholder="Acme Corp"
                value={businessName}
                onChange={(e) => setBusinessName(e.target.value)}
                autoFocus
              />
            </label>
            <label className="onboarding__field">
              <span className="onboarding__field-label">Email</span>
              <input
                type="email"
                className="onboarding__input"
                placeholder="you@company.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
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
              disabled={loading || !businessName.trim() || !email.trim() || !password.trim()}
            >
              {loading ? (
                <>
                  <span className="spinner spinner--sm" />
                  Creating Account…
                </>
              ) : (
                'Sign Up'
              )}
            </button>
            <div style={{ textAlign: 'center', marginTop: '16px', fontSize: '14px' }}>
              <span style={{ color: 'var(--text-muted)' }}>Already have an account? </span>
              <Link to="/login" style={{ color: 'var(--gold-400)', textDecoration: 'none' }}>
                Sign In
              </Link>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
