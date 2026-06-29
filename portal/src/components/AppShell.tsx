import { NavLink, useNavigate } from 'react-router-dom';
import { type ReactNode } from 'react';
import { useAuth } from '../lib/useAuth';

const navItems = [
  { to: '/sessions', label: 'Sessions' },
  { to: '/search', label: 'Search' },
  { to: '/accounts', label: 'Accounts' },
  { to: '/activity', label: 'Activity' },
  { to: '/devices', label: 'Devices' },
];

export function AppShell({ children }: { children: ReactNode }) {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();

  const handleSignOut = async () => {
    await signOut();
    navigate('/signin', { replace: true });
  };

  return (
    <div className="min-h-screen">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-pill focus:bg-primary focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:text-white focus:shadow-card"
      >
        Skip to main content
      </a>
      <header className="sticky top-0 z-30 border-b border-surface-border bg-surface/85 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-7xl items-center gap-6 px-4 sm:px-6 lg:px-8">
          <NavLink to="/sessions" className="flex items-center gap-2">
            <span className="grid h-9 w-9 place-items-center rounded-xl bg-primary text-white shadow-card">
              <span className="text-base font-bold">RD</span>
            </span>
            <span className="hidden text-sm font-semibold text-ink-primary sm:inline">
              Scanner Portal
            </span>
          </NavLink>

          <nav className="ml-2 flex items-center gap-1">
            {navItems.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) =>
                  [
                    'rounded-pill px-3.5 py-1.5 text-sm font-medium transition-colors',
                    isActive
                      ? 'bg-primary/10 text-primary-dark'
                      : 'text-ink-secondary hover:bg-surface-alt hover:text-ink-primary',
                  ].join(' ')
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-3">
            {user?.email && (
              <span className="hidden text-xs text-ink-secondary sm:inline">
                {user.email}
              </span>
            )}
            <button
              type="button"
              onClick={handleSignOut}
              className="rounded-pill border border-surface-border px-3 py-1.5 text-xs font-medium text-ink-secondary transition-colors hover:border-ink-secondary hover:text-ink-primary"
            >
              Sign out
            </button>
          </div>
        </div>
      </header>

      <main id="main" className="mx-auto max-w-7xl px-4 py-6 sm:px-6 sm:py-8 lg:px-8">
        {children}
      </main>
    </div>
  );
}
