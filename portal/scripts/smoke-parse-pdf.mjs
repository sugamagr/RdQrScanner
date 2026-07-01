// Headless smoke test: runs the same parser code the browser runs
// against the actual DOP report the user supplied, so we can verify
// output shape + row count + last_paid_through derivation BEFORE
// committing. Uses pdfjs-dist's legacy build to avoid the DOMMatrix
// dependency that only exists in browsers.

// We can't just import '../src/lib/pdfAccountsParser.ts' — that pulls
// the browser pdfjs entry which crashes in Node (no DOMMatrix). We
// replicate the pipeline here using the legacy build. If this test
// passes we know the extraction logic (columns, coalesce, validate)
// is correct; the browser will just use a different pdfjs entry point
// with identical text-extraction output.

import { readFileSync } from 'node:fs';
import { getDocument, GlobalWorkerOptions } from 'pdfjs-dist/legacy/build/pdf.mjs';
import { fileURLToPath } from 'node:url';

// The legacy build ships a Node-compatible worker at pdf.worker.mjs.
// Use file:// so pdfjs treats it as a same-origin asset.
GlobalWorkerOptions.workerSrc = fileURLToPath(
  new URL('../node_modules/pdfjs-dist/legacy/build/pdf.worker.mjs', import.meta.url),
);

const MONTH_NAMES = {
  jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6,
  jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12,
};

function parseDueDate(raw) {
  const trimmed = raw.trim();
  if (trimmed.length === 0) return null;
  const m = /^(\d{1,2})-([A-Za-z]{3})-(\d{4})$/.exec(trimmed);
  if (!m) return null;
  const monthNum = MONTH_NAMES[m[2].toLowerCase()];
  if (monthNum == null) return null;
  const year = Number(m[3]);
  return { year, month: monthNum };
}
function previousMonthToken(y, m) {
  let year = y, month = m - 1;
  if (month === 0) { month = 12; year -= 1; }
  return `${year}-${String(month).padStart(2, '0')}`;
}
function parseDenomination(raw) {
  const c = raw.trim();
  if (!c) return null;
  if (/\bDr\.?\b/i.test(c)) return null;
  const d = c.replace(/\s*Cr\.?\s*$/i, '').replace(/,/g, '').trim();
  const dot = d.indexOf('.');
  const ip = dot >= 0 ? d.slice(0, dot) : d;
  if (!/^\d+$/.test(ip)) return null;
  const n = Number(ip);
  return Number.isFinite(n) && n > 0 ? n : null;
}
const RD_REGEX = /^\d{9,15}$/;

