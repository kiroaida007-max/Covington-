#!/usr/bin/env python3
import sys, re
# Usage: python3 -I scripts/extract-aosp-opcodes.py dalvik-bytecode.html > dalvik-opcodes.tsv
# Regenerates the opcode cross-check table used by :formats-dex tests.
from html.parser import HTMLParser
class P(HTMLParser):
    def __init__(s):
        super().__init__(); s.rows=[]; s.row=None; s.cell=None
    def handle_starttag(s,t,a):
        if t=='tr': s.row=[]
        if t in('td','th') and s.row is not None: s.cell=[]
        if t in ('br','p') and s.cell is not None: s.cell.append('\n')
    def handle_endtag(s,t):
        if t in('td','th') and s.cell is not None and s.row is not None:
            s.row.append(''.join(s.cell).strip()); s.cell=None
        if t=='tr' and s.row is not None:
            s.rows.append(s.row); s.row=None
    def handle_data(s,d):
        if s.cell is not None: s.cell.append(d)
p=P(); p.feed(open(sys.argv[1],encoding='utf-8').read())
out={}
for r in p.rows:
    if not r: continue
    m=re.match(r'^([0-9a-f]{2})(?:\.\.([0-9a-f]{2}))? ([0-9][0-9a-z]{1,3})$', r[0].strip())
    if not m: continue
    lo=int(m.group(1),16); hi=int(m.group(2) or m.group(1),16); fmt=m.group(3)
    syn=r[1]
    if '(unused)' in syn:
        for op in range(lo,hi+1): out[op]=('(unused)',fmt)
        continue
    if lo==hi:
        out[lo]=(syn.split()[0],fmt)
    else:
        for mm in re.finditer(r'\b([0-9a-f]{2}): ([a-z0-9/\-]+)', syn):
            op=int(mm.group(1),16)
            if lo<=op<=hi: out[op]=(mm.group(2),fmt)
for op in range(256):
    name,fmt=out.get(op,('MISSING','?'))
    print(f"{op:02x}\t{name}\t{fmt}")
