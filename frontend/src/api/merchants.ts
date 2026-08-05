import { apiFetch } from './client';

export interface MerchantConnectRequest {
  gateway: string;
  apiKey: string;
  apiSecret: string;
  name?: string;
}

export interface MerchantConnectResponse {
  merchantId: string;
  webhookUrl: string;
}

export function connectMerchant(
  request: MerchantConnectRequest
): Promise<MerchantConnectResponse> {
  return apiFetch<MerchantConnectResponse>('/merchants/connect', {
    method: 'POST',
    body: JSON.stringify(request),
  });
}