function groupByY(items) {
  const b = new Map();
  for (const it of items) {
    const k = Math.round(it.y / 2) * 2;
    let arr = b.get(k);
    if (!arr) { arr = []; b.set(k, arr); }
    arr.push(it);
  }
  for (const arr of b.values()) arr.sort((a, b) => a.x - b.x);
  return b;
}
function deriveColsFromPage(rowsTopDown, outHeaderBottomY) {
  const map = {};
  let lowestHeaderY = null;
  for (const row of rowsTopDown) {
    for (const it of row) {
      const s = it.str.trim().toLowerCase();
      let matched = false;
      if (map.select == null && s === 'select') { map.select = it.x; matched = true; }
      else if (map.accountNo == null && s === 'account no') { map.accountNo = it.x; matched = true; }
      else if (map.accountName == null && s === 'account name') { map.accountName = it.x; matched = true; }
      else if (map.denomination == null && s === 'denomination') { map.denomination = it.x; matched = true; }
      else if (map.monthPaidUpto == null && s === 'month paid upto') { map.monthPaidUpto = it.x; matched = true; }
      else if (map.nextDueDate == null && s.startsWith('next rd installment')) { map.nextDueDate = it.x; matched = true; }
      if (matched && (lowestHeaderY == null || it.y < lowestHeaderY)) lowestHeaderY = it.y;
    }
    const done = ['select','accountNo','accountName','denomination','monthPaidUpto','nextDueDate'].every(k => map[k] != null);
    if (done) break;
  }
  if (outHeaderBottomY) outHeaderBottomY[0] = lowestHeaderY;
  const req = ['select','accountNo','accountName','denomination','monthPaidUpto','nextDueDate'];
  for (const k of req) if (map[k] == null) return null;
  return req.map(k => ({ key: k, xStart: map[k] }));
}
function bucketRow(y, row, cols) {
  const cells = { select:'', accountNo:'', accountName:'', denomination:'', monthPaidUpto:'', nextDueDate:'' };
  for (const item of row) {
    let col = cols[0].key;
    for (let i = cols.length - 1; i >= 0; i--) {
      if (item.x + 0.5 >= cols[i].xStart) { col = cols[i].key; break; }
    }
    cells[col] = cells[col].length === 0 ? item.str : `${cells[col]} ${item.str}`;
  }
  for (const k of Object.keys(cells)) cells[k] = cells[k].replace(/\s+/g, ' ').trim();
  return { y, cells };
}
function isDecorative(t) {
  const s = t.trim();
  if (!s) return true;
  if (/^\d+\s*\/\s*\d+$/.test(s)) return true;
  if (/^https?:\/\//i.test(s)) return true;
  if (/^\d{2}\/\d{2}\/\d{4}/.test(s)) return true;
  if (/Department of Post/i.test(s)) return true;
  if (/^Due Date$/i.test(s)) return true;
  return false;
}
function coalesce(rowsInput, page, headerBottomY = null) {
  const mains = [];
  const fragments = [];
  for (const { y, cells: c } of rowsInput) {
    const empty = Object.values(c).every(v => v.length === 0);
    if (empty) continue;
    const isNew = /^\d+$/.test(c.select);
    if (isNew) {
      mains.push({ page, index: Number(c.select), y,
        accountNo: c.accountNo, accountName: c.accountName, denomination: c.denomination,
        monthPaidUpto: c.monthPaidUpto, nextDueDate: c.nextDueDate });
    } else {
      const raw = [c.accountNo, c.accountName, c.denomination, c.monthPaidUpto, c.nextDueDate]
        .filter(s => s.length > 0).join(' ').replace(/\s+/g, ' ').trim();
      if (!raw) continue;
      if (isDecorative(raw)) continue;
      fragments.push({ y, text: raw });
    }
  }
  if (mains.length === 0) return [];
  const PAD = 20;
  let topY = mains[0].y, botY = mains[0].y;
  for (const m of mains) { if (m.y > topY) topY = m.y; if (m.y < botY) botY = m.y; }
  const paddedTop = topY + PAD;
  const envTop = headerBottomY != null ? Math.min(paddedTop, headerBottomY - 1) : paddedTop;
  const envBot = botY - PAD;
  for (const frag of fragments) {
    if (frag.y > envTop || frag.y < envBot) continue;
    let bestIdx = 0, bestDist = Math.abs(frag.y - mains[0].y);
    for (let i = 1; i < mains.length; i++) {
      const d = Math.abs(frag.y - mains[i].y);
      if (d < bestDist || (d === bestDist && mains[i].y > mains[bestIdx].y)) {
        bestIdx = i; bestDist = d;
      }
    }
    const target = mains[bestIdx];
    const pos = frag.y > target.y ? 'lead' : 'trail';
    if (target.accountName.length === 0) target.accountName = frag.text;
    else target.accountName = pos === 'lead'
      ? `${frag.text} ${target.accountName}`.replace(/\s+/g, ' ').trim()
      : `${target.accountName} ${frag.text}`.replace(/\s+/g, ' ').trim();
  }
  return mains;
}

async function main() {
  const path = '/Users/apple/Downloads/RD Accounts.pdf';
  const data = new Uint8Array(readFileSync(path));
  const doc = await getDocument({ data, isEvalSupported: false, useSystemFonts: false }).promise;
  console.log(`Loaded ${doc.numPages} pages`);
  const allItems = [];
  for (let p = 1; p <= doc.numPages; p++) {
    const pg = await doc.getPage(p);
    const tc = await pg.getTextContent();
    for (const raw of tc.items) {
      if (typeof raw.str !== 'string' || !Array.isArray(raw.transform)) continue;
      allItems.push({ str: raw.str, x: raw.transform[4], y: raw.transform[5], width: raw.width ?? 0, height: raw.height ?? 0, page: p });
    }
  }
  console.log(`Extracted ${allItems.length} text items`);

  const page1Items = allItems.filter(it => it.page === 1);
  const page1ByY = groupByY(page1Items);
  const page1RowsTopDown = [...page1ByY.entries()].sort((a,b) => b[0]-a[0]).map(e => e[1]);
  const headerBottomYRef = [null];
  const cols = deriveColsFromPage(page1RowsTopDown, headerBottomYRef);
  console.log('Column anchors:', JSON.stringify(cols));
  console.log('Header bottom y:', headerBottomYRef[0]);

  const records = [];
  for (let p = 1; p <= doc.numPages; p++) {
    const items = allItems.filter(it => it.page === p);
    const grouped = groupByY(items);
    const rowsTopDown = [...grouped.entries()].sort((a,b) => b[0]-a[0]).map(([y, arr]) => bucketRow(y, arr, cols));
    const headerYForPage = p === 1 ? headerBottomYRef[0] : null;
    records.push(...coalesce(rowsTopDown, p, headerYForPage));
  }
  console.log(`\nExtracted ${records.length} raw records`);

  // Full parsed output
  const valid = []; const errors = []; const seen = new Set();
  for (const rec of records) {
    const rl = rec.index;
    const name = rec.accountName.trim();
    if (!name) { errors.push({ row: rl, msg: 'Missing Account Name' }); continue; }
    if (name.length > 60) { errors.push({ row: rl, msg: `Name too long: ${name.length}` }); continue; }
    const rd = rec.accountNo.trim();
    if (!RD_REGEX.test(rd)) { errors.push({ row: rl, msg: `Invalid rd_number "${rd}"` }); continue; }
    const amt = parseDenomination(rec.denomination);
    if (amt == null) { errors.push({ row: rl, msg: `Invalid denomination "${rec.denomination}"` }); continue; }
    if (seen.has(rd)) { errors.push({ row: rl, msg: `Duplicate ${rd}` }); continue; }
    let lpt = null;
    const due = parseDueDate(rec.nextDueDate);
    if (due) lpt = previousMonthToken(due.year, due.month);
    seen.add(rd);
    valid.push({ i: rl, name, rd, amt, lpt, dueRaw: rec.nextDueDate });
  }

  console.log(`\n=== VALIDATION SUMMARY ===`);
  console.log(`Valid rows:   ${valid.length}`);
  console.log(`Errors:       ${errors.length}`);
  if (errors.length > 0) {
    console.log('Error detail:');
    for (const e of errors.slice(0, 20)) console.log(`  row ${e.row}: ${e.msg}`);
  }

  console.log(`\n=== First 3 valid rows ===`);
  for (const v of valid.slice(0, 3)) console.log(`  ${v.i}: ${v.name} | ${v.rd} | ${v.amt} | lpt=${v.lpt ?? '(null)'}  [due:${v.dueRaw}]`);
  console.log(`\n=== Last 5 valid rows (should include blank-due-date at end) ===`);
  for (const v of valid.slice(-5)) console.log(`  ${v.i}: ${v.name} | ${v.rd} | ${v.amt} | lpt=${v.lpt ?? '(null)'}  [due:"${v.dueRaw}"]`);

  console.log(`\n=== Rows with null last_paid_through (blank due) ===`);
  const nullLpt = valid.filter(v => v.lpt === null);
  for (const v of nullLpt) console.log(`  ${v.i}: ${v.name} (${v.rd})`);

  console.log(`\n=== Amount total (should be Rs 4,82,150 per user CSV) ===`);
  const total = valid.reduce((s,v) => s + v.amt, 0);
  console.log(`  Rs ${total} across ${valid.length} accounts`);

  console.log(`\n=== Compare against user CSV ===`);
  const csvLines = readFileSync('/Users/apple/Documents/RdQrScanner/rd_accounts_import.csv', 'utf8').split('\n').slice(1).filter(l => l.trim().length > 0);
  console.log(`  CSV row count: ${csvLines.length}`);
  console.log(`  Parser valid:  ${valid.length}`);
  console.log(`  Diff:          ${csvLines.length - valid.length}`);

  // Row-by-row match on rd_number + amount + last_paid_through
  const csvByRd = new Map();
  for (const line of csvLines) {
    const parts = line.split(',');
    csvByRd.set(parts[1], { name: parts[0], amt: Number(parts[2]), lpt: parts[3] || null });
  }
  let mismatches = 0;
  for (const v of valid) {
    const c = csvByRd.get(v.rd);
    if (!c) { console.log(`  MISS in CSV: ${v.rd} (${v.name})`); mismatches++; continue; }
    if (c.amt !== v.amt) { console.log(`  AMT diff ${v.rd}: csv=${c.amt} pdf=${v.amt}`); mismatches++; }
    if (c.lpt !== v.lpt) { console.log(`  LPT diff ${v.rd}: csv=${c.lpt} pdf=${v.lpt}`); mismatches++; }
    if (c.name !== v.name) { console.log(`  NAME diff ${v.rd}: csv="${c.name}" pdf="${v.name}"`); mismatches++; }
  }
  for (const [rd, c] of csvByRd) {
    if (!valid.find(v => v.rd === rd)) { console.log(`  MISS in PDF: ${rd} (${c.name})`); mismatches++; }
  }
  console.log(`  Total mismatches: ${mismatches}`);
}
main().catch(e => { console.error(e); process.exit(1); });
