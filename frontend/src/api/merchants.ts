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
  webhookSecret: string;
}

export function connectMerchant(
  request: MerchantConnectRequest
): Promise<MerchantConnectResponse> {
  return apiFetch<MerchantConnectResponse>('/merchants/connect', {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

export function getConnectedGateways(): Promise<string[]> {
  return apiFetch<string[]>('/merchants/connected-gateways');
}
