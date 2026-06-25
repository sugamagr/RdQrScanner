import Papa, { type ParseResult } from 'papaparse';

const RD_NUMBER_REGEX = /^\d{9,15}$/;

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
}

export interface ParsedAccount {
  rdNumber: string;
  name: string;
  monthlyAmount: number;
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
 * Header row REQUIRED, exactly: name, rd_number, monthly_amount
 * (case-insensitive, surrounding whitespace stripped). Reject:
 *   - missing required field (any of name / rd_number / monthly_amount blank)
 *   - rd_number not matching ^\d{9,15}$
 *   - monthly_amount not a positive integer (rejects 0, negative, float, NaN)
 *   - in-file duplicate rd_number (keep first occurrence, drop the rest)
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
          seenRdNumbers.add(rdNumber);
          valid.push({ rdNumber, name: sanitizeFormulaPrefix(name), monthlyAmount: amount });
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

/** Triggers a CSV-template download via Blob + anchor click. */
export function downloadAccountsCsvTemplate(): void {
  const sample =
    'name,rd_number,monthly_amount\n' +
    'Ramesh Kumar,123456789,500\n' +
    'Sunita Devi,987654321012,1000\n';
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
