import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
} from 'react';
import { Loader2, X } from 'lucide-react';
import type { DashboardRange, DashboardStats } from '../lib/dashboardQueries';
import { generateDashboardPdf, type PdfTheme, type PdfPaper, type PdfSection } from '../lib/pdfExport';

interface Props {
  stats: DashboardStats;
  range: DashboardRange;
  onClose: () => void;
}

interface SectionDef {
  key: PdfSection;
  label: string;
  description: string;
}

const SECTIONS: SectionDef[] = [
  { key: 'kpis', label: 'KPI summary', description: 'Headline counts and amounts' },
  { key: 'money', label: 'Money collected trend', description: 'Monthly money chart with values' },
  { key: 'currentVsDefault', label: 'Current vs default split', description: 'Active accounts breakdown' },
  { key: 'sessions', label: 'Sessions trend', description: 'Monthly session counts' },
  { key: 'scans', label: 'Scans & defaulters trend', description: 'Monthly scans with defaulter overlay' },
  { key: 'source', label: 'Account source mix', description: 'CSV vs manual share' },
  { key: 'amountHistogram', label: 'Amount distribution', description: 'Buckets by monthly amount' },
  { key: 'topDefaulters', label: 'Top defaulters', description: 'Top 10 by months overdue (masked)' },
  { key: 'activity', label: 'Recent activity', description: 'Latest 8 events' },
];

const DEFAULT_TITLE = 'RD Book — Dashboard report';

export function ExportPdfDialog({ stats, range, onClose }: Props) {
  const [title, setTitle] = useState<string>(DEFAULT_TITLE);
  const [subtitle, setSubtitle] = useState<string>(describeRange(range));
  const [enabled, setEnabled] = useState<Record<PdfSection, boolean>>(() => {
    const seed: Record<PdfSection, boolean> = {
      kpis: true, money: true, currentVsDefault: true, sessions: true, scans: true,
      source: true, amountHistogram: true, topDefaulters: true, activity: true,
    };
    return seed;
  });
  const [paper, setPaper] = useState<PdfPaper>('A4');
  const [theme, setTheme] = useState<PdfTheme>('light');
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const closeRef = useRef<HTMLButtonElement | null>(null);

  // Focus-trap + escape: same pattern as ImportCsvDialog so the
  // dialog stays accessible even when invoked from a chart card.
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    closeRef.current?.focus();
    const focusables = 'button:not([disabled]), [href], input:not([disabled])';
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
        return;
      }
      if (e.key !== 'Tab') return;
      const root = dialogRef.current;
      if (!root) return;
      const list = Array.from(root.querySelectorAll<HTMLElement>(focusables)).filter((el) => el.offsetParent !== null);
      if (list.length === 0) return;
      const first = list[0];
      const last = list[list.length - 1];
      const active = document.activeElement as HTMLElement | null;
      if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      opener?.focus();
    };
  }, [onClose]);

  const selectedCount = useMemo(
    () => SECTIONS.reduce((s, sec) => s + (enabled[sec.key] ? 1 : 0), 0),
    [enabled],
  );

  const toggle = useCallback(
    (k: PdfSection) => setEnabled((prev) => ({ ...prev, [k]: !prev[k] })),
    [],
  );

  const onTitleChange = (e: ChangeEvent<HTMLInputElement>) => setTitle(e.target.value);
  const onSubtitleChange = (e: ChangeEvent<HTMLInputElement>) => setSubtitle(e.target.value);

  const onGenerate = useCallback(async () => {
    setRunning(true);
    setError(null);
    try {
      const selected = SECTIONS.filter((s) => enabled[s.key]).map((s) => s.key);
      if (selected.length === 0) {
        setError('Select at least one section to include.');
        setRunning(false);
        return;
      }
      await generateDashboardPdf({
        stats,
        range,
        title: title.trim() || DEFAULT_TITLE,
        subtitle: subtitle.trim() || describeRange(range),
        sections: selected,
        paper,
        theme,
      });
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'PDF generation failed');
      setRunning(false);
    }
  }, [enabled, paper, theme, range, stats, title, subtitle, onClose]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="pdf-export-title"
      className="fixed inset-0 z-50 flex items-end justify-center bg-ink-primary/40 p-0 backdrop-blur-sm sm:items-center sm:p-4"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        className="flex max-h-[100dvh] w-full max-w-2xl flex-col overflow-hidden rounded-t-2xl bg-surface shadow-elevated sm:max-h-[92dvh] sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="flex items-start justify-between border-b border-surface-border px-5 py-4">
          <div>
            <h2 id="pdf-export-title" className="text-base font-semibold text-ink-primary">
              Export dashboard report (PDF)
            </h2>
            <p className="mt-0.5 text-xs text-ink-secondary">
              Pick sections, paper, and theme. Values are printed alongside every chart.
            </p>
          </div>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            className="rounded-lg p-1 text-ink-secondary hover:bg-surface-alt hover:text-ink-primary"
            aria-label="Close"
          >
            <X className="h-5 w-5" />
          </button>
        </header>

        <div className="flex-1 space-y-5 overflow-y-auto px-5 py-5">
          <section className="space-y-3">
            <div>
              <label className="block text-[10px] font-semibold uppercase tracking-wider text-ink-muted">
                Title
              </label>
              <input
                type="text"
                value={title}
                onChange={onTitleChange}
                placeholder={DEFAULT_TITLE}
                className="mt-1 w-full rounded-xl border border-surface-border bg-surface px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
            </div>
            <div>
              <label className="block text-[10px] font-semibold uppercase tracking-wider text-ink-muted">
                Subtitle
              </label>
              <input
                type="text"
                value={subtitle}
                onChange={onSubtitleChange}
                placeholder={describeRange(range)}
                className="mt-1 w-full rounded-xl border border-surface-border bg-surface px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
            </div>
          </section>

          <section>
            <p className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-ink-muted">
              Sections ({selectedCount} of {SECTIONS.length})
            </p>
            <ul className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
              {SECTIONS.map((s) => (
                <li key={s.key}>
                  <label
                    className={[
                      'flex cursor-pointer items-start gap-3 rounded-xl border px-3 py-2.5 transition-colors',
                      enabled[s.key]
                        ? 'border-primary/40 bg-primary/5'
                        : 'border-surface-border bg-surface hover:bg-surface-alt',
                    ].join(' ')}
                  >
                    <input
                      type="checkbox"
                      checked={enabled[s.key]}
                      onChange={() => toggle(s.key)}
                      className="mt-0.5 h-4 w-4 accent-primary"
                    />
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-ink-primary">{s.label}</p>
                      <p className="text-[11px] text-ink-secondary">{s.description}</p>
                    </div>
                  </label>
                </li>
              ))}
            </ul>
          </section>

          <section className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <p className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-ink-muted">Paper</p>
              <div className="inline-flex rounded-pill border border-surface-border bg-surface p-0.5 shadow-card">
                <PaperButton current={paper} value="A4" label="A4" onClick={setPaper} />
                <PaperButton current={paper} value="Letter" label="Letter" onClick={setPaper} />
              </div>
            </div>
            <div>
              <p className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-ink-muted">Theme</p>
              <div className="inline-flex rounded-pill border border-surface-border bg-surface p-0.5 shadow-card">
                <ThemeButton current={theme} value="light" label="Light" onClick={setTheme} />
                <ThemeButton current={theme} value="mono" label="Print mono" onClick={setTheme} />
              </div>
            </div>
          </section>

          {error && (
            <div className="rounded-xl border border-danger/20 bg-danger/5 px-3 py-2 text-xs text-danger" role="alert">
              {error}
            </div>
          )}
        </div>

        <footer className="flex items-center justify-end gap-2 border-t border-surface-border bg-surface-alt px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            disabled={running}
            className="rounded-pill px-3.5 py-1.5 text-xs font-medium text-ink-secondary hover:text-ink-primary"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={running || selectedCount === 0}
            onClick={() => void onGenerate()}
            className="inline-flex items-center gap-1.5 rounded-pill bg-primary px-4 py-1.5 text-xs font-semibold text-white shadow-card transition-colors hover:bg-primary-dark disabled:cursor-not-allowed disabled:opacity-50"
          >
            {running && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
            {running ? 'Generating…' : 'Download PDF'}
          </button>
        </footer>
      </div>
    </div>
  );
}

