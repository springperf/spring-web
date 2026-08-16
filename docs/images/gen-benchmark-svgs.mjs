#!/usr/bin/env node
/**
 * Regenerates the 8 benchmark SVG charts (4 charts x CN/EN) from the JDK17 dataset.
 * Data source: docs/benchmark.md
 *   - single source: benchmark-reports/20260814-221509 (thrpt + sample, 4/8/16 threads)
 *
 * Usage:  node gen-benchmark-svgs.mjs
 * Output: benchmark-{throughput-4t,multiple-trend,latency-p50,memory-allocation}[|-en].svg
 */
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const DIR = dirname(fileURLToPath(import.meta.url));
const fmt = (n) => n.toLocaleString('en-US');

const PROFILES = [
  ['perf', 'bar-perf'],
  ['tomcat', 'bar-tomcat'],
  ['undertow', 'bar-undertow'],
  ['webflux', 'bar-webflux'],
];

// CSS shared by every chart (colors / gridlines only — text styles are per-chart).
const sharedCss = () => [
  ' .gridline { stroke: #e5e7eb; stroke-width: 1; }\n',
  ' .baseline { stroke: #d1d5db; stroke-width: 1; }\n',
  ' .bar-perf { fill: #22c55e; }\n',
  ' .bar-tomcat { fill: #3b82f6; }\n',
  ' .bar-undertow { fill: #f59e0b; }\n',
  ' .bar-webflux { fill: #8b5cf6; }\n',
];

const barLegend = (blocks) =>
  blocks
    .map(
      ([cls, rx, ry, tx, ty, label]) =>
        ` <rect x="${rx}" y="${ry}" width="12" height="12" rx="2" class="${cls}"/>\n` +
        ` <text x="${tx}" y="${ty}" class="legendText">${label}</text>\n`
    )
    .join('');

/* ======================================================================
 * Chart 1: 4-thread throughput (grouped bar)
 * ==================================================================== */
const THROUGHPUT = {
  // order: [perf, tomcat, undertow, webflux], null = FAIL
  json: [37508, 19900, 19488, 18114],
  get: [38538, 17051, 17604, 15467],
  bytes: [42017, 26915, 27680, 24859],
  valid: [35949, 20077, 19880, 17625],
  async: [40501, 19111, 17543, 24297],
  bytesLarge: [18250, 10032, 12587, 11772],
  sse: [13323, 1055, null, 2623],
};

function throughputSvg(lang) {
  const title = lang === 'cn' ? '4线程吞吐量对比 (ops/sec)' : '4-Thread Throughput (ops/sec)';
  const W = 1100, H = 520, baseY = 430, maxVal = 50000;
  const px = (baseY - 50) / maxVal; // 0.0076 px/unit, top gridline at y=50
  const groups = [
    ['json', 100], ['get', 239], ['bytes', 378], ['valid', 517],
    ['async', 656], ['bytesLarge', 795], ['sse', 934],
  ];
  const ticks = [0, 10000, 20000, 30000, 40000, 50000];

  let out = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">\n`;
  out += `<style>\n` +
    ` .bg { fill: #f8fafc; }\n` +
    ` .title { font: bold 16px -apple-system,BlinkMacSystemFont,sans-serif; fill: #1f2937; }\n` +
    ` .tickLabel { font: 11px -apple-system,BlinkMacSystemFont,sans-serif; fill: #9ca3af; }\n` +
    ` .apiLabel { font: 12px -apple-system,BlinkMacSystemFont,sans-serif; fill: #374151; text-anchor: middle; }\n` +
    ` .dataLabel { font: 10px -apple-system,BlinkMacSystemFont,sans-serif; fill: #1f2937; text-anchor: middle; }\n` +
    ` .legendText { font: 12px -apple-system,BlinkMacSystemFont,sans-serif; fill: #374151; }\n` +
    sharedCss().join('') + `</style>\n`;
  out += `<rect class="bg" x="0" y="0" width="${W}" height="${H}" rx="12"/>\n`;
  out += `<text x="30" y="32" class="title">${title}</text>\n\n`;

  for (const t of ticks) {
    const y = baseY - t * px;
    out += `<line x1="80" y1="${y}" x2="1060" y2="${y}" class="${t === 0 ? 'baseline' : 'gridline'}"/>\n`;
  }
  for (const t of ticks) {
    const y = baseY - t * px;
    out += `<text x="68" y="${Math.round(y) + 4}" class="tickLabel" text-anchor="end">${fmt(t)}</text>\n`;
  }

  out += `
 <!-- Legend -->
 <rect x="720" y="16" width="14" height="14" rx="2" class="bar-perf"/>
 <text x="740" y="28" class="legendText">perf</text>
 <rect x="790" y="16" width="14" height="14" rx="2" class="bar-tomcat"/>
 <text x="810" y="28" class="legendText">Tomcat</text>
 <rect x="870" y="16" width="14" height="14" rx="2" class="bar-undertow"/>
 <text x="890" y="28" class="legendText">Undertow</text>
 <rect x="960" y="16" width="14" height="14" rx="2" class="bar-webflux"/>
 <text x="980" y="28" class="legendText">WebFlux</text>
`;

  for (const [gname, base] of groups) {
    const vals = THROUGHPUT[gname];
    out += `\n <!-- ${gname} -->\n`;
    PROFILES.forEach(([, cls], i) => {
      const v = vals[i];
      if (v === null) return;
      const x = base + i * 29;
      const h = Math.max(2, Math.round(v * px));
      const y = baseY - h;
      out += ` <rect x="${x}" y="${y}" width="22" height="${h}" class="${cls}"/>\n`;
      out += ` <text x="${x + 11}" y="${y - 6}" class="dataLabel">${fmt(v)}</text>\n`;
    });
    out += ` <text x="${base + 44}" y="455" class="apiLabel">${gname}</text>\n`;
  }

  return out + '</svg>\n';
}

