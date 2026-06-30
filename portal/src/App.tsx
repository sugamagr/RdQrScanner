import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './lib/useAuth';
import { useRealtimeSync } from './lib/useRealtimeSync';
import { SignInPage } from './pages/SignIn';
import { SessionsPage } from './pages/Sessions';
import { SessionDetailPage } from './pages/SessionDetail';
import { DevicesPage } from './pages/Devices';
import { SearchPage } from './pages/Search';
import { AccountsPage } from './pages/Accounts';
import { ActivityPage } from './pages/Activity';
import { AppShell } from './components/AppShell';
import { DashboardRouteSkeleton, FullPageLoader } from './components/Loader';

// Dashboard ships Recharts (~300KB) which makes the route the heaviest
// in the app. Lazy-loading splits it into its own chunk so the rest of
// the portal first-paint stays lean for power users who jump straight
// to /sessions or /accounts.
const DashboardPage = lazy(() =>
  import('./pages/Dashboard').then((m) => ({ default: m.DashboardPage })),
);

export default function App() {
  const { session, loading } = useAuth();

  if (loading) {
    return <FullPageLoader label="Signing you in" />;
  }

  if (!session) {
    return (
      <Routes>
        <Route path="/signin" element={<SignInPage />} />
        <Route path="*" element={<DeepLinkSignInRedirect />} />
      </Routes>
    );
  }

  return <AuthedRoot />;
}

// Preserves the requested URL across the auth gate so a bookmarked
// /sessions/<id> link returns the user to that detail page after
// sign-in instead of dumping them at /sessions. SignIn.tsx already
// reads location.state.from on successful auth.
function DeepLinkSignInRedirect() {
  const location = useLocation();
  return (
    <Navigate
      to="/signin"
      replace
      state={{ from: `${location.pathname}${location.search}${location.hash}` }}
    />
  );
}

function AuthedRoot() {
  useRealtimeSync();
  return (
    <AppShell>
      <Routes>
        <Route
          path="/"
          element={
            <Suspense fallback={<DashboardRouteSkeleton />}>
              <DashboardPage />
            </Suspense>
          }
        />
        <Route path="/sessions" element={<SessionsPage />} />
        <Route path="/sessions/:sessionId" element={<SessionDetailPage />} />
        <Route path="/search" element={<SearchPage />} />
        <Route path="/accounts" element={<AccountsPage />} />
        <Route path="/activity" element={<ActivityPage />} />
        <Route path="/devices" element={<DevicesPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AppShell>
  );
}
