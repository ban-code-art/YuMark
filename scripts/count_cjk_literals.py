import io, os, re, sys

lit = re.compile(r'"(?:[^"\n]|\\.)*"')
cjk = re.compile(u'[一-鿿]')
base = sys.argv[1] if len(sys.argv) > 1 else '.'
rows = []
for root, _, files in os.walk(base):
    for f in files:
        if not f.endswith('.kt'):
            continue
        p = os.path.join(root, f)
        s = io.open(p, encoding='utf-8').read()
        lines = s.split('\n')
        n = 0
        in_block = False
        for l in lines:
            t = l.strip()
            if in_block:
                if '*/' in t:
                    in_block = False
                continue
            if t.startswith('/*'):
                if '*/' not in t:
                    in_block = True
                continue
            if t.startswith('//') or t.startswith('*'):
                continue
            for m in lit.findall(l):
                if cjk.search(m):
                    n += 1
        if n:
            rows.append((n, len(lines), p.replace(os.sep, '/')))
rows.sort(reverse=True)
tot = 0
for n, c, p in rows:
    tot += n
    print('%4d 处 / %5d 行  %s' % (n, c, p))
print('---- 合计 %d 处，%d 个文件 ----' % (tot, len(rows)))
