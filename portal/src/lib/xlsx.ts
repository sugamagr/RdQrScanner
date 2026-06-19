// Structural port of app/src/main/java/com/qrscanner/app/util/XlsxExporter.kt.
// Hand-rolls OOXML inside a ZIP so portal-exported XLSX files have the
// same column order, header bolding, and cell formatting as the phone's
// export. The OOXML payload matches the Kotlin source character-for-
// character; the surrounding ZIP container metadata (timestamps, CRC,
// compression method) differs between fflate and Kotlin's
// ZipOutputStream, so files aren't byte-identical — but every reader
// (Excel, Numbers, LibreOffice, Sheets) renders them as the same
// workbook.

import { zipSync, strToU8 } from 'fflate';
import type { RdNumberRow, ScanLotRow } from '../types/db';
import { formatExport, fromIso, resolveOrAuto } from './monthYear';

export function buildSessionXlsx(params: {
  sessionDisplayNumber: number;
  lots: ScanLotRow[];
  rdNumbersByLotId: Map<string, RdNumberRow[]>;
}): Uint8Array {
  const { sessionDisplayNumber, lots, rdNumbersByLotId } = params;

  const files: Record<string, Uint8Array> = {
    '[Content_Types].xml': strToU8(contentTypesXml()),
    '_rels/.rels': strToU8(rootRelsXml()),
    'xl/workbook.xml': strToU8(workbookXml(sessionDisplayNumber)),
    'xl/_rels/workbook.xml.rels': strToU8(workbookRelsXml()),
    'xl/styles.xml': strToU8(stylesXml()),
    'xl/worksheets/sheet1.xml': strToU8(sheet1Xml(lots, rdNumbersByLotId)),
  };
  return zipSync(files);
}

export function triggerDownload(bytes: Uint8Array, filename: string): void {
  // Copy into a fresh ArrayBuffer because Blob types reject SharedArrayBuffer-
  // backed views; the runtime check is satisfied either way but TS strict
  // rejects the union without an explicit copy.
  const buffer = bytes.slice().buffer as ArrayBuffer;
  const blob = new Blob([buffer], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  // Defer revoke so Safari finishes the download before the URL dies.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

function contentTypesXml(): string {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>`;
}

function rootRelsXml(): string {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>`;
}

function workbookXml(sessionNumber: number): string {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Session ${sessionNumber}" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>`;
}

function workbookRelsXml(): string {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>`;
}

function stylesXml(): string {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><name val="Calibri"/></font>
  </fonts>
  <fills count="2">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
  </fills>
  <borders count="1">
    <border><left/><right/><top/><bottom/><diagonal/></border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="2">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
  </cellXfs>
</styleSheet>`;
}

function sheet1Xml(
  lots: ScanLotRow[],
  rdNumbersByLotId: Map<string, RdNumberRow[]>
): string {
  const parts: string[] = [];
  parts.push('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>');
  parts.push('<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">');
  parts.push('<cols>');
  parts.push('<col min="2" max="2" width="40" customWidth="1"/>');
  parts.push('<col min="5" max="5" width="30" customWidth="1"/>');
  parts.push('<col min="6" max="6" width="50" customWidth="1"/>');
  parts.push('<col min="7" max="7" width="22" customWidth="1"/>');
  parts.push('</cols>');
  parts.push('<sheetData>');

  parts.push('<row r="1">');
  parts.push(strCell('A1', 'LOT #', true));
  parts.push(strCell('B1', 'RD Numbers', true));
  parts.push(strCell('C1', 'Count', true));
  parts.push(strCell('D1', 'Default Count', true));
  parts.push(strCell('E1', 'Defaulters', true));
  parts.push(strCell('F1', 'Default Months', true));
  parts.push(strCell('G1', 'Timestamp', true));
  parts.push('</row>');

  lots.forEach((lot, index) => {
    const rows = rdNumbersByLotId.get(lot.id) ?? [];
    const defaulters = rows.filter((r) => r.months_paid > 1);
    const anchor = fromIso(lot.timestamp);
    const rowNum = index + 2;
    parts.push(`<row r="${rowNum}">`);
    parts.push(numCell(`A${rowNum}`, lot.lot_number));
    parts.push(strCell(`B${rowNum}`, rows.map((r) => r.number).join(', ')));
    parts.push(numCell(`C${rowNum}`, rows.length));
    parts.push(numCell(`D${rowNum}`, defaulters.length));
    parts.push(
      strCell(
        `E${rowNum}`,
        defaulters.map((r) => `${r.number}: ${r.months_paid}m`).join('; ')
      )
    );
    parts.push(
      strCell(
        `F${rowNum}`,
        defaulters
          .map((r) => {
            const months = resolveOrAuto(r.months_list, r.months_paid, anchor);
            return `${r.number}: ` + months.map(formatExport).join(', ');
          })
          .join('; ')
      )
    );
    parts.push(strCell(`G${rowNum}`, formatTimestamp(lot.timestamp)));
    parts.push('</row>');
  });

  parts.push('</sheetData>');
  parts.push('</worksheet>');
  return parts.join('');
}

function strCell(ref: string, value: string, bold = false): string {
  const escaped = value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
  const style = bold ? ' s="1"' : '';
  return `<c r="${ref}" t="inlineStr"${style}><is><t>${escaped}</t></is></c>`;
}

function numCell(ref: string, value: number): string {
  return `<c r="${ref}"><v>${value}</v></c>`;
}

function formatTimestamp(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  );
}
