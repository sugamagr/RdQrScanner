import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type DragEvent,
} from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Download, FileSpreadsheet, Loader2, Upload, X } from 'lucide-react';
import {
  bulkUpsertAccounts,
  type BulkUpsertResult,
} from '../lib/queries';
import {
  downloadAccountsCsvTemplate,
  parseAccountsCsv,
  type CsvParseResult,
} from '../lib/csvParser';

interface Props {
  ownerId: string;
  onClose: () => void;
  onImported: (summary: string) => void;
}

/**
 * CSV bulk upload modal. Reads a 3-column file
 * (name, rd_number, monthly_amount), parses with strict validation,
 * lets the operator preview valid + invalid counts (first 5 errors
 * shown inline, rest collapsible), then performs per-row upserts.
 *
 * All rows are stamped source = 'CSV' + is_active = true server-side;
 * LWW makes the upload win over any prior operator edit on the same
 * rd_number per the CSV-wins contract (user pick, oracle-spec C18).
 */
export function ImportCsvDialog({ ownerId, onClose, onImported }: Props) {
  const qc = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [parseResult, setParseResult] = useState<CsvParseResult | null>(null);
  const [showAllErrors, setShowAllErrors] = useState(false);
  const [isDragging, setIsDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeBtnRef = useRef<HTMLButtonElement>(null);
  // P6γ LOW + MEDIUM cancellation: mountedRef short-circuits stale
  // setState after unmount (CSV parse is async via PapaParse); the
  // AbortController fires on unmount to cancel the bulkUpsert loop
  // (already-uploaded rows stay committed — CSV is idempotent).
  // C2-P6 MEDIUM in-flight guard: synchronous ref short-circuits
  // rapid double-clicks on the Import button before isPending
  // propagates from useMutation (React batches setState).
  const mountedRef = useRef(true);
  const abortRef = useRef<AbortController | null>(null);
  const uploadInFlightRef = useRef(false);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      abortRef.current?.abort();
    };
  }, []);

  const handleFile = async (picked: File | null) => {
    if (!mountedRef.current) return;
    setFile(picked);
    setParseResult(null);
    setShowAllErrors(false);
    if (picked) {
      // Hard 5 MB cap. PapaParse loads the entire file into memory and
      // a 100 MB CSV would freeze the browser tab for 10-30 seconds
      // before the synchronous parse completes. 5 MB is ~50,000 rows
      // of realistic CSV — well beyond the 200 accounts/month user
      // workload but a reasonable safety net against accidental drag-
      // and-drop of the wrong file.
      const MAX_BYTES = 5 * 1024 * 1024;
      if (picked.size > MAX_BYTES) {
        setParseResult({
          valid: [],
          errors: [
            {
              row: 0,
              message: `File too large (${Math.round(picked.size / 1024 / 1024)} MB). Maximum is 5 MB.`,
            },
          ],
          totalRows: 0,
        });
        return;
      }
      const result = await parseAccountsCsv(picked);
      if (mountedRef.current) setParseResult(result);
    }
  };

  const onDragOver = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'copy';
    if (!isDragging) setIsDragging(true);
  };
  const onDragLeave = (e: DragEvent<HTMLDivElement>) => {
    if (e.currentTarget.contains(e.relatedTarget as Node | null)) return;
    setIsDragging(false);
  };
  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragging(false);
    const dropped = e.dataTransfer.files?.[0];
    if (dropped && /\.csv$/i.test(dropped.name)) {
      void handleFile(dropped);
    }
  };

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    closeBtnRef.current?.focus();
    const focusableSelector =
      'button:not([disabled]), [href], input:not([disabled])';
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
        return;
      }
      if (e.key !== 'Tab') return;
      const root = dialogRef.current;
      if (!root) return;
      const focusables = Array.from(root.querySelectorAll<HTMLElement>(focusableSelector))
        .filter((el) => el.offsetParent !== null);
      if (focusables.length === 0) return;
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
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

  const onFileChange = async (e: ChangeEvent<HTMLInputElement>) => {
    const picked = e.target.files?.[0] ?? null;
    await handleFile(picked);
  };

  const upload = useMutation<BulkUpsertResult>({
    mutationFn: async () => {
      if (!parseResult) throw new Error('Parse the file first');
      const ac = new AbortController();
      abortRef.current = ac;
      try {
        return await bulkUpsertAccounts(parseResult.valid, ownerId, ac.signal);
      } finally {
        if (abortRef.current === ac) abortRef.current = null;
      }
    },
    onSuccess: (result) => {
      if (!mountedRef.current) return;
      qc.invalidateQueries({ queryKey: ['accounts'] });
      const parts = [`Imported ${result.inserted}`];
      const skipped = parseResult ? parseResult.errors.length : 0;
      if (skipped > 0) parts.push(`skipped ${skipped} invalid`);
      if (result.failed > 0) parts.push(`${result.failed} failed`);
      onImported(parts.join(' · '));
      onClose();
    },
  });

  const canUpload =
    parseResult != null && parseResult.valid.length > 0 && !upload.isPending;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="csv-import-title"
      className="fixed inset-0 z-50 flex items-end justify-center bg-ink-primary/40 p-0 backdrop-blur-sm sm:items-center sm:p-4"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        className="flex max-h-[100dvh] w-full max-w-lg flex-col overflow-hidden rounded-t-2xl bg-surface shadow-elevated sm:max-h-[90dvh] sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="flex items-start justify-between border-b border-surface-border px-5 py-4">
          <div>
            <h2 id="csv-import-title" className="text-base font-semibold text-ink-primary">
              Import accounts from CSV
            </h2>
            <p className="mt-0.5 text-xs text-ink-secondary">
              Three required columns: <span className="font-mono">name</span>,{' '}
              <span className="font-mono">rd_number</span>,{' '}
              <span className="font-mono">monthly_amount</span>.
            </p>
          </div>
          <button
            ref={closeBtnRef}
            type="button"
            onClick={onClose}
            className="rounded-lg p-1 text-ink-secondary hover:bg-surface-alt hover:text-ink-primary"
            aria-label="Close"
          >
            <X className="h-5 w-5" />
          </button>
        </header>

        <div className="flex-1 space-y-4 overflow-y-auto px-5 py-5">
          <button
            type="button"
            onClick={downloadAccountsCsvTemplate}
            className="inline-flex items-center gap-1.5 rounded-pill border border-surface-border bg-surface-alt px-3 py-1.5 text-xs font-medium text-ink-secondary hover:border-primary/40 hover:text-primary"
          >
            <Download className="h-3.5 w-3.5" />
            Download template
          </button>

          <div
            onDragOver={onDragOver}
            onDragLeave={onDragLeave}
            onDrop={onDrop}
            className={`rounded-2xl border-2 border-dashed p-6 text-center transition-colors ${
              isDragging
                ? 'border-primary bg-primary/5'
                : 'border-surface-border bg-surface-alt'
            }`}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv,text/csv"
              onChange={onFileChange}
              className="sr-only"
              id="csv-file-input"
            />
            <FileSpreadsheet
              aria-hidden="true"
              className={`mx-auto h-8 w-8 transition-colors ${
                isDragging ? 'text-primary' : 'text-ink-muted'
              }`}
            />
            <p className="mt-2 text-xs text-ink-secondary">
              {isDragging
                ? 'Drop your CSV file to load it'
                : 'Drag a CSV file here, or'}
            </p>
            <label
              htmlFor="csv-file-input"
              className="mt-2 inline-flex cursor-pointer items-center gap-1.5 rounded-pill bg-primary px-4 py-2 text-xs font-semibold text-white shadow-card transition-colors hover:bg-primary-dark"
            >
              <Upload className="h-3.5 w-3.5" />
              {file ? 'Choose a different file' : 'Choose CSV file'}
            </label>
            {file && (
              <p className="mt-2 text-xs text-ink-secondary">
                <span className="font-medium text-ink-primary">{file.name}</span>{' '}
                · {(file.size / 1024).toFixed(1)} KB
              </p>
            )}
          </div>

          {parseResult && (
            <div className="space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <div className="rounded-xl border border-surface-border bg-surface-alt px-3 py-2">
                  <p className="text-[10px] uppercase tracking-wider text-ink-muted">
                    Valid rows
                  </p>
                  <p className="text-lg font-semibold text-accent-mint-ink">
                    {parseResult.valid.length}
                  </p>
                </div>
                <div className="rounded-xl border border-surface-border bg-surface-alt px-3 py-2">
                  <p className="text-[10px] uppercase tracking-wider text-ink-muted">
                    Errors
                  </p>
                  <p
                    className={`text-lg font-semibold ${
                      parseResult.errors.length > 0 ? 'text-warn' : 'text-ink-muted'
                    }`}
                  >
                    {parseResult.errors.length}
                  </p>
                </div>
              </div>

              {parseResult.errors.length > 0 && (
                <details
                  open={showAllErrors}
                  onToggle={(e) => setShowAllErrors((e.target as HTMLDetailsElement).open)}
                  className="rounded-xl border border-warn/20 bg-warn/5 px-3 py-2"
                >
                  <summary className="cursor-pointer text-xs font-medium text-warn">
                    Show all {parseResult.errors.length} error
                    {parseResult.errors.length === 1 ? '' : 's'}
                  </summary>
                  <ul className="mt-2 space-y-1 text-[11px] text-warn">
                    {parseResult.errors.map((err, i) => (
                      <li key={`${err.row}-${i}`} className="font-mono">
                        Row {err.row}: {err.message}
                      </li>
                    ))}
                  </ul>
                </details>
              )}

              {parseResult.valid.length > 0 && (
                <div>
                  <p className="text-[10px] uppercase tracking-wider text-ink-muted">
                    Preview (first 5)
                  </p>
                  <ul className="mt-1 space-y-1 text-xs">
                    {parseResult.valid.slice(0, 5).map((row) => (
                      <li
                        key={row.rdNumber}
                        className="flex items-center justify-between rounded-lg bg-surface-alt px-2.5 py-1.5"
                      >
                        <span className="truncate font-medium text-ink-primary">
                          {row.name}
                        </span>
                        <span className="ml-2 shrink-0 font-mono text-ink-secondary">
                          {row.rdNumber} · ₹{row.monthlyAmount}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          )}

          {upload.isPending && parseResult && (
            <div
              role="progressbar"
              aria-label="Uploading accounts"
              className="space-y-1.5"
            >
              <div className="flex items-center justify-between text-[11px] text-ink-secondary">
                <span>
                  Importing {parseResult.valid.length} account
                  {parseResult.valid.length === 1 ? '' : 's'}…
                </span>
                <Loader2 className="h-3 w-3 animate-spin text-primary" />
              </div>
              <div className="h-1 overflow-hidden rounded-full bg-surface-alt">
                <div className="h-full w-1/3 animate-csv-progress rounded-full bg-primary" />
              </div>
            </div>
          )}

          {upload.isError && (
            <div className="rounded-xl border border-danger/20 bg-danger/5 px-3 py-2 text-xs text-danger">
              {upload.error instanceof Error
                ? upload.error.message
                : 'Upload failed.'}
            </div>
          )}
        </div>

        <footer className="flex items-center justify-end gap-2 border-t border-surface-border bg-surface-alt px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            disabled={upload.isPending}
            className="rounded-pill px-3.5 py-1.5 text-xs font-medium text-ink-secondary hover:text-ink-primary"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => {
              if (!canUpload || uploadInFlightRef.current) return;
              uploadInFlightRef.current = true;
              upload.mutate(undefined, {
                onSettled: () => {
                  uploadInFlightRef.current = false;
                },
              });
            }}
            disabled={!canUpload}
            className="inline-flex items-center gap-1.5 rounded-pill bg-primary px-4 py-1.5 text-xs font-semibold text-white shadow-card transition-colors hover:bg-primary-dark disabled:cursor-not-allowed disabled:opacity-50"
          >
            {upload.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
            {upload.isPending
              ? 'Importing…'
              : parseResult
                ? `Import ${parseResult.valid.length} account${parseResult.valid.length === 1 ? '' : 's'}`
                : 'Import'}
          </button>
        </footer>
      </div>
    </div>
  );
}
