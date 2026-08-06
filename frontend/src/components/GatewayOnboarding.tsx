import { useState } from 'react';
import { connectMerchant } from '../api/merchants';
import type { MerchantConnectResponse } from '../api/merchants';

interface GatewayOnboardingProps {
  onConnected: () => void;
}

type GatewayId = 'razorpay' | 'payu' | 'cashfree';

interface GatewayInfo {
  id: GatewayId;
  name: string;
  icon: string;
  description: string;
  isSandbox: boolean;
}

const GATEWAYS: GatewayInfo[] = [
  {
    id: 'razorpay',
    name: 'Razorpay',
    icon: '⚡',
    description: 'Accept UPI, cards, wallets & more. India\'s most popular payment gateway.',
    isSandbox: false,
  },
  {
    id: 'payu',
    name: 'PayU',
    icon: '🔷',
    description: 'Enterprise payment solutions with multi-currency support.',
    isSandbox: true,
  },
  {
    id: 'cashfree',
    name: 'Cashfree',
    icon: '💚',
    description: 'Fast payouts, payment gateway, and banking APIs.',
    isSandbox: true,
  },
];

type Step = 'choose' | 'form' | 'success';

/**
 * Gateway onboarding flow — card-based chooser → credentials form → success with webhook URL + secret.
 * Matches the existing dashboard design system.
 */
