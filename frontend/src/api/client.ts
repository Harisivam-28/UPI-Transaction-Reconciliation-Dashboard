const BASE = '/api';

export async function apiFetch<T>(
  path: string,
  options?: RequestInit
): Promise<T> {
  const token = localStorage.getItem('token');
  const headers: HeadersInit = {
    'Content-Type': 'application/json',
  };

  if (token && !path.startsWith('/auth/login') && !path.startsWith('/auth/signup')) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const res = await fetch(`${BASE}${path}`, {
    headers: {
      ...headers,
      ...options?.headers,
    },
    ...options,
  });

  if (!res.ok) {
    if (res.status === 401) {
      window.dispatchEvent(new Event('auth:unauthorized'));
    }
    throw new Error(`API ${res.status}: ${res.statusText}`);
  }

  return res.json() as Promise<T>;
}
