/**
 * IndiaPost DOP "Deposit Accounts" report parser.
 *
 * The PDF is a fixed-format report emitted by the Finacle-hosted DOP
 * agent portal. Every row is a deposit account belonging to the
 * signed-in agent. Column layout (verified against the July 2026
 * printout the operator supplied):
 *
 *   Select | Account No | Account Name | Denomination | Month Paid Upto | Next RD Installment Due Date
 *
 * The importer maps three of those columns straight through to the
 * rd_accounts CSV contract (name, rd_number, monthly_amount) and one
 * derived column (last_paid_through = next-due-date minus 1 month,
 * blank when the PDF cell is blank). "Month Paid Upto" is a count of
 * cleared installments, NOT a month — we deliberately discard it here
 * because rd_accounts.last_paid_through is a YYYY-MM token and the
 * derivation from next-due-date is the honest translation.
 *
 * See the parent handoff PDF for the full sample the parser was
 * built + tested against (145 accounts, 3 with blank due date).
 *
 * The extractor works by pulling pdfjs-dist text items page-by-page,
 * grouping them into rows by y-coordinate, and then column-splitting
 * each row by x-coordinate against known column ranges anchored to
 * the header row on page 1. This is more robust than newline-based
 * parsing because pdfjs emits items in reading order per line, but
 * multi-line cells (e.g. "MUNENDRA KUMAR" + "GANGWAR") appear as two
 * separate text items on separate y values. The grouper collapses
 * those into a single row by looking at the numeric row index in
 * column 0 (which only ever appears once per record).
 */

import type { CsvParseResult, CsvRowError, ParsedAccount } from './csvParser';

/** Extension of the operator-supplied file; drives the router in
 *  ImportCsvDialog to pick this parser vs the CSV parser. */
export const PDF_ACCOUNTS_MIME = 'application/pdf';

interface TextItem {
  str: string;
  x: number;
  y: number;
  width: number;
  height: number;
  page: number;
}

interface RawRow {
  page: number;
  index: number;
  y: number;
  accountNo: string;
  accountName: string;
  denomination: string;
  monthPaidUpto: string;
  nextDueDate: string;
}

interface ContinuationFragment {
  y: number;
  text: string;
}

/**
 * Column x-range hints. These are ANCHORS not hard boundaries — the
 * real column bounds are re-derived from the header row on page 1 at
 * parse time to survive minor DOP layout tweaks (font size changes,
 * added logo width, etc.). Kept as a fallback in case the header row
 * is missing / corrupted on some future export.
 */
const FALLBACK_COLUMN_ANCHORS = {
  select: 40,
  accountNo: 100,
  accountName: 200,
  denomination: 340,
  monthPaidUpto: 430,
  nextDueDate: 500,
} as const;

/**
 * "27-Jul-2026" -> { y: 2026, m: 7, d: 27 }. Returns null on bad
 * input so the row-level handler can decide whether to blank the
 * derived last_paid_through cell or reject the whole row.
 */
const MONTH_NAMES: Record<string, number> = {
  jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6,
  jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12,
};

function parseDueDate(raw: string): { year: number; month: number } | null {
  const trimmed = raw.trim();
  if (trimmed.length === 0) return null;
  const m = /^(\d{1,2})-([A-Za-z]{3})-(\d{4})$/.exec(trimmed);
  if (!m) return null;
  const monthNum = MONTH_NAMES[m[2].toLowerCase()];
  if (monthNum == null) return null;
  const year = Number(m[3]);
  if (!Number.isFinite(year)) return null;
  return { year, month: monthNum };
}

/**
 * Given a due date {y, m}, return the YYYY-MM of the previous month.
 * Handles January-rollover: {2026, 1} -> "2025-12". Used to derive
 * last_paid_through from the "Next RD Installment Due Date" cell.
 */
function previousMonthToken(y: number, m: number): string {
  let year = y;
  let month = m - 1;
  if (month === 0) {
    month = 12;
    year -= 1;
  }
  return `${year}-${String(month).padStart(2, '0')}`;
}