export function GatewayOnboarding({ onConnected }: GatewayOnboardingProps) {
  const [step, setStep] = useState<Step>('choose');
  const [selectedGateway, setSelectedGateway] = useState<GatewayInfo | null>(null);
  const [apiKey, setApiKey] = useState('');
  const [apiSecret, setApiSecret] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<MerchantConnectResponse | null>(null);
  const [copiedUrl, setCopiedUrl] = useState(false);
  const [copiedSecret, setCopiedSecret] = useState(false);

  const handleSelectGateway = (gw: GatewayInfo) => {
    setSelectedGateway(gw);
    setApiKey('');
    setApiSecret('');
    setError(null);
    setStep('form');
  };

  const handleBack = () => {
    setStep('choose');
    setSelectedGateway(null);
    setError(null);
  };

  const handleConnect = async () => {
    if (!selectedGateway) return;
    if (!apiKey.trim() || !apiSecret.trim()) {
      setError('Both API Key and API Secret are required.');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const response = await connectMerchant({
        gateway: selectedGateway.id,
        apiKey: apiKey.trim(),
        apiSecret: apiSecret.trim(),
      });
      setResult(response);
      setStep('success');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Connection failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const copyToClipboard = async (text: string, setCopied: (v: boolean) => void) => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const ta = document.createElement('textarea');
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 2500);
  };

  return (
    <div className="onboarding">
      {/* ── Step 1: Gateway Chooser ──────────────────────── */}
      {step === 'choose' && (
        <div className="onboarding__chooser">
          <div className="onboarding__header">
            <h2 className="onboarding__title">Connect Your Payment Gateway</h2>
            <p className="onboarding__subtitle">
              Link your payment gateway to automatically track and recover stuck UPI payments.
            </p>
          </div>
          <div className="onboarding__cards">
            {GATEWAYS.map((gw) => (
              <button
                key={gw.id}
                id={`gateway-card-${gw.id}`}
                className="gateway-card"
                onClick={() => handleSelectGateway(gw)}
              >
                <div className="gateway-card__icon">{gw.icon}</div>
                <div className="gateway-card__content">
                  <div className="gateway-card__name-row">
                    <span className="gateway-card__name">{gw.name}</span>
                    {gw.isSandbox && (
                      <span className="gateway-card__sandbox-badge">Sandbox</span>
                    )}
                  </div>
                  <p className="gateway-card__desc">{gw.description}</p>
                </div>
                <div className="gateway-card__arrow">→</div>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* ── Step 2: Credentials Form ─────────────────────── */}
      {step === 'form' && selectedGateway && (
        <div className="onboarding__form-wrapper">
          <button className="onboarding__back" onClick={handleBack}>
            ← Back to gateways
          </button>
          <div className="onboarding__form-card">
            <div className="onboarding__form-header">
              <span className="onboarding__form-icon">{selectedGateway.icon}</span>
              <div>
                <div className="onboarding__form-title-row">
                  <h3 className="onboarding__form-title">Connect {selectedGateway.name}</h3>
                  {selectedGateway.isSandbox && (
                    <span className="gateway-card__sandbox-badge">Sandbox mode</span>
                  )}
                </div>
                <p className="onboarding__form-subtitle">
                  Enter your {selectedGateway.name} test-mode API credentials below.
                </p>
              </div>
            </div>

            <div className="onboarding__fields">
              <label className="onboarding__field">
                <span className="onboarding__field-label">API Key</span>
                <input
                  id="input-api-key"
                  type="text"
                  className="onboarding__input"
                  placeholder={`${selectedGateway.id === 'razorpay' ? 'rzp_test_' : ''}...`}
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                  autoFocus
                />
              </label>
              <label className="onboarding__field">
                <span className="onboarding__field-label">API Secret</span>
                <input
                  id="input-api-secret"
                  type="password"
                  className="onboarding__input"
                  placeholder="••••••••••••••••"
                  value={apiSecret}
                  onChange={(e) => setApiSecret(e.target.value)}
                />
              </label>
            </div>

            {error && (
              <div className="onboarding__error">
                <span>⚠</span> {error}
              </div>
            )}

            <button
              id="btn-connect-gateway"
              className="btn-primary btn-primary--full"
              onClick={handleConnect}
              disabled={loading || !apiKey.trim() || !apiSecret.trim()}
            >
              {loading ? (
                <>
                  <span className="spinner spinner--sm" />
                  Connecting…
                </>
              ) : (
                <>🔗 Connect {selectedGateway.name}</>
              )}
            </button>
          </div>
        </div>
      )}

      {/* ── Step 3: Success — Webhook URL + Secret ────────── */}
      {step === 'success' && selectedGateway && result && (
        <div className="onboarding__success-wrapper">
          <div className="onboarding__success-card">
            <div className="onboarding__success-icon">✓</div>
            <h3 className="onboarding__success-title">
              {selectedGateway.name} Connected!
            </h3>
            <p className="onboarding__success-subtitle">
              Copy both values below into your gateway's webhook settings to start
              receiving signed transaction events.
            </p>

            {/* Webhook URL */}
            <div className="onboarding__webhook-section">
              <span className="onboarding__field-label">Webhook URL</span>
              <div className="onboarding__webhook-url-row">
                <code className="onboarding__webhook-url">
                  {window.location.origin}{result.webhookUrl}
                </code>
                <button
                  id="btn-copy-webhook-url"
                  className="btn-secondary"
                  onClick={() => copyToClipboard(window.location.origin + result.webhookUrl, setCopiedUrl)}
                >
                  {copiedUrl ? '✓ Copied!' : '📋 Copy'}
                </button>
              </div>
            </div>

            {/* Webhook Secret */}
            <div className="onboarding__webhook-section">
              <span className="onboarding__field-label">Webhook Secret</span>
              <div className="onboarding__webhook-url-row">
                <code className="onboarding__webhook-url">
                  {result.webhookSecret}
                </code>
                <button
                  id="btn-copy-webhook-secret"
                  className="btn-secondary"
                  onClick={() => copyToClipboard(result.webhookSecret, setCopiedSecret)}
                >
                  {copiedSecret ? '✓ Copied!' : '📋 Copy'}
                </button>
              </div>
            </div>

            <p className="onboarding__webhook-instructions">
              Paste the Webhook URL into {selectedGateway.name} Dashboard → Settings → Webhooks,
              and paste the Webhook Secret into the same form's "Secret" field — this is required
              for {selectedGateway.name} to sign requests and for us to verify them.
            </p>

            <button
              id="btn-go-to-dashboard"
              className="btn-primary btn-primary--full"
              onClick={onConnected}
            >
              Go to Dashboard →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

