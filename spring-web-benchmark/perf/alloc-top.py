import re, sys, collections
UNIT={'bytes':1,'kB':1024,'MB':1048576,'GB':1073741824}
RE_O=re.compile(r'jdk\.ObjectAllocationSample \{')
RE_C=re.compile(r'objectClass = ([^(]+?)\s*\(classLoader')
RE_W=re.compile(r'weight = ([\d.]+)\s*(\w+)')
RE_T=re.compile(r'eventThread = "([^"]*)"')

def parse(path):
    """返回 {类名: 权重}，只统计服务端 EventLoop 线程。"""
    out = collections.Counter()
    inb, cls, wt = False, None, None
    for line in open(path, encoding='utf-8', errors='replace'):
        if RE_O.search(line):
            inb, cls, wt = True, None, None
            continue
        if not inb:
            continue
        m = RE_C.search(line)
        if m:
            cls = m.group(1).strip(); continue
        m = RE_W.search(line)
        if m and wt is None:
            wt = float(m.group(1)) * UNIT[m.group(2)]; continue
        m = RE_T.search(line)
        if m and cls and wt is not None:
            if 'EventLoopGroup' in m.group(1):
                out[cls] += wt
            inb = False
    return out

# 排除：基准自身（ResponseEntity/控制器）与客户端（okhttp/okio）——
# 它们在 EventLoop 上出现的部分（如 ResponseEntity$DefaultBuilder）不是框架成本
EXCL = ('org.springframework.http.ResponseEntity', 'okhttp3', 'okio',
        'io.springperf.benchmark')

sets = []
for path, tag in ((sys.argv[1], 'BASE'), (sys.argv[2], 'CUR'), (sys.argv[3], 'OPT')):
    d = parse(path)
    filt = collections.Counter({k: v for k, v in d.items() if not k.startswith(EXCL)})
    s = sum(filt.values())
    sets.append((tag, filt, s))
    print(f"=== {tag}  排除基准/客户端后总权重 {s/1048576:.1f} MB")
    for k, v in filt.most_common(12):
        print(f"    {k:<58}{v*100/s:>6.2f}%")
    print()

# 三方：OPT 相对 BASE 仍增长的类
_, base, bs = sets[0]
_, opt, os_ = sets[2]
print("=" * 70)
print(f"{'类（OPT 仍高于 BASE）':<58}{'BASE%':>8}{'OPT%':>8}")
print("-" * 70)
for k in sorted(set(base) | set(opt),
                key=lambda x: -(opt.get(x, 0) / os_ - base.get(x, 0) / bs))[:12]:
    bp, op = base.get(k, 0) / bs * 100, opt.get(k, 0) / os_ * 100
    if op - bp < 0.5:
        break
    print(f"{k:<58}{bp:>7.2f}%{op:>7.2f}%")
