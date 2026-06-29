import { type ReactNode } from 'react';

/**
 * Designer loader system: three variants share the same visual
 * vocabulary so loading states across the portal feel cohesive.
 *
 *  - <FullPageLoader /> — centred orbit + caption, used for route
 *    loads and the auth-check spinner.
 *  - <InlineLoader />   — compact orbit for in-button or in-card
 *    states. `size="sm"` (16px) for button labels, `size="md"`
 *    (28px) for inline card spinners.
 *  - <SkeletonCard />   — rounded skeleton with a traversing shimmer
 *    band. `count` controls the stack length, `height` matches the
 *    target row height so the skeleton occupies the same vertical
 *    space as the real content (no layout jump on resolve).
 *
 * The orbit is two concentric SVG arcs rotating in opposite
 * directions at different speeds with a breathing dot at the centre
 * — the asymmetry is what reads as "designed" rather than the stock
 * single-arc browser spinner.
 *
 * a11y: every loader exposes `role="status"` + `aria-live="polite"`
 * + a visually-hidden text label so screen readers announce the
 * loading state. Caller can override the label via the `label` prop.
 */

interface OrbitProps {
  pixelSize: number;
}

function Orbit({ pixelSize }: OrbitProps) {
  // Stroke geometry: outer arc 270deg, inner arc 210deg — partial
  // sweeps so the orbit reads as motion (full circles would look
  // static at a glance). Stroke width scales linearly with size
  // capped at 3px so a 16px loader stays crisp.
  const stroke = Math.max(2, Math.min(3, pixelSize / 8));
  const outerR = (pixelSize - stroke) / 2;
  const innerR = outerR - stroke - 2;
  const outerCirc = 2 * Math.PI * outerR;
  const innerCirc = 2 * Math.PI * innerR;
  const outerDash = `${outerCirc * 0.75} ${outerCirc * 0.25}`;
  const innerDash = `${innerCirc * 0.58} ${innerCirc * 0.42}`;
  return (
    <span
      className="relative inline-block"
      style={{ width: pixelSize, height: pixelSize }}
      aria-hidden="true"
    >
      <svg
        viewBox={`0 0 ${pixelSize} ${pixelSize}`}
        className="absolute inset-0 animate-loader-spin-cw"
      >
        <circle
          cx={pixelSize / 2}
          cy={pixelSize / 2}
          r={outerR}
          fill="none"
          stroke="currentColor"
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={outerDash}
          className="text-primary"
        />
      </svg>
      <svg
        viewBox={`0 0 ${pixelSize} ${pixelSize}`}
        className="absolute inset-0 animate-loader-spin-ccw"
      >
        <circle
          cx={pixelSize / 2}
          cy={pixelSize / 2}
          r={innerR}
          fill="none"
          stroke="currentColor"
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={innerDash}
          className="text-primary/40"
        />
      </svg>
      {pixelSize >= 28 && (
        <span
          className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 animate-loader-pulse rounded-full bg-primary"
          style={{ width: pixelSize / 6, height: pixelSize / 6 }}
        />
      )}
    </span>
  );
}

/**
 * Themed full-page loader: drifting orange/peach gradient backdrop,
 * three floating bubbles (each on its own keyframe so the motion
 * never looks synchronized), and the orbit + label hosted on a
 * frosted card. Used for route loads and the auth-check spinner.
 *
 * The bubbles are absolutely positioned `aria-hidden` decorative
 * blurs; they live behind the card so the page reads as branded
 * without competing with the actual progress signal. `min-h-screen`
 * + `overflow-hidden` keeps the bubbles clipped during their float
 * even when the loader hosts inside a `<Suspense>` boundary that
 * happens to sit below the AppShell header.
 */