function PaperButton({
  current,
  value,
  label,
  onClick,
}: {
  current: PdfPaper;
  value: PdfPaper;
  label: string;
  onClick: (v: PdfPaper) => void;
}) {
  const active = current === value;
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={() => onClick(value)}
      className={[
        'min-h-[40px] rounded-pill px-3.5 py-2 text-xs font-semibold transition-colors',
        active ? 'bg-primary text-white shadow-card' : 'text-ink-secondary hover:text-ink-primary',
      ].join(' ')}
    >
      {label}
    </button>
  );
}

function ThemeButton({
  current,
  value,
  label,
  onClick,
}: {
  current: PdfTheme;
  value: PdfTheme;
  label: string;
  onClick: (v: PdfTheme) => void;
}) {
  const active = current === value;
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={() => onClick(value)}
      className={[
        'min-h-[40px] rounded-pill px-3.5 py-2 text-xs font-semibold transition-colors',
        active ? 'bg-primary text-white shadow-card' : 'text-ink-secondary hover:text-ink-primary',
      ].join(' ')}
    >
      {label}
    </button>
  );
}

function describeRange(range: DashboardRange): string {
  if (range === null) return 'All time';
  if (range === 3) return 'Last 3 months';
  if (range === 6) return 'Last 6 months';
  if (range === 12) return 'Last 12 months';
  if (range.kind === 'current-month') return 'Current month';
  return `${range.fromIso} to ${range.toIso}`;
}