/* ======================================================================
 * Chart 2: perf advantage multiple vs concurrency (line chart)
 * ==================================================================== */
const TREND = {
  sse: [12.63, 11.90, 7.72],
  json: [1.88, 1.93, 1.61],
  get: [2.26, 2.08, 1.84],
  bytes: [1.56, 1.54, 1.22],
  valid: [1.79, 1.86, 1.65],
  async: [2.12, 2.10, 1.73],
  bytesLarge: [1.82, 1.73, 1.35],
};

function trendSvg(lang) {
  const title = lang === 'cn'
    ? 'perf 优势倍数随线程变化 (vs Spring MVC Tomcat)'
    : 'perf Advantage vs Concurrency (vs Spring MVC Tomcat)';
  const refLabel = lang === 'cn' ? 'MVC 基线 (1.0x)' : 'MVC baseline (1.0x)';
  const xlabels = lang === 'cn' ? ['4 线程', '8 线程', '16 线程'] : ['4 threads', '8 threads', '16 threads'];
  const xsub = lang === 'cn' ? '并发线程数' : 'Concurrent Threads';
  const tblHdr = lang === 'cn' ? '接口' : 'API';

  const W = 900, H = 560, baseY = 430, maxV = 14;
  const px = (baseY - 70) / maxV; // 25.7 px per 1x
  const xs = [110, 370, 630];
  const ticks = [0, 2, 4, 6, 8, 10, 12, 14];
  const y = (v) => Math.round(baseY - v * px);

  let out = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">\n`;
  out += `<style>\n` +
    ` .bg { fill: #f8fafc; }\n` +
    ` .title { font: bold 15px -apple-system,BlinkMacSystemFont,sans-serif; fill: #1f2937; }\n` +
    ` .tickLabel { font: 11px -apple-system,BlinkMacSystemFont,sans-serif; fill: #9ca3af; }\n` +
    ` .xLabel { font: 12px -apple-system,BlinkMacSystemFont,sans-serif; fill: #374151; text-anchor: middle; }\n` +
    ` .dataLabel { font: 10px -apple-system,BlinkMacSystemFont,sans-serif; fill: #1f2937; }\n` +
    ` .legendText { font: 11px -apple-system,BlinkMacSystemFont,sans-serif; fill: #374151; }\n` +
    ` .gridline { stroke: #e5e7eb; stroke-width: 1; }\n` +
    ` .baseline { stroke: #d1d5db; stroke-width: 1; }\n` +
    ` .refLine { stroke: #d1d5db; stroke-width: 1.5; stroke-dasharray: 6,3; }\n` +
    ` .dot { stroke: #fff; stroke-width: 1.5; }\n` +
    ` .line-sse { stroke: #ef4444; stroke-width: 2.5; fill: none; }\n` +
    ` .line-normal { stroke: #94a3b8; stroke-width: 1.5; fill: none; }\n` +
    ` .label-sse { fill: #ef4444; font-weight: bold; font-size: 11px; }\n` +
    `</style>\n`;
  out += `<rect class="bg" x="0" y="0" width="${W}" height="${H}" rx="12"/>\n`;
  out += `<text x="30" y="32" class="title">${title}</text>\n\n`;

  for (const t of ticks) {
    const yy = baseY - t * px;
    out += `<line x1="70" y1="${yy}" x2="650" y2="${yy}" class="${t === 0 ? 'baseline' : 'gridline'}"/>\n`;
  }
  for (const t of ticks) {
    const yy = baseY - t * px;
    out += `<text x="58" y="${yy + 4}" class="tickLabel" text-anchor="end">${t === 0 ? '0' : t + 'x'}</text>\n`;
  }
  out += `<line x1="70" y1="${y(1)}" x2="650" y2="${y(1)}" class="refLine"/>\n`;

  xs.forEach((x, i) => {
    out += `<text x="${x}" y="480" class="xLabel">${xlabels[i]}</text>\n`;
  });
  out += `<text x="${xs[1]}" y="505" class="tickLabel" text-anchor="middle">${xsub}</text>\n`;

  const sse = TREND.sse.map((v, i) => `${xs[i]},${y(v)}`).join(' ');
  out += `\n <polyline class="line-sse" points="${sse}"/>\n`;
  sse.split(' ').forEach((p, i) => {
    const [cx, cy] = p.split(',').map(Number);
    out += ` <circle cx="${cx}" cy="${cy}" r="5" class="dot" fill="#ef4444"/>\n`;
    out += ` <text x="${cx}" y="${cy - 6}" class="dataLabel label-sse" text-anchor="middle">${TREND.sse[i].toFixed(2)}x</text>\n`;
  });

  for (const key of ['json', 'get', 'bytes', 'valid', 'async', 'bytesLarge']) {
    const pts = TREND[key].map((v, i) => `${xs[i]},${y(v)}`).join(' ');
    out += `\n <polyline class="line-normal" points="${pts}"/>\n`;
    pts.split(' ').forEach((p) => {
      const [cx, cy] = p.split(',').map(Number);
      out += ` <circle cx="${cx}" cy="${cy}" r="3" class="dot" fill="#94a3b8"/>\n`;
    });
  }

  out += `
 <!-- Legend -->
 <rect x="30" y="52" width="12" height="12" rx="2" fill="#ef4444"/>
 <text x="46" y="62" class="legendText label-sse">SSE</text>
 <rect x="75" y="52" width="12" height="12" rx="2" fill="#94a3b8"/>
 <text x="91" y="62" class="legendText">json / get / bytes / valid / async / bytesLarge</text>
 <line x1="30" y1="84" x2="42" y2="84" stroke="#d1d5db" stroke-width="1.5" stroke-dasharray="6,3"/>
 <text x="46" y="88" class="legendText">${refLabel}</text>

 <!-- Data summary table -->
 <rect x="660" y="88" width="210" height="155" rx="6" fill="#f1f5f9"/>
 <text x="672" y="108" class="legendText" font-weight="bold">${tblHdr}</text>
 <text x="725" y="108" class="legendText" font-weight="bold" text-anchor="middle">4t</text>
 <text x="775" y="108" class="legendText" font-weight="bold" text-anchor="middle">8t</text>
 <text x="825" y="108" class="legendText" font-weight="bold" text-anchor="middle">16t</text>
 <line x1="670" y1="115" x2="860" y2="115" stroke="#e2e8f0" stroke-width="1"/>
`;
  const rows = ['sse', 'json', 'get', 'bytes', 'valid', 'async', 'bytesLarge'];
  rows.forEach((key, r) => {
    const yy = 130 + r * 17;
    const isSse = key === 'sse';
    const cls = isSse ? ' fill="#ef4444" font-weight="bold"' : '';
    out += ` <text x="672" y="${yy}" class="legendText"${cls}>${isSse ? 'SSE' : key}</text>\n`;
    TREND[key].forEach((v, i) => {
      out += ` <text x="${725 + i * 50}" y="${yy}" class="legendText" text-anchor="middle"${cls}>${v.toFixed(2)}x</text>\n`;
    });
  });

  return out + '</svg>\n';
}