export function FullPageLoader({ label = 'Loading' }: { label?: string }) {
  return (
    <div
      role="status"
      aria-live="polite"
      className="relative flex min-h-[70vh] items-center justify-center overflow-hidden rounded-3xl bg-[length:200%_200%] bg-gradient-to-br from-primary/15 via-accent-coral/10 to-accent-mint/15 animate-gradient-drift"
    >
      <Bubble
        size={220}
        className="left-[-60px] top-[10%] bg-primary/30 animate-bubble-a"
      />
      <Bubble
        size={320}
        className="right-[-100px] top-[55%] bg-accent-mint/25 animate-bubble-b"
      />
      <Bubble
        size={180}
        className="left-[35%] bottom-[-40px] bg-accent-coral/30 animate-bubble-c"
      />

      <div className="relative z-10 flex flex-col items-center gap-5 rounded-3xl border border-white/60 bg-white/70 px-10 py-9 shadow-elevated backdrop-blur-xl">
        <Orbit pixelSize={72} />
        <div className="text-center">
          <p className="text-base font-semibold tracking-tight text-ink-primary">
            {label}
          </p>
          <p className="mt-1 text-xs font-medium uppercase tracking-[0.18em] text-ink-muted">
            One moment
          </p>
          <span className="sr-only">, please wait</span>
        </div>
      </div>
    </div>
  );
}

function Bubble({ size, className }: { size: number; className: string }) {
  return (
    <span
      aria-hidden="true"
      className={[
        'pointer-events-none absolute rounded-full blur-3xl',
        className,
      ].join(' ')}
      style={{ width: size, height: size }}
    />
  );
}

export function InlineLoader({
  size = 'md',
  label = 'Loading',
  className,
}: {
  size?: 'sm' | 'md';
  label?: string;
  className?: string;
}) {
  const pixelSize = size === 'sm' ? 16 : 28;
  return (
    <span
      role="status"
      aria-live="polite"
      className={['inline-flex items-center', className ?? ''].join(' ')}
    >
      <Orbit pixelSize={pixelSize} />
      <span className="sr-only">{label}</span>
    </span>
  );
}

interface SkeletonCardProps {
  count?: number;
  /**
   * Tailwind h-* value to match the row height of the real content.
   * Pass the actual numeric height (e.g. 14 → h-14) — the component
   * picks a fixed inline height to avoid Tailwind purging unknown
   * dynamic classes.
   */
  heightPx?: number;
  rounded?: 'lg' | 'xl' | '2xl';
  label?: string;
  className?: string;
}

/**
 * Lightweight dashboard-shaped skeleton used as the Suspense fallback
 * for the lazy Dashboard route. Lives in Loader.tsx (not Dashboard.tsx)
 * so the synchronous fallback path does NOT pull in the lazy Recharts
 * chunk — otherwise Suspense would suspend on its own fallback, which
 * is a tightly-undefined React behaviour and caused CLS in QC R1.
 */
export function DashboardRouteSkeleton() {
  return (
    <div className="space-y-8" role="status" aria-live="polite">
      <div className="flex items-end justify-between">
        <div>
          <div className="h-7 w-44 animate-loader-shimmer rounded-lg bg-surface-alt" />
          <div className="mt-2 h-4 w-72 animate-loader-shimmer rounded-md bg-surface-alt" />
        </div>
        <div className="h-9 w-48 animate-loader-shimmer rounded-pill bg-surface-alt" />
      </div>
      <SkeletonCard count={4} heightPx={104} rounded="2xl" />
      <SkeletonCard count={2} heightPx={300} rounded="2xl" />
      <span className="sr-only">Loading dashboard</span>
    </div>
  );
}

export function SkeletonCard({
  count = 6,
  heightPx = 56,
  rounded = 'xl',
  label = 'Loading content',
  className,
}: SkeletonCardProps) {
  const roundedClass =
    rounded === '2xl' ? 'rounded-2xl' : rounded === 'lg' ? 'rounded-lg' : 'rounded-xl';
  // Items rendered with index-key are fine here: skeletons are
  // ephemeral, never reordered, and the array length is the only
  // dimension that changes between renders.
  const items: ReactNode[] = [];
  for (let i = 0; i < count; i += 1) {
    items.push(
      <div
        key={i}
        className={[
          'relative overflow-hidden border border-surface-border bg-surface-alt',
          roundedClass,
        ].join(' ')}
        style={{ height: heightPx }}
      >
        <div
          className="absolute inset-y-0 -inset-x-1/2 animate-loader-shimmer bg-gradient-to-r from-transparent via-white/60 to-transparent"
        />
      </div>,
    );
  }
  return (
    <div
      role="status"
      aria-live="polite"
      className={['flex flex-col gap-2', className ?? ''].join(' ')}
    >
      {items}
      <span className="sr-only">{label}</span>
    </div>
  );
}
