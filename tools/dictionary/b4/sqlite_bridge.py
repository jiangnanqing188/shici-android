"""Test-only transport to stdlib SQLite, no application database or Android runtime."""
import sqlite3,sys,struct
r=sys.stdin.buffer;w=sys.stdout.buffer
c=sqlite3.connect(':memory:',isolation_level=None);c.execute('PRAGMA foreign_keys=ON')
def ri():return struct.unpack('>i',r.read(4))[0]
def rs():return r.read(ri()).decode('utf8')
def wi(n):w.write(struct.pack('>i',n))
def ws(s):
 b=str(s).encode('utf8');wi(len(b));w.write(b)
while True:
 try:
  op=rs();q=rs();a=[None if (v:=rs())=='<NULL>' else v for _ in range(ri())]
 except (EOFError,struct.error):break
 try:
  cur=c.execute(q,a)
  names=[d[0] for d in cur.description] if cur.description else []
  rows=cur.fetchall() if names else []
  wi(0);wi(cur.rowcount);wi(len(names))
  for n in names:ws(n)
  wi(len(rows))
  for row in rows:
   for v in row:ws('<NULL>' if v is None else v)
 except Exception as e:wi(1);ws(type(e).__name__+': '+str(e))
 w.flush()