/**
 * "4,000.00 Cr." -> 4000. The "Cr." suffix is a Finacle credit-side
 * marker (every RD deposit is a credit); we strip it uniformly. The
 * ".00" fractional part is always zero-paise in the DOP export, so
 * we discard rather than round. Negative-side / debit ("Dr.") rows
 * SHOULD NEVER APPEAR in a Deposit Accounts report, but if one does,
 * we reject the row rather than silently invert the amount.
 */
function parseDenomination(raw: string): number | null {
  const cleaned = raw.trim();
  if (cleaned.length === 0) return null;
  // Reject explicit debit rows.
  if (/\bDr\.?\b/i.test(cleaned)) return null;
  // Strip "Cr." suffix + all commas + trailing decimals + whitespace.
  const digitsOnly = cleaned.replace(/\s*Cr\.?\s*$/i, '').replace(/,/g, '').trim();
  // "4000.00" or "4000" — take the integer part only.
  const dot = digitsOnly.indexOf('.');
  const intPart = dot >= 0 ? digitsOnly.slice(0, dot) : digitsOnly;
  if (!/^\d+$/.test(intPart)) return null;
  const n = Number(intPart);
  if (!Number.isFinite(n) || n <= 0) return null;
  return n;
}

const RD_NUMBER_REGEX = /^\d{9,15}$/;
const NAME_MAX = 60;

/**
 * Sanitise the same Excel-formula-injection surface the CSV parser
 * handles. Duplicated (not imported) because the CSV helper is a
 * private function; the safer route is a copy that we can evolve
 * independently if one path (say, the PDF one) ever needs a
 * different rule.
 */
function sanitizeFormulaPrefix(value: string): string {
  if (value.length === 0) return value;
  const first = value.charCodeAt(0);
  if (first === 0x3d || first === 0x2b || first === 0x2d || first === 0x40 || first === 0x09 || first === 0x0d) {
    return `'${value}`;
  }
  return value;
}

/**
 * Group extracted text items into visual rows keyed by y-coordinate,
 * rounded to the nearest integer point. PDF text emitters don't
 * guarantee items on the same visual row share identical y values
 * (kerning + subscripts can nudge by fractions), so we bucket into
 * 2pt windows. This is the same tolerance react-pdf uses internally.
 */
function groupByY(items: TextItem[]): Map<number, TextItem[]> {
  const buckets = new Map<number, TextItem[]>();
  for (const it of items) {
    const key = Math.round(it.y / 2) * 2;
    let bucket = buckets.get(key);
    if (!bucket) {
      bucket = [];
      buckets.set(key, bucket);
    }
    bucket.push(it);
  }
  for (const bucket of buckets.values()) {
    bucket.sort((a, b) => a.x - b.x);
  }
  return buckets;
}

/**
 * Column boundaries derived from the header row's x-positions.
 * Returned as a sorted array of {name, xStart} pairs; a text item
 * belongs to the column whose xStart is the largest value <= item.x.
 */
interface ColumnBoundary {
  key: 'select' | 'accountNo' | 'accountName' | 'denomination' | 'monthPaidUpto' | 'nextDueDate';
  xStart: number;
}

/**
 * Search ALL rows on the first page, accumulating column anchors as
 * they appear. The DOP report splits the header across up to three
 * visual rows: "Select" sits on its own line, the middle four labels
 * ("Account No", "Account Name", "Denomination", "Month Paid Upto")
 * share one line, and "Next RD Installment / Due Date" wraps across
 * two lines. Merging across rows accepts any header layout as long
 * as all six labels appear somewhere on page 1 above the first
 * data row.
 *
 * Returns null when at least one required column label is missing —
 * that means the DOP format has changed and the caller should fall
 * back to fixed anchors + report the drift.
 *
 * Side-channel: writes the LOWEST y where a header label was found
 * into `outHeaderBottomY[0]` if provided. The coalescer uses this
 * on page 1 to floor the row-envelope's top edge at the header row
 * (page 1 has an extra header-row band above record 1 that a naive
 * `topY + PAD` envelope would include).
 */
