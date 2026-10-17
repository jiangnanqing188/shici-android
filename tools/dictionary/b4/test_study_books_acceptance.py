"""Independent pinned ZIP membership checks. No meaning review, downloads, or source rewriting."""
from pathlib import Path
import os,unittest,json,gzip,zipfile,hashlib,unicodedata,sys,importlib.util,tempfile
R=Path(os.environ.get('B4_PROJECT_ROOT',str(Path(__file__).resolve().parents[3])));B=R/'.tools/validation/20261016-b4-study-review';S=Path(os.environ.get('B4_SOURCE',str(B/'source-g3')))
sys.dont_write_bytecode=True
sys.path.insert(0,str(S/"tools/dictionary"))
spec=importlib.util.spec_from_file_location('study_builder',S/'tools/dictionary/build_study_books.py');builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)
INV=R/'docs/dictionary/source-inventory-20261009.json';SRC=R/'.tools/dictionaries/source-review-20261009'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def norm(v):return ' '.join(unicodedata.normalize('NFC',v).replace('’',"'").lower().strip().split())
def originals():
 inv=json.loads(INV.read_text());out={};sources={}
 for e in inv['sources']:
  if e['group']=='kajweb' and e['exam'] in ('ky','cet6'):
   with zipfile.ZipFile(SRC/e['file']) as z:rows=[json.loads(x) for x in z.read(e['member']).decode('utf-8-sig').splitlines() if x.strip()]
   out[e['exam']]=list(dict.fromkeys(norm(row['headWord']) for row in rows));sources[e['exam']]=e
 return out,sources
def actual():
 out={};cur=None
 for line in gzip.decompress((S/'app/src/main/assets/dictionary/study-books.tsv.bin').read_bytes()).decode('utf8').splitlines()[1:]:
  f=line.split('\t')
  if f[0]=='BOOK':cur=f[1];out[cur]=[]
  else:out[cur].append(f[2])
 return out
class B4AssetAcceptance(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.want,cls.sources=originals();cls.got=actual();cls.man=json.loads((S/'app/src/main/assets/dictionary/study-books-manifest.json').read_text())
  for k,values in cls.want.items():(B/f'sol-tools-g3/expected-{k}.txt').write_text('\n'.join(values)+'\n')
 def test_01_original_zip_sha_pinned(self):
  for e in self.sources.values():self.assertEqual(e['sha256'],sha(SRC/e['file']));self.assertEqual('3992bcb94c800a2fd38a9fd6ff95b2353e755363',e['commit'])
 def test_02_exact_kaoyan_order_and_members(self):self.assertEqual(self.want['ky'],self.got['ky']);self.assertEqual(4533,len(self.got['ky']))
 def test_03_exact_cet6_order_and_members(self):self.assertEqual(self.want['cet6'],self.got['cet6']);self.assertEqual(2078,len(self.got['cet6']))
 def test_04_only_requested_fixed_books(self):self.assertEqual({'ky','cet6'},set(self.got))
 def test_05_cross_book_overlap_retained(self):self.assertGreater(len(set(self.got['ky'])&set(self.got['cet6'])),0)
 def test_06_all_keys_in_original_dictionary(self):
  payload=gzip.decompress((S/'app/src/main/assets/dictionary/kajweb.tsv.bin').read_bytes()).decode('utf8');keys={f[1] for row in payload.splitlines()[1:] if (f:=row.split('\t'))[0]=='E'}
  self.assertEqual(set(),set(self.got['ky']+self.got['cet6'])-keys)
 def test_07_asset_hash_and_counts(self):
  self.assertEqual(self.man['asset_sha256'],sha(S/'app/src/main/assets/dictionary/study-books.tsv.bin'));self.assertEqual({'ky':4533,'cet6':2078},self.man['book_counts'])
 def test_08_unreviewed_and_license_flags(self):self.assertEqual('unreviewed',self.man['content_review_status']);self.assertFalse(self.man['syllabus_verified']);self.assertEqual('unresolved',self.man['redistribution_status'])
 def test_09_builder_reproduces_exact_asset(self):
  with tempfile.TemporaryDirectory(dir=B/'sol-tools-g3') as tmp:
   d=Path(tmp)/'generated';builder.build(INV,SRC,d);self.assertEqual((S/'app/src/main/assets/dictionary/study-books.tsv.bin').read_bytes(),(d/'dictionary/study-books.tsv.bin').read_bytes())
 def test_10_builder_refuses_existing_destination(self):
  with tempfile.TemporaryDirectory(dir=B/'sol-tools-g3') as tmp:
   with self.assertRaises(ValueError):builder.build(INV,SRC,Path(tmp))
 def test_11_builder_rejects_wrong_pinned_commit(self):
  with tempfile.TemporaryDirectory(dir=B/'sol-tools-g3') as tmp:
   d=json.loads(INV.read_text());next(e for e in d['sources'] if e['group']=='kajweb' and e['exam']=='ky')['commit']='wrong';p=Path(tmp)/'inventory.json';p.write_text(json.dumps(d))
   with self.assertRaises(ValueError):builder.build(p,SRC,Path(tmp)/'out')
 def test_12_builder_rejects_corrupt_source_hash(self):
  with tempfile.TemporaryDirectory(dir=B/'sol-tools-g3') as tmp:
   d=json.loads(INV.read_text());next(e for e in d['sources'] if e['group']=='kajweb' and e['exam']=='ky')['sha256']='0'*64;p=Path(tmp)/'inventory.json';p.write_text(json.dumps(d))
   with self.assertRaises(ValueError):builder.build(p,SRC,Path(tmp)/'out')
if __name__=='__main__':unittest.main(verbosity=2)
