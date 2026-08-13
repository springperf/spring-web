const fs = require('fs');
const file = process.argv[2];
const threadPat = new RegExp(process.argv[3]);
const text = fs.readFileSync(file, 'utf8');
const blocks = text.split(/jdk\.ExecutionSample \{/).slice(1);
const frames = new Map();
let n = 0;
for (const b of blocks) {
  const t = b.match(/sampledThread = "([^"]+)"/);
  if (!t || !threadPat.test(t[1])) continue;
  n++;
  const re = /^    ([\w.$]+)\(/gm;
  let m;
  while ((m = re.exec(b))) {
    const f = m[1];
    if (f.startsWith('io.springperf.') || f.startsWith('org.springframework.')) {
      frames.set(f, (frames.get(f) || 0) + 1);
    }
  }
}
console.log(`threads matching /${process.argv[3]}/: ${n} samples`);
[...frames.entries()].sort((a, b) => b[1] - a[1]).slice(0, 25)
  .forEach(([f, c]) => console.log(`${String(c).padStart(5)}  ${f}`));