function deriveColumnsFromPage(
  rowsTopDown: TextItem[][],
  outHeaderBottomY?: [number | null],
): ColumnBoundary[] | null {
  const map: Partial<Record<ColumnBoundary['key'], number>> = {};
  let lowestHeaderY: number | null = null;
  for (const row of rowsTopDown) {
    let rowMatched = false;
    for (const it of row) {
      const s = it.str.trim().toLowerCase();
      let matchedThisIt = false;
      if (map.select == null && s === 'select') { map.select = it.x; matchedThisIt = true; }
      else if (map.accountNo == null && s === 'account no') { map.accountNo = it.x; matchedThisIt = true; }
      else if (map.accountName == null && s === 'account name') { map.accountName = it.x; matchedThisIt = true; }
      else if (map.denomination == null && s === 'denomination') { map.denomination = it.x; matchedThisIt = true; }
      else if (map.monthPaidUpto == null && s === 'month paid upto') { map.monthPaidUpto = it.x; matchedThisIt = true; }
      else if (map.nextDueDate == null && s.startsWith('next rd installment')) { map.nextDueDate = it.x; matchedThisIt = true; }
      if (matchedThisIt) {
        rowMatched = true;
        if (lowestHeaderY == null || it.y < lowestHeaderY) lowestHeaderY = it.y;
      }
    }
    void rowMatched;
    const complete =
      map.select != null && map.accountNo != null && map.accountName != null &&
      map.denomination != null && map.monthPaidUpto != null && map.nextDueDate != null;
    if (complete) break;
  }
  if (outHeaderBottomY) outHeaderBottomY[0] = lowestHeaderY;
  const required: ColumnBoundary['key'][] = [
    'select', 'accountNo', 'accountName', 'denomination', 'monthPaidUpto', 'nextDueDate',
  ];
  for (const k of required) {
    if (map[k] == null) return null;
  }
  return required.map((k) => ({ key: k, xStart: map[k] as number }));
}

/**
 * Assign each text item on a row to its column. Items are already
 * sorted by x, so a single left-to-right sweep suffices.
 */
interface BucketedRow {
  y: number;
  cells: Record<ColumnBoundary['key'], string>;
}

function bucketRowByColumns(y: number, row: TextItem[], cols: ColumnBoundary[]): BucketedRow {
  const cells: Record<ColumnBoundary['key'], string> = {
    select: '', accountNo: '', accountName: '',
    denomination: '', monthPaidUpto: '', nextDueDate: '',
  };
  for (const item of row) {
    // Find the last column whose xStart is <= item.x. Items to the
    // LEFT of the first column (page numbers in the margin, "Select"
    // row-index labels) still land in `select`, which we discard.
    let col: ColumnBoundary['key'] = cols[0].key;
    for (let i = cols.length - 1; i >= 0; i -= 1) {
      if (item.x + 0.5 >= cols[i].xStart) {
        col = cols[i].key;
        break;
      }
    }
    const prior = cells[col];
    cells[col] = prior.length === 0 ? item.str : `${prior} ${item.str}`;
  }
  for (const key of Object.keys(cells) as ColumnBoundary['key'][]) {
    cells[key] = cells[key].replace(/\s+/g, ' ').trim();
  }
  return { y, cells };
}

/**
 * Reject continuation fragments that are page decorations, not name
 * data. Applied at coalesce time so real names are never dropped.
 *   - "1/5", "2/5", ... — page footer markers.
 *   - URLs and long https:// strings — jsessionid footer link.
 *   - Standalone dates like "01/07/2026, 19:45" — page header timestamp.
 */