/* ======================================================================
 * Chart 3: 4-thread p50 latency (two-panel bar)
 * ==================================================================== */
const LATENCY = {
  json: [0.11, 0.20, 0.20, 0.22],
  get: [0.10, 0.22, 0.24, 0.24],
  bytes: [0.10, 0.15, 0.15, 0.16],
  valid: [0.11, 0.20, 0.20, 0.22],
  async: [0.10, 0.22, 0.23, 0.16],
  bytesLarge: [0.21, 0.38, 0.29, 0.31],
  sse: [0.28, 2.27, null, 1.26],
};

function latencySvg(lang) {
  const title = lang === 'cn' ? '4线程 p50 延迟对比 (ms) — 越低越好' : '4-Thread p50 Latency (ms) — Lower is better';
  const regTitle = lang === 'cn' ? '常规接口' : 'Regular APIs';
  const W = 1100, H = 420, baseY = 335;
  const pxReg = 275; // px per ms (left panel, 0-1.0ms)
  const pxSse = 70;  // px per ms (right panel, 0-4ms)
  const groups = [
    ['json', 112], ['get', 240], ['bytes', 368], ['valid', 496], ['async', 624], ['bytesLarge', 752],
  ];
  const fmt2 = (v) => v.toFixed(2);

  let out = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">\n`;
  out += `<style>\n` +
    ` .bg { fill: #f8fafc; }\n` +
    ` .title { font: bold 14px -apple-system,BlinkMacSystemFont,sans-serif; fill: #1f2937; }\n` +
    ` .sectionTitle { font: bold 13px -apple-system,BlinkMacSystemFont,sans-serif; fill: #1f2937; text-anchor: middle; }\n` +
    ` .tickLabel { font: 11px -apple-system,BlinkMacSystemFont,sans-serif; fill: #9ca3af; }\n` +
    ` .groupLabel { font: 11px -apple-system,BlinkMacSystemFont,sans-serif; fill: #374151; text-anchor: middle; }\n` +
    ` .dataLabel { font: 9px -apple-system,BlinkMacSystemFont,sans-serif; fill: #1f2937; text-anchor: middle; }\n` +
    ` .legendText { font: 11px -apple-system,BlinkMacSystemFont,sans-serif; fill: #374151; }\n` +
    sharedCss().join('') + `</style>\n`;
  out += `<rect class="bg" x="0" y="0" width="${W}" height="${H}" rx="12"/>\n`;
  out += `<text x="30" y="28" class="title">${title}</text>\n`;

  out += `
 ${barLegend([
   ['bar-perf', 560, 14, 576, 24, 'perf'],
   ['bar-tomcat', 615, 14, 631, 24, 'Tomcat'],
   ['bar-undertow', 685, 14, 701, 24, 'Undertow'],
   ['bar-webflux', 760, 14, 776, 24, 'WebFlux'],
 ])}
 <!-- ========== LEFT PANEL: regular APIs ========== -->
 <text x="385" y="55" class="sectionTitle">${regTitle}</text>

 <line x1="60" y1="335" x2="60" y2="55" stroke="#d1d5db" stroke-width="1"/>
`;
  for (const t of [0, 0.2, 0.4, 0.6, 0.8, 1.0]) {
    const yy = baseY - t * pxReg;
    out += ` <line x1="60" y1="${yy}" x2="730" y2="${yy}" class="${t === 0 ? 'baseline' : 'gridline'}"/>\n`;
    out += ` <text x="48" y="${yy + 4}" class="tickLabel" text-anchor="end">${t === 1.0 ? '1.0ms' : t}</text>\n`;
  }

  for (const [gname, c] of groups) {
    const vals = LATENCY[gname];
    out += `\n <!-- ${gname} -->\n`;
    PROFILES.forEach(([, cls], i) => {
      const v = vals[i];
      if (v === null) return;
      const x = c + [-38, -13, 12, 37][i];
      const h = Math.max(2, Math.round(v * pxReg));
      const yy = baseY - h;
      out += ` <rect x="${x}" y="${yy}" width="18" height="${h}" class="${cls}"/>\n`;
      out += ` <text x="${x + 9}" y="${yy - 6}" class="dataLabel">${fmt2(v)}</text>\n`;
    });
    out += ` <text x="${c}" y="365" class="groupLabel">${gname}</text>\n`;
  }

  out += `
 <!-- ========== RIGHT PANEL: SSE ========== -->
 <text x="920" y="55" class="sectionTitle">SSE</text>

 <line x1="800" y1="335" x2="800" y2="55" stroke="#d1d5db" stroke-width="1"/>
`;
  for (const t of [0, 1, 2, 3, 4]) {
    const yy = baseY - t * pxSse;
    out += ` <line x1="800" y1="${yy}" x2="1060" y2="${yy}" class="${t === 0 ? 'baseline' : 'gridline'}"/>\n`;
    out += ` <text x="788" y="${yy + 4}" class="tickLabel" text-anchor="end">${t === 0 ? '0' : t + 'ms'}</text>\n`;
  }

  out += `\n <!-- sse -->\n`;
  PROFILES.forEach(([, cls], i) => {
    const v = LATENCY.sse[i];
    if (v === null) return;
    const x = 892 + i * 25;
    const h = Math.max(2, Math.round(v * pxSse));
    const yy = baseY - h;
    out += ` <rect x="${x}" y="${yy}" width="22" height="${h}" class="${cls}"/>\n`;
    out += ` <text x="${x + 11}" y="${yy - 6}" class="dataLabel">${fmt2(v)}</text>\n`;
  });
  out += ` <text x="930" y="365" class="groupLabel">sse</text>\n`;
  out += `\n <line x1="750" y1="50" x2="750" y2="370" stroke="#e2e8f0" stroke-width="1" stroke-dasharray="4,4"/>\n`;

  return out + '</svg>\n';
}

