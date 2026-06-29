import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import { AuthProvider } from './lib/auth';
import { ErrorBoundary } from './components/ErrorBoundary';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      // retry: 1 with default exponential backoff (1s, 5s) covers
      // transient 401s after a TOKEN_REFRESHED race + brief network
      // blips. The second retry uses the freshly-refreshed token via
      // the shared supabase client.
      retry: 1,
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 5000),
      // QC R1 LOW L1: useRealtimeSync already invalidates every active
      // query on the visibilitychange 'visible' event, so leaving
      // refetchOnWindowFocus on top duplicated every fetch on tab
      // focus. The realtime path is more authoritative (it also
      // covers reconnect after a long sleep) so this turns off.
      refetchOnWindowFocus: false,
      // offlineFirst lets cached views render immediately when the
      // tab regains focus on a slow/paused Supabase project — the
      // refetch runs in the background instead of blocking the UI
      // behind a spinner.
      networkMode: 'offlineFirst',
    },
  },
});

const rootEl = document.getElementById('root');
if (!rootEl) {
  throw new Error('Root element missing — index.html should contain <div id="root"></div>');
}

ReactDOM.createRoot(rootEl).render(
  <React.StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AuthProvider>
            <App />
          </AuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </ErrorBoundary>
  </React.StrictMode>
);
