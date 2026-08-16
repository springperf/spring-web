#!/usr/bin/env node
/**
 * Cross-checks the 8 generated benchmark SVGs against the source dataset
 * in docs/benchmark.md (benchmark-reports/20260814-221509, 4/8/16 threads).
 * Usage: node verify-benchmark-svgs.mjs   (exit 0 = all checks pass)
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const DIR = dirname(fileURLToPath(import.meta.url));
let failures = 0;

const check = (svgPath, values) => {
  const text = readFileSync(join(DIR, svgPath), 'utf8');
  for (const v of values) {
    if (!text.includes(String(v))) {
      console.log(`MISSING ${v} in ${svgPath}`);
      failures++;
    }
  }
  // malformed markers
  for (const bad of ['NaN', 'undefined', 'null', 'Infinity']) {
    if (text.includes(bad)) {
      console.log(`BAD TOKEN ${bad} in ${svgPath}`);
      failures++;
    }
  }
  if (!text.trim().startsWith('<svg') || !text.trim().endsWith('</svg>')) {
    console.log(`BAD SVG WRAPPER in ${svgPath}`);
    failures++;
  }
};

const through = [
  '37,508', '19,900', '19,488', '18,114', '38,538', '17,051', '17,604', '15,467',
  '42,017', '26,915', '27,680', '24,859', '35,949', '20,077', '19,880', '17,625',
  '40,501', '19,111', '17,543', '24,297', '18,250', '10,032', '12,587', '11,772',
  '13,323', '1,055', '2,623',
];
const trend = [
  '12.63x', '11.90x', '7.72x', '1.88x', '1.93x', '1.61x', '2.26x', '2.08x', '1.84x',
  '1.56x', '1.54x', '1.22x', '1.79x', '1.86x', '1.65x', '2.12x', '2.10x', '1.73x',
  '1.82x', '1.73x', '1.35x',
];
const latency = [
  '0.11', '0.20', '0.22', '0.10', '0.24', '0.15', '0.16', '0.23', '0.21', '0.38',
  '0.29', '0.31', '0.28', '2.27', '1.26',
];
const memory = [
  '10.1', '23.4', '23.3', '32.6', '10.7', '37.3', '35.6', '50.0',
  '313.9KB', '225.1KB', '192.3KB', '310.4KB', '233.9KB', '192.5KB',
];

check('benchmark-throughput-4t.svg', through);
check('benchmark-throughput-4t-en.svg', through);
check('benchmark-multiple-trend.svg', trend);
check('benchmark-multiple-trend-en.svg', trend);
check('benchmark-latency-p50.svg', latency);
check('benchmark-latency-p50-en.svg', latency);
check('benchmark-memory-allocation.svg', memory);
check('benchmark-memory-allocation-en.svg', memory);

if (failures === 0) {
  console.log('ALL SVG DATA CHECKS PASSED');
} else {
  console.log(`${failures} FAILURES`);
  process.exit(1);
}
