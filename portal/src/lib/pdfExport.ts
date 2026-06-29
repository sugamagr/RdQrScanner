import type { Style } from '@react-pdf/types';
import type { DashboardRange, DashboardStats } from './dashboardQueries';
import { formatNumber } from './format';

/**
 * Style map for the PDF rendering helpers. We deliberately use
 * `Record<string, Style>` rather than an enumerated `{ page: Style;
 * headerBand: Style; ... }` because the helper functions reference
 * keys that drift over time, and a strict shape would force every
 * field rename to update two locations. The runtime guarantee is that
 * every accessed key is created via StyleSheet.create() above.
 *
 * The type-only import does NOT pull the @react-pdf/renderer runtime
 * into the main bundle — only the dynamic import inside
 * generateDashboardPdf does. This preserves the lazy-chunk boundary
 * that vite.config.ts.manualChunks.pdf depends on.
 */
type PdfStyles = Record<string, Style>;

export type PdfPaper = 'A4' | 'Letter';
export type PdfTheme = 'light' | 'mono';
export type PdfSection =
  | 'kpis'
  | 'money'
  | 'currentVsDefault'
  | 'sessions'
  | 'scans'
  | 'source'
  | 'amountHistogram'
  | 'topDefaulters'
  | 'activity';

export interface GeneratePdfOptions {
  stats: DashboardStats;
  range: DashboardRange;
  title: string;
  subtitle: string;
  sections: PdfSection[];
  paper: PdfPaper;
  theme: PdfTheme;
}

/**
 * Generates and downloads a designer-quality dashboard PDF. The work
 * deliberately lives in this lazy-imported module — together with
 * @react-pdf/renderer (~250KB gz) — so the main bundle keeps its
 * one-shot speed and the heavy code only loads when the operator
 * clicks "Export PDF".
 *
 * Chart rendering uses native @react-pdf primitives (Svg / Path /
 * Rect / Text) rather than rasterizing the live Recharts DOM because:
 *   1. Vector PDF stays crisp at any zoom (raster blurs at print).
 *   2. We don't have to mount the dashboard chart twice (live + a
 *      hidden export-only copy with explicit dimensions).
 *   3. Theme switching (light vs mono) becomes a pure data swap on
 *      stroke/fill values rather than re-painting a canvas.
 *
 * The downside: every chart type needs hand-rolled axis math. That's
 * acceptable here because the dashboard has 6 chart types and the
 * data is bounded (≤ 60 month buckets at the cap).
 */
