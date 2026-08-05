import sys, re, collections

leaf_methods = collections.Counter()
all_frames = collections.Counter()
total = 0
in_stack = False
in_sample = False
stack_lines = []
sample_count = 0

with open('/tmp/jfr_output.txt', 'r', encoding='utf-8', errors='replace') as f:
    for line in f:
        if line.startswith('jdk.ExecutionSample {'):
            in_sample = True
            in_stack = False
            stack_lines = []
            sample_count += 1
        elif in_sample:
            if 'stackTrace = [' in line:
                in_stack = True
                stack_lines = []
            elif in_stack:
                if line.strip() == ']':
                    # End of stack trace, process it
                    st = '\n'.join(stack_lines)
                    methods = re.findall(r'([a-zA-Z_][\w/]*(?:\.[a-zA-Z_]\w+)*)\.([a-zA-Z_]\w+)\s*\(', st)
                    full = [f'{p}.{m}' for p, m in methods]
                    if full:
                        total += 1
                        leaf_methods[full[0]] += 1
                        for fn in full:
                            all_frames[fn] += 1
                    in_stack = False
                    in_sample = False
                elif line.strip() == '}':
                    # Sample ended without proper stack (shouldn't happen)
                    in_sample = False
                    in_stack = False
                else:
                    stack_lines.append(line)
            elif line.strip() == '}':
                in_sample = False

print(f'Total samples parsed: {sample_count}')
print(f'Total with valid stack: {total}')

print(f'\n{"="*60}')
print('TOP 40 LEAF METHODS (top of stack / innermost)')
print(f'{"="*60}')
for m, c in leaf_methods.most_common(40):
    print(f'  {c*100/total:5.1f}% ({c:5d}) {m}')

print(f'\n{"="*60}')
print('TOP 40 ALL FRAMES')
print(f'{"="*60}')
for m, c in all_frames.most_common(40):
    print(f'  {c*100/total:5.1f}% ({c:5d}) {m}')