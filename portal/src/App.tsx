import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './lib/useAuth';
import { useRealtimeSync } from './lib/useRealtimeSync';
import { SignInPage } from './pages/SignIn';
import { SessionsPage } from './pages/Sessions';
import { SessionDetailPage } from './pages/SessionDetail';
import { DevicesPage } from './pages/Devices';
import { SearchPage } from './pages/Search';
import { AccountsPage } from './pages/Accounts';
import { AppShell } from './components/AppShell';

export default function App() {
  const { session, loading } = useAuth();

  if (loading) {
    return <FullPageSpinner />;
  }

  if (!session) {
    return (
      <Routes>
        <Route path="/signin" element={<SignInPage />} />
        <Route path="*" element={<Navigate to="/signin" replace />} />
      </Routes>
    );
  }

  return <AuthedRoot />;
}

function AuthedRoot() {
  useRealtimeSync();
  return (
    <AppShell>
      <Routes>
        <Route path="/sessions" element={<SessionsPage />} />
        <Route path="/sessions/:sessionId" element={<SessionDetailPage />} />
        <Route path="/search" element={<SearchPage />} />
        <Route path="/accounts" element={<AccountsPage />} />
        <Route path="/devices" element={<DevicesPage />} />
        <Route path="*" element={<Navigate to="/sessions" replace />} />
      </Routes>
    </AppShell>
  );
}

function FullPageSpinner() {
  return (
    <div className="flex min-h-screen items-center justify-center">
      <div className="h-10 w-10 animate-spin rounded-full border-4 border-primary/20 border-t-primary" />
    </div>
  );
}