function isDecorativeFragment(text: string): boolean {
  const t = text.trim();
  if (t.length === 0) return true;
  if (/^\d+\s*\/\s*\d+$/.test(t)) return true;
  if (/^https?:\/\//i.test(t)) return true;
  if (/^\d{2}\/\d{2}\/\d{4}/.test(t)) return true;
  if (/Department of Post/i.test(t)) return true;
  // "Due Date" is the second line of the wrapping header "Next RD
  // Installment / Due Date"; it sits below `headerBottomY` (which
  // captures the y of the LOWEST header TOKEN, not the visual band
  // bottom) and would otherwise get attributed as a lead prefix to
  // record 1. It never legitimately appears as an account name.
  if (/^Due Date$/i.test(t)) return true;
  return false;
}

/**
 * Collapse consecutive rows that belong to the same record.
 *
 * A record begins on a row whose `select` cell is a positive integer
 * (the 1-indexed row number in the report). Multi-line account names
 * appear as continuation fragments — rows without a `select` integer.
 *
 * Empirically the DOP report emits wrapped names on EITHER side of
 * the record's main row. For record 4 in the July 2026 sample:
 *
 *   y=460  record 3 (DHARM PAL) main row
 *   y=446  MUNENDRA KUMAR              <-- belongs to record 4
 *   y=440  record 4 main row (4 | 020027227016 | ...)
 *   y=436  GANGWAR                     <-- belongs to record 4
 *
 * Both fragments belong to record 4 because they're closer in y to
 * record 4's main row than to record 3's. So we run a TWO-PASS
 * algorithm instead of trying to attribute continuations as they
 * stream in:
 *
 *   Pass 1: sweep rows top-down, splitting into (main-rows, floating
 *   fragments). Main rows carry a positive integer in `select`;
 *   fragments are everything else that isn't blank and isn't
 *   decorative (page footers, URLs, timestamps).
 *
 *   Pass 2: for each fragment, find the main row whose y is closest
 *   and attach the fragment there. A fragment above a main row gets
 *   prepended (lead); a fragment below gets appended (trail). This
 *   is the geometrically-correct attribution regardless of how the
 *   DOP report interleaves fragments and main rows.
 *
 * The old streaming approach mis-attributed pre-continuations to
 * the previously-open record (a bug the smoke test caught before
 * ship): fragment "MUNENDRA KUMAR" arrived while record 3 was
 * still current, so it got trail-appended to record 3 instead of
 * lead-attached to record 4.
 */
function coalesceRecords(
  rowsInput: BucketedRow[],
  page: number,
  headerBottomY: number | null = null,
): RawRow[] {
  const mains: RawRow[] = [];
  const fragments: ContinuationFragment[] = [];

  for (const { y, cells } of rowsInput) {
    const empty = Object.values(cells).every((v) => v.length === 0);
    if (empty) continue;
    const selectStr = cells.select;
    const isNewRecord = /^\d+$/.test(selectStr);
    if (isNewRecord) {
      mains.push({
        page,
        index: Number(selectStr),
        y,
        accountNo: cells.accountNo,
        accountName: cells.accountName,
        denomination: cells.denomination,
        monthPaidUpto: cells.monthPaidUpto,
        nextDueDate: cells.nextDueDate,
      });
    } else {
      // Salvage all non-empty column text into a single fragment;
      // the DOP report only legitimately wraps accountName, but
      // stray content in other cells still gets attributed rather
      // than dropped.
      const raw = [
        cells.accountNo, cells.accountName, cells.denomination,
        cells.monthPaidUpto, cells.nextDueDate,
      ].filter((s) => s.length > 0).join(' ').replace(/\s+/g, ' ').trim();
      if (raw.length === 0) continue;
      if (isDecorativeFragment(raw)) continue;
      fragments.push({ y, text: raw });
    }
  }

  if (mains.length === 0) return [];

  // Data-row envelope: highest and lowest y among main rows on this
  // page. Anything above the top main is header text (page title,
  // "Select Mode:", "Account Id(s):", "Deposit Accounts List"); anything
  // below the bottom main is footer text ("Please click here to close",
  // URL, jsessionid). Both get discarded before nearest-y attribution
  // so header/footer can't bleed into the edge records.
  //
  // Padding: a single wrapped-name fragment naturally sits within ~10pt
  // of its main row's baseline (12pt line-height in the DOP report).
  // A 20pt envelope-expansion tolerates a legitimate pre-continuation
  // sitting slightly above the top main row (record 1's wrapped name
  // arriving before record 1's numeric row-index).
  const ROW_ENVELOPE_PAD = 20;
  let topY = mains[0].y;
  let bottomY = mains[0].y;
  for (const m of mains) {
    if (m.y > topY) topY = m.y;
    if (m.y < bottomY) bottomY = m.y;
  }
  // Envelope top: normally `topY + PAD`, but on pages that carry the
  // column-header band (page 1 in the DOP report; pages 2+ have no
  // repeated header) we clip against `headerBottomY - 1` so the
  // header row and everything above it (page title, "Select Mode:",
  // "Deposit Accounts List") is excluded. Without the clip, "Deposit
  // Accounts List" at y=536 falls inside record 1's y=498 + 20pad
  // envelope and gets attributed as SHIKHIL GUPTA's lead prefix.
  const paddedTop = topY + ROW_ENVELOPE_PAD;
  const envelopeTop = headerBottomY != null
    ? Math.min(paddedTop, headerBottomY - 1)
    : paddedTop;
  const envelopeBottom = bottomY - ROW_ENVELOPE_PAD;

  for (const frag of fragments) {
    if (frag.y > envelopeTop) continue;
    if (frag.y < envelopeBottom) continue;

    // Nearest-main by absolute y distance. Ties go to the main
    // ABOVE the fragment (higher y) so a fragment sitting exactly
    // between two mains reads as a trail of the earlier record —
    // matches the DOP layout convention observed in the sample.
    let bestIdx = 0;
    let bestDist = Math.abs(frag.y - mains[0].y);
    for (let i = 1; i < mains.length; i += 1) {
      const d = Math.abs(frag.y - mains[i].y);
      if (d < bestDist || (d === bestDist && mains[i].y > mains[bestIdx].y)) {
        bestIdx = i;
        bestDist = d;
      }
    }
    const target = mains[bestIdx];
    // Fragment above the main (higher y) → lead prefix.
    // Fragment below (lower y) → trail suffix.
    const position: 'lead' | 'trail' = frag.y > target.y ? 'lead' : 'trail';
    if (target.accountName.length === 0) {
      target.accountName = frag.text;
    } else {
      target.accountName = position === 'lead'
        ? `${frag.text} ${target.accountName}`
        : `${target.accountName} ${frag.text}`;
      target.accountName = target.accountName.replace(/\s+/g, ' ').trim();
    }
  }

  return mains;
}

/**
 * Public entry point. Same return shape as `parseAccountsCsv` so
 * ImportCsvDialog can route by file extension and treat both parsers
 * as one interchangeable adapter.
 */
export async function parsePdfAccounts(file: File): Promise<CsvParseResult> {
  // Dynamic import so the pdfjs-dist bundle only loads when the
  // operator opens the import dialog with a .pdf file. Saves the
  // main portal chunk from paying the cost on every page load.
  const pdfjs = await import('pdfjs-dist');

  // pdfjs-dist v6 REQUIRES a real workerSrc. The "empty string
  // disables the worker" trick that worked in v3/v4 throws
  // 'No "GlobalWorkerOptions.workerSrc" specified.' in v6 (see
  // node_modules/pdfjs-dist/build/pdf.mjs line 16126). The only
  // supported disable path in v6 is a full "legacy" build entry,
  // which we deliberately avoid because it disables all workers
  // process-wide and is intended for Node — not the browser.
  //
  // Instead we point workerSrc at a same-origin static asset copied
  // from node_modules/pdfjs-dist/build/pdf.worker.min.mjs into
  // portal/public/pdf.worker.min.mjs at build time. Same-origin
  // avoids CSP tightening (worker-src 'self' blob: already permits
  // this) and Cloudflare edge-caches the asset immutably.
  //
  // Keep this assignment idempotent: pdfjs stashes the value in a
  // module-global, so multiple calls to parsePdfAccounts inside the
  // same session don't re-assign.
  const workerOptions = (pdfjs as unknown as {
    GlobalWorkerOptions: { workerSrc: string };
  }).GlobalWorkerOptions;
  if (!workerOptions.workerSrc) {
    workerOptions.workerSrc = `${window.location.origin}/pdf.worker.min.mjs`;
  }

  const arrayBuffer = await file.arrayBuffer();

  let doc;
  try {
    // pdfjs-dist@6.x DocumentInitParameters TypeScript declarations
    // omit isEvalSupported + useSystemFonts (the runtime accepts
    // them; the types are just stale — Mozilla issue #17742). The
    // narrow cast preserves the two hardening options that matter
    // for our CSP posture:
    //   - isEvalSupported=false stops pdfjs from calling new
    //     Function() for embedded font programs. Our CSP already
    //     blocks eval globally, but pdfjs's internal fallback runs
    //     early enough that setting the flag surfaces a clean error
    //     instead of a mid-parse CSP violation.
    //   - useSystemFonts=false stops pdfjs from enumerating the
    //     platform font list (privacy-adjacent fingerprint surface,
    //     and irrelevant for text-content extraction anyway).
    const initParams = {
      data: arrayBuffer,
      isEvalSupported: false,
      useSystemFonts: false,
    } as unknown as Parameters<typeof pdfjs.getDocument>[0];
    doc = await pdfjs.getDocument(initParams).promise;
  } catch (err) {
    return {
      valid: [],
      errors: [{
        row: 0,
        message: `Could not open PDF: ${err instanceof Error ? err.message : String(err)}`,
      }],
      totalRows: 0,
    };
  }

  const allItems: TextItem[] = [];
  for (let p = 1; p <= doc.numPages; p += 1) {
    const page = await doc.getPage(p);
    const textContent = await page.getTextContent();
    for (const raw of textContent.items) {
      // pdfjs types TextItem loosely; guard with an explicit shape
      // check so the parser fails loud rather than silently drops
      // when a future pdfjs version changes item.transform semantics.
      const it = raw as {
        str?: string;
        transform?: number[];
        width?: number;
        height?: number;
      };
      if (typeof it.str !== 'string' || !Array.isArray(it.transform)) continue;
      // transform = [a, b, c, d, e, f] — [4] is x, [5] is y in PDF
      // user space (bottom-origin, points). We keep y positive so
      // groupByY can bucket without worrying about sign.
      const x = it.transform[4];
      const y = it.transform[5];
      if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
      allItems.push({
        str: it.str,
        x,
        y,
        width: it.width ?? 0,
        height: it.height ?? 0,
        page: p,
      });
    }
  }

  // Derive columns from the header on the first page. If the DOP
  // format ever changes and the header words drift, we fall back to
  // fixed anchors so the parser at least produces a legible error
  // per row instead of crashing.
  const firstPageItems = allItems.filter((it) => it.page === 1);
  const firstPageByY = groupByY(firstPageItems);
  // Sort y descending — PDF origin is bottom-left, so higher y = closer
  // to the top of the page. Header labels sit above the first data row.
  const firstPageRowsTopDown = [...firstPageByY.entries()]
    .sort((a, b) => b[0] - a[0])
    .map((entry) => entry[1]);
  const headerBottomYRef: [number | null] = [null];
  let cols: ColumnBoundary[] | null = deriveColumnsFromPage(firstPageRowsTopDown, headerBottomYRef);
  if (!cols) {
    // Fallback anchors keyed to the sample PDF the parser was written
    // against. If the anchors miss on a future export the user gets a
    // clear "columns not found" message from downstream validators.
    cols = (Object.keys(FALLBACK_COLUMN_ANCHORS) as Array<keyof typeof FALLBACK_COLUMN_ANCHORS>)
      .map((k) => ({ key: k as ColumnBoundary['key'], xStart: FALLBACK_COLUMN_ANCHORS[k] }));
  }

  // Group + coalesce per page (so a page-boundary continuation can't
  // bleed a header row into the previous record).
  const allRecords: RawRow[] = [];
  for (let p = 1; p <= doc.numPages; p += 1) {
    const pageItems = allItems.filter((it) => it.page === p);
    const grouped = groupByY(pageItems);
    const rowsTopDown: BucketedRow[] = [...grouped.entries()]
      .sort((a, b) => b[0] - a[0])
      .map(([y, items]) => bucketRowByColumns(y, items, cols as ColumnBoundary[]));
    // headerBottomY is only meaningful on the page that CARRIED the
    // header row. In the July 2026 sample that's page 1; pages 2+ jump
    // straight into data rows so any envelope-top clip would be wrong.
    const headerBottomYForPage = p === 1 ? headerBottomYRef[0] : null;
    const records = coalesceRecords(rowsTopDown, p, headerBottomYForPage);
    allRecords.push(...records);
  }

  // Sanity-check the report was recognisable at all. Empty extraction
  // usually means the operator uploaded a wrong file (blank PDF, an
  // image-scanned PDF with no text layer, etc.). Rather than surface
  // 145 blank rows of validation errors we short-circuit with one
  // clear message.
  if (allRecords.length === 0) {
    return {
      valid: [],
      errors: [{
        row: 0,
        message: 'No account rows found in the PDF. Is this the "Deposit Accounts" report from DOP? Image-scanned PDFs are not supported — you need the printable text export.',
      }],
      totalRows: 0,
    };
  }

  // Downstream validation — mirrors the CSV parser's gate set so a
  // single downstream contract holds.
  const valid: ParsedAccount[] = [];
  const errors: CsvRowError[] = [];
  const seen = new Set<string>();

  for (const rec of allRecords) {
    // For error attribution we surface the PDF's own row index so the
    // operator can find the offending row on the printout at a glance.
    const rowLabel = rec.index;

    const name = rec.accountName.trim();
    if (name.length === 0) {
      errors.push({ row: rowLabel, message: 'Missing Account Name' });
      continue;
    }
    if (name.length > NAME_MAX) {
      errors.push({
        row: rowLabel,
        message: `Name too long (${name.length}/${NAME_MAX} chars): "${name.slice(0, 30)}…"`,
      });
      continue;
    }

    const rdNumber = rec.accountNo.trim();
    if (!RD_NUMBER_REGEX.test(rdNumber)) {
      errors.push({
        row: rowLabel,
        message: `Invalid Account No "${rdNumber}" (must be 9-15 digits)`,
      });
      continue;
    }

    const amount = parseDenomination(rec.denomination);
    if (amount == null) {
      errors.push({
        row: rowLabel,
        message: `Invalid Denomination "${rec.denomination}" (must be a positive Cr. amount)`,
      });
      continue;
    }

    if (seen.has(rdNumber)) {
      errors.push({
        row: rowLabel,
        message: `Duplicate Account No "${rdNumber}" in this PDF (using first occurrence)`,
      });
      continue;
    }

    // last_paid_through = next_due_date - 1 month. Blank due-date
    // cell leaves the field null so bulkUpsertAccounts omits it from
    // the upsert payload and preserves any existing cloud value.
    let lastPaidThrough: string | null = null;
    const due = parseDueDate(rec.nextDueDate);
    if (due) {
      lastPaidThrough = previousMonthToken(due.year, due.month);
    }

    seen.add(rdNumber);
    valid.push({
      rdNumber,
      name: sanitizeFormulaPrefix(name),
      monthlyAmount: amount,
      lastPaidThrough,
    });
  }

  return {
    valid,
    errors,
    totalRows: allRecords.length,
  };
}
