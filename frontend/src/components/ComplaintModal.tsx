import { useState } from 'react';

interface ComplaintModalProps {
  complaintText: string;
  onClose: () => void;
}

export function ComplaintModal({ complaintText, onClose }: ComplaintModalProps) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(complaintText);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Fallback for non-secure contexts
      const ta = document.createElement('textarea');
      ta.value = complaintText;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Generated complaint">
        <div className="modal__header">
          <h3 className="modal__title">📋 RBI Complaint — Ready to File</h3>
          <button className="modal__close" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <div className="modal__body">
          <div className="complaint-text">{complaintText}</div>
        </div>
        <div className="modal__actions">
          <button className="btn-secondary" onClick={onClose}>
            Close
          </button>
          <button className="btn-primary" onClick={handleCopy}>
            {copied ? '✓ Copied!' : '📋 Copy to Clipboard'}
          </button>
        </div>
      </div>
    </div>
  );
}
