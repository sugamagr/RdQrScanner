// Mirror of app/src/main/java/com/qrscanner/app/util/MonthYear.kt.
// Owns the YYYY-MM parse/format/auto-window logic so the portal and
// phones produce identical defaulter month listings in exports.

export interface MonthYear {
  year: number;
  month: number;
}

const MIN_YEAR = 2000;
const MAX_YEAR = 2099;
const SHORT_MONTHS_EN = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

export function monthYearToToken(my: MonthYear): string {
  return `${String(my.year).padStart(4, '0')}-${String(my.month).padStart(2, '0')}`;
}

export function formatExport(my: MonthYear): string {
  const name = SHORT_MONTHS_EN[my.month - 1] ?? String(my.month).padStart(2, '0');
  return `${name} ${my.year}`;
}

export function fromEpochMillis(millis: number): MonthYear {
  const d = new Date(millis);
  return { year: d.getFullYear(), month: d.getMonth() + 1 };
}

export function fromIso(iso: string): MonthYear {
  return fromEpochMillis(Date.parse(iso));
}

export function minusOneMonth(my: MonthYear): MonthYear {
  return my.month === 1
    ? { year: my.year - 1, month: 12 }
    : { year: my.year, month: my.month - 1 };
}

export function plusOneMonth(my: MonthYear): MonthYear {
  return my.month === 12
    ? { year: my.year + 1, month: 1 }
    : { year: my.year, month: my.month + 1 };
}

export function parseToken(token: string): MonthYear | null {
  const trimmed = token.trim();
  if (trimmed.length !== 7 || trimmed[4] !== '-') return null;
  const year = Number.parseInt(trimmed.slice(0, 4), 10);
  const month = Number.parseInt(trimmed.slice(5, 7), 10);
  if (!Number.isInteger(year) || !Number.isInteger(month)) return null;
  if (year < MIN_YEAR || year > MAX_YEAR) return null;
  if (month < 1 || month > 12) return null;
  return { year, month };
}

export function parseList(raw: string | null, expectedCount: number): MonthYear[] | null {
  if (!raw || raw.trim().length === 0 || expectedCount <= 0) return null;
  const tokens = raw.split(',');
  if (tokens.length !== expectedCount) return null;
  const out: MonthYear[] = [];
  for (const tok of tokens) {
    const parsed = parseToken(tok);
    if (!parsed) return null;
    out.push(parsed);
  }
  return out;
}

export function autoWindow(count: number, endingAt: MonthYear): MonthYear[] {
  const safe = Math.max(1, Math.min(36, count));
  const result: MonthYear[] = [];
  let cursor = endingAt;
  for (let i = 0; i < safe; i++) {
    result.push(cursor);
    cursor = minusOneMonth(cursor);
  }
  return result;
}

export function resolveOrAuto(
  raw: string | null,
  count: number,
  endingAt: MonthYear
): MonthYear[] {
  return parseList(raw, count) ?? autoWindow(count, endingAt);
}
