import Papa, { type ParseResult } from 'papaparse';

const RD_NUMBER_REGEX = /^\d{9,15}$/;

/**
 * YYYY-MM token format used by `rd_accounts.last_paid_through`. Matches
 * the phone-side [com.qrscanner.app.util.MonthYear] contract: lexical
 * comparison must equal chronological comparison, which requires a
 * zero-padded month and a 4-digit year. The regex rejects 2025-1,
 * 25-01, 2025-13, etc. — anything that would break monotonic ordering
 * downstream.
 */
const MONTH_TOKEN_REGEX = /^\d{4}-(0[1-9]|1[0-2])$/;

/**
 * Strip Excel formula prefixes from a name cell so a malicious CSV
 * with =cmd|'/c calc'!A1 (or similar) in the name field doesn't run
 * commands when the owner later re-exports to XLSX and opens in
 * Excel/Sheets. Prefixes any leading =, +, -, @, tab, or carriage
 * return with a single quote so the formula engine treats the cell
 * as literal text. Idempotent on already-prefixed values.
 *
 * The threat surface is downstream: our portal itself doesn't execute
 * the formula — but customers exporting + re-opening in Excel would.
 * The defense is at the ingest boundary so every downstream consumer
 * is protected by default.
 */
function sanitizeFormulaPrefix(value: string): string {
  if (value.length === 0) return value;
  const first = value.charCodeAt(0);
  // = + - @ \t \r
  if (first === 0x3d || first === 0x2b || first === 0x2d || first === 0x40 || first === 0x09 || first === 0x0d) {
    return `'${value}`;
  }
  return value;
}

interface RawCsvRow {
  name?: string;
  rd_number?: string;
  monthly_amount?: string;
  last_paid_through?: string;
}

export interface ParsedAccount {
  rdNumber: string;
  name: string;
  monthlyAmount: number;
  /**
   * Optional 4th column. `null` means the CSV row left the cell blank
   * (or the file omits the column entirely) — bulkUpsertAccounts will
   * NOT include last_paid_through in the upsert payload, so the
   * existing cloud value (if any) is preserved. A non-null value is
   * an EXPLICIT operator override; the writer pushes it as-is, no
   * monotonic guard. The dialog runs a regression check before
   * commit and asks the operator to confirm if any row would lower
   * an existing value.
   */
  lastPaidThrough: string | null;
}

export interface CsvRowError {
  row: number;
  message: string;
}

export interface CsvParseResult {
  valid: ParsedAccount[];
  errors: CsvRowError[];
  totalRows: number;
}

/**
 * Strict CSV parse for the rd_accounts bulk-upload contract.
 *
 * Required header columns (any order, case-insensitive, whitespace
 * stripped): name, rd_number, monthly_amount.
 *
 * Optional 4th column: last_paid_through (YYYY-MM token). When present
 * and non-blank, the parsed value reaches cloud as an explicit operator
 * override; when blank or absent, the cloud row's existing value is
 * preserved (the writer omits the field from the upsert payload).
 *
 * Reject rules:
 *   - missing required field (any of name / rd_number / monthly_amount blank)
 *   - rd_number not matching ^\d{9,15}$
 *   - monthly_amount not a positive integer (rejects 0, negative, float, NaN)
 *   - in-file duplicate rd_number (keep first occurrence, drop the rest)
 *   - last_paid_through present but not matching the YYYY-MM regex —
 *     the WHOLE row is rejected because silently dropping a typoed
 *     month would be worse than asking the operator to fix it.
 *
 * Row indices in errors are 1-indexed AND account for the header row,
 * so an error at "row 5" means line 5 of the file (line 1 = header,
 * line 2 = first data row).
 */
export function parseAccountsCsv(file: File): Promise<CsvParseResult> {
  return new Promise((resolve) => {
    Papa.parse<RawCsvRow>(file, {
      header: true,
      skipEmptyLines: 'greedy',
      transformHeader: (h) => h.trim().toLowerCase().replace(/\s+/g, '_'),
      transform: (v) => v.trim(),
      complete: (result: ParseResult<RawCsvRow>) => {
        const valid: ParsedAccount[] = [];
        const errors: CsvRowError[] = [];
        const seenRdNumbers = new Set<string>();

        for (const err of result.errors) {
          errors.push({
            row: (err.row ?? 0) + 2,
            message: err.message,
          });
        }

        result.data.forEach((raw, idx) => {
          const lineNumber = idx + 2;
          const name = raw.name ?? '';
          const rdNumber = raw.rd_number ?? '';
          const monthlyAmountRaw = raw.monthly_amount ?? '';

          if (!name || !rdNumber || !monthlyAmountRaw) {
            errors.push({
              row: lineNumber,
              message: 'Missing required field (name, rd_number, or monthly_amount)',
            });
            return;
          }
          if (name.length > 60) {
            errors.push({
              row: lineNumber,
              message: `Name too long (max 60 chars): "${name.slice(0, 30)}…"`,
            });
            return;
          }
          if (!RD_NUMBER_REGEX.test(rdNumber)) {
            errors.push({
              row: lineNumber,
              message: `Invalid rd_number "${rdNumber}" (must be 9-15 digits)`,
            });
            return;
          }
          const amount = Number(monthlyAmountRaw);
          if (!Number.isInteger(amount) || amount <= 0) {
            errors.push({
              row: lineNumber,
              message: `Invalid monthly_amount "${monthlyAmountRaw}" (must be a positive integer)`,
            });
            return;
          }
          if (seenRdNumbers.has(rdNumber)) {
            errors.push({
              row: lineNumber,
              message: `Duplicate rd_number "${rdNumber}" in this file (using first occurrence)`,
            });
            return;
          }
          // Optional column: empty string / absent column → null (skip).
          // Non-empty must match YYYY-MM exactly; we reject the row on
          // malformed input rather than silently dropping the value
          // because the operator's intent was clearly to set it.
          const lastPaidRaw = raw.last_paid_through ?? '';
          let lastPaidThrough: string | null = null;
          if (lastPaidRaw.length > 0) {
            if (!MONTH_TOKEN_REGEX.test(lastPaidRaw)) {
              errors.push({
                row: lineNumber,
                message: `Invalid last_paid_through "${lastPaidRaw}" (must be YYYY-MM, e.g. 2025-03)`,
              });
              return;
            }
            lastPaidThrough = lastPaidRaw;
          }
          seenRdNumbers.add(rdNumber);
          valid.push({
            rdNumber,
            name: sanitizeFormulaPrefix(name),
            monthlyAmount: amount,
            lastPaidThrough,
          });
        });

        resolve({
          valid,
          errors,
          totalRows: result.data.length,
        });
      },
      error: (err) => {
        resolve({
          valid: [],
          errors: [{ row: 0, message: err.message }],
          totalRows: 0,
        });
      },
    });
  });
}

/**
 * Triggers a CSV-template download via Blob + anchor click. The
 * template includes the optional 4th column `last_paid_through` with
 * one row populated and one row left blank so the operator sees both
 * forms. Order of columns is not significant — the parser
 * transformHeader keys by lowercased name.
 */
export function downloadAccountsCsvTemplate(): void {
  const sample =
    'name,rd_number,monthly_amount,last_paid_through\n' +
    'Ramesh Kumar,123456789,500,2025-03\n' +
    'Sunita Devi,987654321012,1000,\n';
  const blob = new Blob([sample], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'rd_accounts_template.csv';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