/* ======================================================================
 * Chart 4: per-request memory allocation (two-panel bar)
 * ==================================================================== */
const MEMORY = {
  json: [10.1, 23.4, 23.3, 32.6],
  get: [10.7, 37.3, 35.6, 50.0],
  sse4t: [313.9, 225.1, null, 192.3],
  sse16t: [310.4, 233.9, null, 192.5],
};

function memorySvg(lang) {
  const title = lang === 'cn' ? '每请求内存分配对比 (KB)' : 'Per-Request Memory Allocation (4 threads)';
  const smallTitle = lang === 'cn' ? '小请求 (json / get)' : 'Small Payload (json / get)';
  const sseTitle = lang === 'cn' ? 'SSE 每请求分配' : 'SSE Per-Request Allocation';
  const W = 960, H = 400, baseY = 335;
  const pxReg = 4.6667; // px per KB (left, 0-60KB)
  const pxSse = 0.7;    // px per KB (right, 0-400KB)
  const fmt1 = (v) => v.toFixed(1);

  let out = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">\n`;
  out += `<style>\n` +
    ` .bg { fill: #f8fafc; }\n` +
    ` .title { font: bold 14px -apple-system,BlinkMacSystemFont,sans-serif; fill: #1f2937; }\n` +
    ` .sectionTitle { font: bold 13px -apple-system,BlinkMacSystemFont,sans-serif; fill: #1f2937; text-anchor: middle; }\n` +
    ` .tickLabel { font: 11px -apple-system,BlinkMacSystemFont,sans-serif; fill: #9ca3af; }\n` +
    ` .groupLabel { font: 12px -apple-system,BlinkMacSystemFont,sans-serif; fill: #374151; text-anchor: middle; }\n` +
    ` .dataLabel { font: 10px -apple-system,BlinkMacSystemFont,sans-serif; fill: #1f2937; text-anchor: middle; }\n` +
    ` .legendText { font: 11px -apple-system,BlinkMacSystemFont,sans-serif; fill: #374151; }\n` +
    sharedCss().join('') + `</style>\n`;
  out += `<rect class="bg" x="0" y="0" width="${W}" height="${H}" rx="12"/>\n`;
  out += `<text x="30" y="28" class="title">${title}</text>\n`;

  out += `
 ${barLegend([
   ['bar-perf', 580, 14, 596, 24, 'perf'],
   ['bar-tomcat', 635, 14, 651, 24, 'Tomcat'],
   ['bar-undertow', 705, 14, 721, 24, 'Undertow'],
   ['bar-webflux', 780, 14, 796, 24, 'WebFlux'],
 ])}
 <!-- ========== LEFT PANEL: json / get ========== -->
 <text x="235" y="55" class="sectionTitle">${smallTitle}</text>

 <line x1="60" y1="335" x2="60" y2="55" stroke="#d1d5db" stroke-width="1"/>
`;
  for (const t of [0, 12, 24, 36, 48, 60]) {
    const yy = baseY - t * pxReg;
    out += ` <line x1="60" y1="${yy}" x2="420" y2="${yy}" class="${t === 0 ? 'baseline' : 'gridline'}"/>\n`;
    out += ` <text x="48" y="${yy + 4}" class="tickLabel" text-anchor="end">${t === 0 ? '0' : t + 'KB'}</text>\n`;
  }

  for (const [gname, c] of [['json', 140], ['get', 310]]) {
    const vals = MEMORY[gname];
    out += `\n <!-- ${gname} -->\n`;
    PROFILES.forEach(([, cls], i) => {
      const v = vals[i];
      if (v === null) return;
      const x = c + [-38, -12, 14, 40][i];
      const h = Math.max(2, Math.round(v * pxReg));
      const yy = baseY - h;
      out += ` <rect x="${x}" y="${yy}" width="20" height="${h}" class="${cls}"/>\n`;
      out += ` <text x="${x + 10}" y="${yy - 6}" class="dataLabel">${fmt1(v)}</text>\n`;
    });
    out += ` <text x="${c + 12}" y="365" class="groupLabel">${gname}</text>\n`;
  }

  out += `
 <!-- ========== RIGHT PANEL: SSE ========== -->
 <text x="700" y="55" class="sectionTitle">${sseTitle}</text>

 <line x1="500" y1="335" x2="500" y2="55" stroke="#d1d5db" stroke-width="1"/>
`;
  for (const t of [0, 100, 200, 300, 400]) {
    const yy = baseY - t * pxSse;
    out += ` <line x1="500" y1="${yy}" x2="920" y2="${yy}" class="${t === 0 ? 'baseline' : 'gridline'}"/>\n`;
    out += ` <text x="488" y="${yy + 4}" class="tickLabel" text-anchor="end">${t === 0 ? '0' : fmt(t) + 'KB'}</text>\n`;
  }

  for (const [gname, c] of [['sse4t', 600], ['sse16t', 760]]) {
    const vals = MEMORY[gname];
    const label = gname === 'sse4t' ? 'SSE 4t' : 'SSE 16t';
    out += `\n <!-- ${gname} -->\n`;
    PROFILES.forEach(([, cls], i) => {
      const v = vals[i];
      if (v === null) return;
      const x = c + [-38, -12, 14, 40][i];
      const h = Math.max(2, Math.round(v * pxSse));
      const yy = baseY - h;
      out += ` <rect x="${x}" y="${yy}" width="20" height="${h}" class="${cls}"/>\n`;
      out += ` <text x="${x + 10}" y="${yy - 6}" class="dataLabel">${fmt1(v)}KB</text>\n`;
    });
    out += ` <text x="${c - 4}" y="365" class="groupLabel">${label}</text>\n`;
  }
  out += `\n <line x1="450" y1="50" x2="450" y2="370" stroke="#e2e8f0" stroke-width="1" stroke-dasharray="4,4"/>\n`;

  return out + '</svg>\n';
}

/* ====================================================================== */
const files = [
  ['benchmark-throughput-4t.svg', throughputSvg('cn')],
  ['benchmark-throughput-4t-en.svg', throughputSvg('en')],
  ['benchmark-multiple-trend.svg', trendSvg('cn')],
  ['benchmark-multiple-trend-en.svg', trendSvg('en')],
  ['benchmark-latency-p50.svg', latencySvg('cn')],
  ['benchmark-latency-p50-en.svg', latencySvg('en')],
  ['benchmark-memory-allocation.svg', memorySvg('cn')],
  ['benchmark-memory-allocation-en.svg', memorySvg('en')],
];

for (const [name, content] of files) {
  writeFileSync(join(DIR, name), content, 'utf8');
  console.log(`wrote ${name} (${content.length} bytes)`);
}