export async function generateDashboardPdf(opts: GeneratePdfOptions): Promise<void> {
  const reactPdf = await import('@react-pdf/renderer');
  const React = await import('react');
  const { Document, Page, View, Text, StyleSheet, Svg, Path, Rect, Line, G, pdf } = reactPdf;

  const colors = buildColors(opts.theme);
  const pageSize = opts.paper === 'A4'
    ? { width: 595.28, height: 841.89 }
    : { width: 612, height: 792 };

  // Margins in points. Top is taller to host the header band; bottom
  // hosts the footer. Side margins stay generous for thumb-readability.
  const M = { top: 56, right: 40, bottom: 48, left: 40 };

  const contentWidth = pageSize.width - M.left - M.right;

  const styles: PdfStyles = StyleSheet.create({
    page: {
      fontFamily: 'Helvetica',
      fontSize: 9,
      color: colors.inkPrimary,
      backgroundColor: colors.bg,
      paddingTop: M.top,
      paddingBottom: M.bottom,
      paddingLeft: M.left,
      paddingRight: M.right,
    },
    headerBand: {
      position: 'absolute',
      top: 0,
      left: 0,
      right: 0,
      height: 36,
      backgroundColor: colors.headerBg,
    },
    headerInner: {
      position: 'absolute',
      top: 12,
      left: M.left,
      right: M.right,
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
    },
    headerTitle: { color: colors.headerInk, fontSize: 10, fontWeight: 700 },
    headerMeta: { color: colors.headerInk, fontSize: 8, opacity: 0.9 },
    title: { fontSize: 18, fontWeight: 700, marginTop: 8, color: colors.inkPrimary },
    subtitle: { fontSize: 10, marginTop: 4, color: colors.inkSecondary },
    sectionTitle: { fontSize: 12, fontWeight: 700, marginTop: 18, marginBottom: 8, color: colors.inkPrimary },
    kpiGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
    kpiCard: {
      width: (contentWidth - 24) / 4,
      borderRadius: 6,
      borderWidth: 0.5,
      borderColor: colors.border,
      padding: 8,
      backgroundColor: colors.surface,
    },
    kpiLabel: { fontSize: 7, color: colors.inkMuted, textTransform: 'uppercase', letterSpacing: 0.5 },
    kpiValue: { fontSize: 12, fontWeight: 700, marginTop: 4, color: colors.inkPrimary },
    kpiSubtitle: { fontSize: 7, color: colors.inkSecondary, marginTop: 2 },
    chartBlock: {
      marginBottom: 10,
      borderRadius: 6,
      borderWidth: 0.5,
      borderColor: colors.border,
      backgroundColor: colors.surface,
      padding: 10,
    },
    chartTitle: { fontSize: 10, fontWeight: 700, color: colors.inkPrimary },
    chartSubtitle: { fontSize: 8, color: colors.inkSecondary, marginTop: 2, marginBottom: 8 },
    chartCaption: { fontSize: 7, color: colors.inkMuted, marginTop: 4 },
    twoCol: { flexDirection: 'row', gap: 10 },
    tableHeaderRow: {
      flexDirection: 'row',
      backgroundColor: colors.tableHeaderBg,
      paddingVertical: 4,
      paddingHorizontal: 6,
      borderTopLeftRadius: 4,
      borderTopRightRadius: 4,
    },
    tableHeader: { fontSize: 8, fontWeight: 700, color: colors.inkSecondary, textTransform: 'uppercase' },
    tableRow: {
      flexDirection: 'row',
      paddingVertical: 5,
      paddingHorizontal: 6,
      borderBottomWidth: 0.5,
      borderBottomColor: colors.border,
    },
    tableCell: { fontSize: 9, color: colors.inkPrimary },
    footer: {
      position: 'absolute',
      bottom: 16,
      left: M.left,
      right: M.right,
      flexDirection: 'row',
      justifyContent: 'space-between',
    },
    footerText: { fontSize: 7, color: colors.inkMuted },
    legendRow: { flexDirection: 'row', gap: 12, marginTop: 6 },
    legendItem: { flexDirection: 'row', alignItems: 'center', gap: 4 },
    legendSwatch: { width: 8, height: 8, borderRadius: 2 },
    legendLabel: { fontSize: 8, color: colors.inkSecondary },
  });

  const sections = opts.sections;
  const generatedAt = new Date();
  const formattedTimestamp = generatedAt.toISOString().slice(0, 16).replace('T', ' ') + ' UTC';

  const doc = React.createElement(
    Document,
    {
      title: opts.title,
      author: 'RD Book Scanner',
      subject: opts.subtitle,
      creator: 'RD Book Portal',
      producer: 'RD Book Portal',
    },
    React.createElement(
      Page,
      { size: pageSize, style: styles.page },
      React.createElement(View, { fixed: true, style: styles.headerBand }),
      React.createElement(
        View,
        { fixed: true, style: styles.headerInner },
        React.createElement(Text, { style: styles.headerTitle }, 'RD Book Scanner — Report'),
        React.createElement(Text, { style: styles.headerMeta }, formattedTimestamp),
      ),
      React.createElement(Text, { style: styles.title }, opts.title),
      React.createElement(Text, { style: styles.subtitle }, opts.subtitle),

      ...renderSections({ ...opts, sections, styles, colors, contentWidth, React, view: View, text: Text, svg: Svg, path: Path, rect: Rect, line: Line, g: G }),

      React.createElement(
        View,
        {
          fixed: true,
          style: styles.footer,
          // @react-pdf/renderer types this callback as
          // { pageNumber: number; subPageNumber: number } but the
          // runtime also provides totalPages. The cast lets us use
          // the documented totalPages without disabling strict types
          // across the rest of the file.
          render: ((props: { pageNumber: number; totalPages: number }) => (
            React.createElement(
              React.Fragment,
              null,
              React.createElement(Text, { style: styles.footerText }, `Page ${props.pageNumber} of ${props.totalPages}`),
              React.createElement(Text, { style: styles.footerText }, opts.title),
            )
          )) as unknown as (props: { pageNumber: number; subPageNumber: number }) => import('react').ReactNode,
        },
      ),
    ),
  );

  // Render to Blob then trigger anchor[download]. Safari needs a
  // setTimeout before revokeObjectURL or the download is cancelled
  // mid-stream — see librarian audit bg_e2a3f6fa.
  const blob = await pdf(doc).toBlob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  const fname = `rd-book-report-${generatedAt.toISOString().slice(0, 10)}.pdf`;
  a.download = fname;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

interface PdfColors {
  bg: string;
  surface: string;
  border: string;
  inkPrimary: string;
  inkSecondary: string;
  inkMuted: string;
  headerBg: string;
  headerInk: string;
  tableHeaderBg: string;
  primary: string;
  mint: string;
  coral: string;
  warn: string;
  danger: string;
  defaulter: string;
}

function buildColors(theme: PdfTheme): PdfColors {
  if (theme === 'mono') {
    return {
      bg: '#FFFFFF',
      surface: '#FFFFFF',
      border: '#D1D5DB',
      inkPrimary: '#111827',
      inkSecondary: '#374151',
      inkMuted: '#6B7280',
      headerBg: '#1F2937',
      headerInk: '#FFFFFF',
      tableHeaderBg: '#F3F4F6',
      primary: '#374151',
      mint: '#4B5563',
      coral: '#6B7280',
      warn: '#1F2937',
      danger: '#111827',
      defaulter: '#1F2937',
    };
  }
  return {
    bg: '#FFFFFF',
    surface: '#FFFFFF',
    border: '#E5E7EB',
    inkPrimary: '#111827',
    inkSecondary: '#374151',
    inkMuted: '#6B7280',
    headerBg: '#FF9F43',
    headerInk: '#FFFFFF',
    tableHeaderBg: '#F9FAFB',
    primary: '#FF9F43',
    mint: '#0E8278',
    coral: '#FF6B6B',
    warn: '#B45309',
    danger: '#B91C1C',
    defaulter: '#B45309',
  };
}

interface RenderContext extends GeneratePdfOptions {
  styles: PdfStyles;
  colors: PdfColors;
  contentWidth: number;
  // We pass the @react-pdf primitives down rather than re-importing
  // because dynamic import + ESM interop varies by bundler. Threading
  // the React handle is also necessary because we can't statically
  // import React inside a lazy chunk that might run before the main
  // bundle in pathological loading orders.
  React: typeof import('react');
  view: typeof import('@react-pdf/renderer').View;
  text: typeof import('@react-pdf/renderer').Text;
  svg: typeof import('@react-pdf/renderer').Svg;
  path: typeof import('@react-pdf/renderer').Path;
  rect: typeof import('@react-pdf/renderer').Rect;
  line: typeof import('@react-pdf/renderer').Line;
  g: typeof import('@react-pdf/renderer').G;
}

function renderSections(ctx: RenderContext): import('react').ReactElement[] {
  const elems: import('react').ReactElement[] = [];
  for (const sec of ctx.sections) {
    switch (sec) {
      case 'kpis':
        elems.push(renderKpis(ctx));
        break;
      case 'money':
        elems.push(renderMoneyTrend(ctx));
        break;
      case 'currentVsDefault':
        elems.push(renderCurrentVsDefault(ctx));
        break;
      case 'sessions':
        elems.push(renderSessionsTrend(ctx));
        break;
      case 'scans':
        elems.push(renderScansTrend(ctx));
        break;
      case 'source':
        elems.push(renderSourceDonut(ctx));
        break;
      case 'amountHistogram':
        elems.push(renderAmountHistogram(ctx));
        break;
      case 'topDefaulters':
        elems.push(renderTopDefaulters(ctx));
        break;
      case 'activity':
        elems.push(renderActivity(ctx));
        break;
      default: {
        // Exhaustiveness — TS will error if a new PdfSection is added
        // without a case above.
        const exhaustive: never = sec;
        throw new Error(`Unhandled section: ${exhaustive as string}`);
      }
    }
  }
  return elems;
}

function renderKpis(ctx: RenderContext): import('react').ReactElement {
  const { React, view, text, styles, stats, colors } = ctx;
  const items: Array<{ label: string; value: string; sub?: string }> = [
    { label: 'Total accounts', value: formatNumber(stats.totalAccounts), sub: `${formatNumber(stats.activeAccounts)} active` },
    { label: 'Defaulters', value: formatNumber(stats.defaulterCount), sub: 'distinct RD numbers' },
    { label: 'Collected this month', value: `Rs ${formatNumber(stats.totalCollectedThisMonth)}`, sub: 'last_paid_through >= now' },
    { label: 'Book amount', value: `Rs ${formatNumber(stats.totalAccountAmount)}`, sub: 'sum monthly amounts' },
    { label: 'Avg ticket', value: `Rs ${formatNumber(stats.averageMonthlyAmount)}`, sub: 'real average (active)' },
    { label: 'Sessions', value: formatNumber(stats.totalSessions), sub: 'in selected range' },
    { label: 'Scans', value: formatNumber(stats.totalRdScans), sub: 'RD numbers in range' },
    { label: 'Active devices', value: `${stats.activeDevices} / ${stats.totalDevices}`, sub: 'last 5 minutes' },
    { label: 'Current accounts', value: formatNumber(stats.currentVsDefault.currentCount), sub: `Rs ${formatNumber(stats.currentVsDefault.currentAmount)}` },
    { label: 'Default accounts', value: formatNumber(stats.currentVsDefault.defaultCount), sub: `Rs ${formatNumber(stats.currentVsDefault.defaultAmount)}` },
    { label: 'Manual / CSV', value: `${stats.sourceBreakdown.find((s) => s.source === 'MANUAL')?.count ?? 0} / ${stats.sourceBreakdown.find((s) => s.source === 'CSV')?.count ?? 0}`, sub: 'account source' },
    { label: 'Money collected (range)', value: `Rs ${formatNumber(stats.monthlyMoneyCollected.reduce((s, m) => s + m.amount, 0))}`, sub: 'weighted by months_paid' },
  ];
  return React.createElement(
    React.Fragment,
    { key: 'kpis' },
    React.createElement(text, { style: styles.sectionTitle }, 'KPI summary'),
    React.createElement(
      view,
      { style: styles.kpiGrid },
      items.map((it, i) => React.createElement(
        view,
        { key: i, style: styles.kpiCard, wrap: false },
        React.createElement(text, { style: styles.kpiLabel }, it.label),
        React.createElement(text, { style: styles.kpiValue }, it.value),
        it.sub != null ? React.createElement(text, { style: styles.kpiSubtitle }, it.sub) : null,
      )),
    ),
    // Spacer rectangle keeps the renderer happy when colors.border is mono.
    React.createElement(view, { style: { height: 0, borderBottomWidth: 0, borderColor: colors.border } }),
  );
}

function renderMoneyTrend(ctx: RenderContext): import('react').ReactElement {
  const { React, view, text, styles, stats, colors } = ctx;
  const total = stats.monthlyMoneyCollected.reduce((s, m) => s + m.amount, 0);
  const defaulterTotal = stats.monthlyMoneyCollected.reduce((s, m) => s + m.defaulterAmount, 0);
  return React.createElement(
    React.Fragment,
    { key: 'money' },
    React.createElement(text, { style: styles.sectionTitle }, 'Money collected trend'),
    React.createElement(
      view,
      { style: styles.chartBlock, wrap: false },
      React.createElement(text, { style: styles.chartTitle }, 'Monthly money collected'),
      React.createElement(text, { style: styles.chartSubtitle }, `Rs ${formatNumber(total)} total · Rs ${formatNumber(defaulterTotal)} from defaulters`),
      renderMoneyChart(ctx),
      React.createElement(
        view,
        { style: styles.legendRow },
        React.createElement(
          view,
          { style: styles.legendItem },
          React.createElement(view, { style: { ...(styles.legendSwatch), backgroundColor: colors.mint } }),
          React.createElement(text, { style: styles.legendLabel }, 'Total'),
        ),
        React.createElement(
          view,
          { style: styles.legendItem },
          React.createElement(view, { style: { ...(styles.legendSwatch), backgroundColor: colors.defaulter } }),
          React.createElement(text, { style: styles.legendLabel }, 'From defaulters'),
        ),
      ),
      renderMonthlyValueTable(ctx, stats.monthlyMoneyCollected.map((m) => ({ month: m.month, primary: m.amount, secondary: m.defaulterAmount })), 'Month', 'Total (Rs)', 'Defaulter (Rs)'),
    ),
  );
}

function renderCurrentVsDefault(ctx: RenderContext): import('react').ReactElement {
  const { React, view, text, styles, stats, colors, contentWidth } = ctx;
  const b = stats.currentVsDefault;
  const totalAmt = b.currentAmount + b.defaultAmount;
  const totalCnt = b.currentCount + b.defaultCount;
  const W = contentWidth - 20;
  const H = 120;
  const padL = 50;
  const padR = 12;
  const padT = 12;
  const padB = 24;
  const innerW = W - padL - padR;
  const innerH = H - padT - padB;
  const maxAmt = Math.max(b.currentAmount, b.defaultAmount, 1);
  const barW = innerW / 2 - 16;
  return React.createElement(
    React.Fragment,
    { key: 'cvd' },
    React.createElement(text, { style: styles.sectionTitle }, 'Current vs default'),
    React.createElement(
      view,
      { style: styles.chartBlock, wrap: false },
      React.createElement(text, { style: styles.chartTitle }, 'Active accounts paid-up status'),
      React.createElement(text, { style: styles.chartSubtitle }, `${formatNumber(totalCnt)} active accounts · Rs ${formatNumber(totalAmt)} monthly`),
      ctx.React.createElement(ctx.svg as unknown as React.ComponentType<{ width: number; height: number; children?: React.ReactNode }>, { width: W, height: H }, [
        // Y axis line
        ctx.React.createElement(ctx.line, { key: 'y', x1: padL, y1: padT, x2: padL, y2: H - padB, stroke: colors.border, strokeWidth: 0.5 }),
        // X axis line
        ctx.React.createElement(ctx.line, { key: 'x', x1: padL, y1: H - padB, x2: W - padR, y2: H - padB, stroke: colors.border, strokeWidth: 0.5 }),
        // Current bar
        ctx.React.createElement(ctx.rect, {
          key: 'b1',
          x: padL + 16,
          y: padT + innerH - (b.currentAmount / maxAmt) * innerH,
          width: barW,
          height: (b.currentAmount / maxAmt) * innerH,
          fill: colors.mint,
        }),
        // Default bar
        ctx.React.createElement(ctx.rect, {
          key: 'b2',
          x: padL + 16 + barW + 32,
          y: padT + innerH - (b.defaultAmount / maxAmt) * innerH,
          width: barW,
          height: (b.defaultAmount / maxAmt) * innerH,
          fill: colors.defaulter,
        }),
      ]),
      renderInfoTable(ctx, [
        { left: 'Current', value: `${formatNumber(b.currentCount)} accounts`, right: `Rs ${formatNumber(b.currentAmount)}` },
        { left: 'Default', value: `${formatNumber(b.defaultCount)} accounts`, right: `Rs ${formatNumber(b.defaultAmount)}` },
      ]),
    ),
  );
}

function renderSessionsTrend(ctx: RenderContext): import('react').ReactElement {
  const { React, view, text, styles, stats, colors } = ctx;
  const total = stats.monthlySessionCounts.reduce((s, d) => s + d.count, 0);
  return React.createElement(
    React.Fragment,
    { key: 'sess' },
    React.createElement(text, { style: styles.sectionTitle }, 'Sessions trend'),
    React.createElement(
      view,
      { style: styles.chartBlock, wrap: false },
      React.createElement(text, { style: styles.chartTitle }, 'Sessions per month'),
      React.createElement(text, { style: styles.chartSubtitle }, `${formatNumber(total)} sessions in range`),
      renderLineChart(ctx, stats.monthlySessionCounts.map((d) => ({ x: d.month, y: d.count })), colors.primary, 'sessions'),
      renderMonthlyValueTable(ctx, stats.monthlySessionCounts.map((m) => ({ month: m.month, primary: m.count })), 'Month', 'Sessions'),
    ),
  );
}

function renderScansTrend(ctx: RenderContext): import('react').ReactElement {
  const { React, view, text, styles, stats, colors } = ctx;
  const total = stats.monthlyScansCollected.reduce((s, d) => s + d.collected, 0);
  return React.createElement(
    React.Fragment,
    { key: 'scans' },
    React.createElement(text, { style: styles.sectionTitle }, 'Scans & defaulters trend'),
    React.createElement(
      view,
      { style: styles.chartBlock, wrap: false },
      React.createElement(text, { style: styles.chartTitle }, 'Scans per month with defaulter overlay'),
      React.createElement(text, { style: styles.chartSubtitle }, `${formatNumber(total)} scans in range`),
      renderDualLineChart(
        ctx,
        stats.monthlyScansCollected.map((d) => ({ x: d.month, y1: d.collected, y2: d.defaulters })),
        colors.mint,
        colors.defaulter,
      ),
      React.createElement(
        view,
        { style: styles.legendRow },
        React.createElement(
          view,
          { style: styles.legendItem },
          React.createElement(view, { style: { ...(styles.legendSwatch), backgroundColor: colors.mint } }),
          React.createElement(text, { style: styles.legendLabel }, 'Total scans'),
        ),
        React.createElement(
          view,
          { style: styles.legendItem },
          React.createElement(view, { style: { ...(styles.legendSwatch), backgroundColor: colors.defaulter } }),
          React.createElement(text, { style: styles.legendLabel }, 'Defaulter scans'),
        ),
      ),
      renderMonthlyValueTable(
        ctx,
        stats.monthlyScansCollected.map((m) => ({ month: m.month, primary: m.collected, secondary: m.defaulters })),
        'Month',
        'Scans',
        'Defaulters',
      ),
    ),
  );
}

function renderSourceDonut(ctx: RenderContext): import('react').ReactElement {
  const { React, view, text, styles, stats, colors, contentWidth } = ctx;
  const total = stats.sourceBreakdown.reduce((s, d) => s + d.count, 0);
  const W = contentWidth - 20;
  const H = 140;
  const cx = padCenter(W);
  const cy = H / 2;
  const r = 50;
  const ir = 30;
  let acc = 0;
  const slices = stats.sourceBreakdown.map((d) => {
    const fraction = total === 0 ? 0 : d.count / total;
    const start = acc * 2 * Math.PI - Math.PI / 2;
    acc += fraction;
    const end = acc * 2 * Math.PI - Math.PI / 2;
    return { d, start, end, fraction };
  });
  return React.createElement(
    React.Fragment,
    { key: 'src' },
    React.createElement(text, { style: styles.sectionTitle }, 'Account source mix'),
    React.createElement(
      view,
      { style: styles.chartBlock, wrap: false },
      React.createElement(text, { style: styles.chartTitle }, 'CSV vs Manual'),
      React.createElement(text, { style: styles.chartSubtitle }, `${formatNumber(total)} accounts`),
      ctx.React.createElement(ctx.svg as unknown as React.ComponentType<{ width: number; height: number; children?: React.ReactNode }>, { width: W, height: H }, slices.map((s, i) => {
        if (s.fraction === 0) return null;
        const color = s.d.source === 'MANUAL' ? colors.primary : colors.mint;
        const path = donutPath(cx, cy, r, ir, s.start, s.end);
        return ctx.React.createElement(ctx.path, {
          key: i,
          d: path,
          fill: color,
        });
      })),
      renderInfoTable(ctx, stats.sourceBreakdown.map((d) => ({
        left: d.source,
        value: `${formatNumber(d.count)} accounts`,
        right: total === 0 ? '0%' : `${Math.round((d.count / total) * 100)}%`,
      }))),
    ),
  );
}

function padCenter(W: number): number { return W / 2; }

function donutPath(cx: number, cy: number, r: number, ir: number, a0: number, a1: number): string {
  const x0 = cx + r * Math.cos(a0);
  const y0 = cy + r * Math.sin(a0);
  const x1 = cx + r * Math.cos(a1);
  const y1 = cy + r * Math.sin(a1);
  const ix0 = cx + ir * Math.cos(a0);
  const iy0 = cy + ir * Math.sin(a0);
  const ix1 = cx + ir * Math.cos(a1);
  const iy1 = cy + ir * Math.sin(a1);
  // Large-arc flag = 1 when the swept angle exceeds π. Without this,
  // slices > 180° render as the *minor* arc and look like a thin
  // crescent instead of the bulk of the donut.
  const large = a1 - a0 > Math.PI ? 1 : 0;
  return `M ${x0} ${y0} A ${r} ${r} 0 ${large} 1 ${x1} ${y1} L ${ix1} ${iy1} A ${ir} ${ir} 0 ${large} 0 ${ix0} ${iy0} Z`;
}

function renderAmountHistogram(ctx: RenderContext): import('react').ReactElement {
  const { React, view, text, styles, stats, colors, contentWidth } = ctx;
  const W = contentWidth - 20;
  const H = 140;
  const padL = 50;
  const padR = 12;
  const padT = 12;
  const padB = 26;
  const innerW = W - padL - padR;
  const innerH = H - padT - padB;
  const max = Math.max(...stats.amountHistogram.map((b) => b.count), 1);
  const barW = innerW / stats.amountHistogram.length;
  return React.createElement(
    React.Fragment,
    { key: 'hist' },
    React.createElement(text, { style: styles.sectionTitle }, 'Amount distribution'),
    React.createElement(
      view,
      { style: styles.chartBlock, wrap: false },
      React.createElement(text, { style: styles.chartTitle }, 'Account count by monthly amount bucket'),
      React.createElement(text, { style: styles.chartSubtitle }, `${formatNumber(stats.amountHistogram.reduce((s, b) => s + b.count, 0))} accounts`),
      ctx.React.createElement(ctx.svg as unknown as React.ComponentType<{ width: number; height: number; children?: React.ReactNode }>, { width: W, height: H }, [
        ctx.React.createElement(ctx.line, { key: 'y', x1: padL, y1: padT, x2: padL, y2: H - padB, stroke: colors.border, strokeWidth: 0.5 }),
        ctx.React.createElement(ctx.line, { key: 'x', x1: padL, y1: H - padB, x2: W - padR, y2: H - padB, stroke: colors.border, strokeWidth: 0.5 }),
        ...stats.amountHistogram.flatMap((b, i) => {
          const h = (b.count / max) * innerH;
          const x = padL + i * barW + 6;
          const y = padT + innerH - h;
          return [
            ctx.React.createElement(ctx.rect, { key: `b${i}`, x, y, width: barW - 12, height: h, fill: colors.primary }),
            ctx.React.createElement(ctx.text, { key: `t${i}`, x: padL + i * barW + barW / 2, y: H - padB + 12, style: { fontSize: 7, fill: colors.inkMuted }, textAnchor: 'middle' }, b.bucket),
            ctx.React.createElement(ctx.text, { key: `v${i}`, x: padL + i * barW + barW / 2, y: y - 2, style: { fontSize: 7, fill: colors.inkSecondary }, textAnchor: 'middle' }, String(b.count)),
          ];
        }),
      ]),
      renderInfoTable(ctx, stats.amountHistogram.map((b) => ({ left: b.bucket, value: `${formatNumber(b.count)} accounts`, right: '' }))),
    ),
  );
}

function renderTopDefaulters(ctx: RenderContext): import('react').ReactElement {
  const { React, view, text, styles, stats, colors } = ctx;
  return React.createElement(
    React.Fragment,
    { key: 'top' },
    React.createElement(text, { style: styles.sectionTitle }, 'Top defaulters'),
    React.createElement(
      view,
      { style: styles.chartBlock, wrap: false },
      React.createElement(text, { style: styles.chartTitle }, `Top ${stats.topDefaulters.length} by months overdue`),
      React.createElement(text, { style: styles.chartSubtitle }, 'RD numbers masked per privacy policy'),
      React.createElement(
        view,
        { style: styles.tableHeaderRow },
        React.createElement(text, { style: { ...(styles.tableHeader), flex: 0.5 } }, '#'),
        React.createElement(text, { style: { ...(styles.tableHeader), flex: 2 } }, 'Name'),
        React.createElement(text, { style: { ...(styles.tableHeader), flex: 2 } }, 'RD number (masked)'),
        React.createElement(text, { style: { ...(styles.tableHeader), flex: 1, textAlign: 'right' } }, 'Months'),
      ),
      stats.topDefaulters.length === 0
        ? React.createElement(
            view,
            { style: styles.tableRow },
            React.createElement(text, { style: { ...(styles.tableCell), color: colors.inkMuted } }, 'No overdue accounts.'),
          )
        : stats.topDefaulters.map((r, i) => React.createElement(
            view,
            { key: r.rdNumber, style: styles.tableRow },
            React.createElement(text, { style: { ...(styles.tableCell), flex: 0.5 } }, String(i + 1)),
            React.createElement(text, { style: { ...(styles.tableCell), flex: 2 } }, r.name),
            React.createElement(text, { style: { ...(styles.tableCell), flex: 2 } }, r.maskedRdNumber),
            React.createElement(text, { style: { ...(styles.tableCell), flex: 1, textAlign: 'right', color: colors.defaulter, fontWeight: 700 } }, `${r.monthsOverdue}`),
          )),
    ),
  );
}

function renderActivity(ctx: RenderContext): import('react').ReactElement {
  const { React, view, text, styles, stats, colors } = ctx;
  return React.createElement(
    React.Fragment,
    { key: 'act' },
    React.createElement(text, { style: styles.sectionTitle }, 'Recent activity'),
    React.createElement(
      view,
      { style: styles.chartBlock, wrap: false },
      React.createElement(text, { style: styles.chartTitle }, 'Latest events'),
      React.createElement(text, { style: styles.chartSubtitle }, `${stats.recentActivity.length} of 8 most recent`),
      React.createElement(
        view,
        { style: styles.tableHeaderRow },
        React.createElement(text, { style: { ...(styles.tableHeader), flex: 1 } }, 'Kind'),
        React.createElement(text, { style: { ...(styles.tableHeader), flex: 3 } }, 'Detail'),
        React.createElement(text, { style: { ...(styles.tableHeader), flex: 1.5, textAlign: 'right' } }, 'When'),
      ),
      stats.recentActivity.length === 0
        ? React.createElement(
            view,
            { style: styles.tableRow },
            React.createElement(text, { style: { ...(styles.tableCell), color: colors.inkMuted } }, 'No activity yet.'),
          )
        : stats.recentActivity.map((r, i) => React.createElement(
            view,
            { key: i, style: styles.tableRow },
            React.createElement(text, { style: { ...(styles.tableCell), flex: 1 } }, r.kind),
            React.createElement(text, { style: { ...(styles.tableCell), flex: 3 } }, `${r.primary}${r.secondary ? ' — ' + r.secondary : ''}`),
            React.createElement(text, { style: { ...(styles.tableCell), flex: 1.5, textAlign: 'right' } }, r.occurredAt.slice(0, 10)),
          )),
    ),
  );
}

function renderMoneyChart(ctx: RenderContext): import('react').ReactElement {
  const { contentWidth, stats, colors } = ctx;
  const W = contentWidth - 20;
  const H = 130;
  return renderTwoSeriesAreaChart(
    ctx,
    stats.monthlyMoneyCollected.map((m) => ({ x: m.month, y1: m.amount, y2: m.defaulterAmount })),
    colors.mint,
    colors.defaulter,
    W,
    H,
    (v) => `Rs ${formatNumber(v)}`,
  );
}

interface XY { x: string; y: number; }
interface XY2 { x: string; y1: number; y2: number; }

function renderLineChart(ctx: RenderContext, data: XY[], color: string, _label: string): import('react').ReactElement {
  const { React, svg, line, rect, contentWidth, colors } = ctx;
  const W = contentWidth - 20;
  const H = 130;
  const padL = 50, padR = 12, padT = 12, padB = 24;
  const innerW = W - padL - padR;
  const innerH = H - padT - padB;
  const maxY = Math.max(...data.map((d) => d.y), 1);
  const stepX = data.length > 1 ? innerW / (data.length - 1) : 0;
  const points = data.map((d, i) => ({
    x: padL + i * stepX,
    y: padT + innerH - (d.y / maxY) * innerH,
    label: d.x,
    value: d.y,
  }));
  const pathStr = points.reduce((acc, p, i) => acc + (i === 0 ? `M ${p.x} ${p.y}` : ` L ${p.x} ${p.y}`), '');
  const fillStr = pathStr + ` L ${points[points.length - 1]?.x ?? padL} ${padT + innerH} L ${padL} ${padT + innerH} Z`;
  return React.createElement(svg as unknown as React.ComponentType<{ width: number; height: number; children?: React.ReactNode }>, { width: W, height: H }, [
    React.createElement(line, { key: 'y', x1: padL, y1: padT, x2: padL, y2: H - padB, stroke: colors.border, strokeWidth: 0.5 }),
    React.createElement(line, { key: 'x', x1: padL, y1: H - padB, x2: W - padR, y2: H - padB, stroke: colors.border, strokeWidth: 0.5 }),
    React.createElement(ctx.path, { key: 'fill', d: fillStr, fill: color, fillOpacity: 0.15, stroke: 'none' }),
    React.createElement(ctx.path, { key: 'stroke', d: pathStr, fill: 'none', stroke: color, strokeWidth: 1.5 }),
    ...points.flatMap((p, i) => [
      React.createElement(rect, { key: `d${i}`, x: p.x - 1.2, y: p.y - 1.2, width: 2.4, height: 2.4, fill: color }),
    ]),
    ...points.map((p, i) => React.createElement(ctx.text, {
      key: `xl${i}`,
      x: p.x,
      y: H - padB + 12,
      style: { fontSize: 6, fill: colors.inkMuted },
      textAnchor: 'middle',
    }, p.label.slice(2))),
  ]);
}

function renderDualLineChart(ctx: RenderContext, data: XY2[], color1: string, color2: string): import('react').ReactElement {
  const { React, svg, line, contentWidth, colors } = ctx;
  const W = contentWidth - 20;
  const H = 130;
  const padL = 50, padR = 12, padT = 12, padB = 24;
  const innerW = W - padL - padR;
  const innerH = H - padT - padB;
  const maxY = Math.max(...data.map((d) => Math.max(d.y1, d.y2)), 1);
  const stepX = data.length > 1 ? innerW / (data.length - 1) : 0;
  const points1 = data.map((d, i) => ({ x: padL + i * stepX, y: padT + innerH - (d.y1 / maxY) * innerH, label: d.x }));
  const points2 = data.map((d, i) => ({ x: padL + i * stepX, y: padT + innerH - (d.y2 / maxY) * innerH }));
  const path1 = points1.reduce((acc, p, i) => acc + (i === 0 ? `M ${p.x} ${p.y}` : ` L ${p.x} ${p.y}`), '');
  const path2 = points2.reduce((acc, p, i) => acc + (i === 0 ? `M ${p.x} ${p.y}` : ` L ${p.x} ${p.y}`), '');
  return React.createElement(svg as unknown as React.ComponentType<{ width: number; height: number; children?: React.ReactNode }>, { width: W, height: H }, [
    React.createElement(line, { key: 'y', x1: padL, y1: padT, x2: padL, y2: H - padB, stroke: colors.border, strokeWidth: 0.5 }),
    React.createElement(line, { key: 'x', x1: padL, y1: H - padB, x2: W - padR, y2: H - padB, stroke: colors.border, strokeWidth: 0.5 }),
    React.createElement(ctx.path, { key: 'p1', d: path1, fill: 'none', stroke: color1, strokeWidth: 1.5 }),
    React.createElement(ctx.path, { key: 'p2', d: path2, fill: 'none', stroke: color2, strokeWidth: 1.5 }),
    ...points1.map((p, i) => React.createElement(ctx.text, {
      key: `xl${i}`,
      x: p.x,
      y: H - padB + 12,
      style: { fontSize: 6, fill: colors.inkMuted },
      textAnchor: 'middle',
    }, p.label.slice(2))),
  ]);
}

function renderTwoSeriesAreaChart(
  ctx: RenderContext,
  data: XY2[],
  color1: string,
  color2: string,
  W: number,
  H: number,
  _format: (n: number) => string,
): import('react').ReactElement {
  const { React, svg, line, contentWidth, colors } = ctx;
  void contentWidth;
  const padL = 50, padR = 12, padT = 12, padB = 24;
  const innerW = W - padL - padR;
  const innerH = H - padT - padB;
  const maxY = Math.max(...data.map((d) => Math.max(d.y1, d.y2)), 1);
  const stepX = data.length > 1 ? innerW / (data.length - 1) : 0;
  const p1 = data.map((d, i) => ({ x: padL + i * stepX, y: padT + innerH - (d.y1 / maxY) * innerH, label: d.x }));
  const p2 = data.map((d, i) => ({ x: padL + i * stepX, y: padT + innerH - (d.y2 / maxY) * innerH }));
  const path1 = p1.reduce((acc, p, i) => acc + (i === 0 ? `M ${p.x} ${p.y}` : ` L ${p.x} ${p.y}`), '');
  const path2 = p2.reduce((acc, p, i) => acc + (i === 0 ? `M ${p.x} ${p.y}` : ` L ${p.x} ${p.y}`), '');
  const last1 = p1[p1.length - 1]?.x ?? padL;
  const last2 = p2[p2.length - 1]?.x ?? padL;
  const fill1 = path1 + ` L ${last1} ${padT + innerH} L ${padL} ${padT + innerH} Z`;
  const fill2 = path2 + ` L ${last2} ${padT + innerH} L ${padL} ${padT + innerH} Z`;
  return React.createElement(svg as unknown as React.ComponentType<{ width: number; height: number; children?: React.ReactNode }>, { width: W, height: H }, [
    React.createElement(line, { key: 'y', x1: padL, y1: padT, x2: padL, y2: H - padB, stroke: colors.border, strokeWidth: 0.5 }),
    React.createElement(line, { key: 'x', x1: padL, y1: H - padB, x2: W - padR, y2: H - padB, stroke: colors.border, strokeWidth: 0.5 }),
    React.createElement(ctx.path, { key: 'f1', d: fill1, fill: color1, fillOpacity: 0.15, stroke: 'none' }),
    React.createElement(ctx.path, { key: 'f2', d: fill2, fill: color2, fillOpacity: 0.15, stroke: 'none' }),
    React.createElement(ctx.path, { key: 'p1', d: path1, fill: 'none', stroke: color1, strokeWidth: 1.5 }),
    React.createElement(ctx.path, { key: 'p2', d: path2, fill: 'none', stroke: color2, strokeWidth: 1.5 }),
    ...p1.map((p, i) => React.createElement(ctx.text, {
      key: `xl${i}`,
      x: p.x,
      y: H - padB + 12,
      style: { fontSize: 6, fill: colors.inkMuted },
      textAnchor: 'middle',
    }, p.label.slice(2))),
  ]);
}

interface MonthlyValueRow {
  month: string;
  primary: number;
  secondary?: number;
}

function renderMonthlyValueTable(
  ctx: RenderContext,
  rows: MonthlyValueRow[],
  monthHeader: string,
  primaryHeader: string,
  secondaryHeader?: string,
): import('react').ReactElement {
  const { React, view, text, styles, colors } = ctx;
  return React.createElement(
    view,
    { style: { marginTop: 6 } },
    React.createElement(
      view,
      { style: styles.tableHeaderRow },
      React.createElement(text, { style: { ...(styles.tableHeader), flex: 1 } }, monthHeader),
      React.createElement(text, { style: { ...(styles.tableHeader), flex: 1, textAlign: 'right' } }, primaryHeader),
      secondaryHeader != null
        ? React.createElement(text, { style: { ...(styles.tableHeader), flex: 1, textAlign: 'right' } }, secondaryHeader)
        : null,
    ),
    ...rows.map((r, i) => React.createElement(
      view,
      { key: i, style: styles.tableRow },
      React.createElement(text, { style: { ...(styles.tableCell), flex: 1 } }, r.month),
      React.createElement(text, { style: { ...(styles.tableCell), flex: 1, textAlign: 'right' } }, formatNumber(r.primary)),
      secondaryHeader != null && r.secondary != null
        ? React.createElement(text, { style: { ...(styles.tableCell), flex: 1, textAlign: 'right', color: colors.defaulter } }, formatNumber(r.secondary))
        : null,
    )),
  );
}

interface InfoRow {
  left: string;
  value: string;
  right: string;
}

function renderInfoTable(ctx: RenderContext, rows: InfoRow[]): import('react').ReactElement {
  const { React, view, text, styles } = ctx;
  return React.createElement(
    view,
    { style: { marginTop: 6 } },
    ...rows.map((r, i) => React.createElement(
      view,
      { key: i, style: styles.tableRow },
      React.createElement(text, { style: { ...(styles.tableCell), flex: 1, fontWeight: 700 } }, r.left),
      React.createElement(text, { style: { ...(styles.tableCell), flex: 1, textAlign: 'center' } }, r.value),
      React.createElement(text, { style: { ...(styles.tableCell), flex: 1, textAlign: 'right' } }, r.right),
    )),
  );
}
