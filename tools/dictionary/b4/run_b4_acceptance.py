"""Offline B4 host acceptance; requires existing SDK/JDK/Gradle and pinned private source ZIPs.
No device or application database is used. Android widgets/framework are explicit test adapters.
B4_PROJECT_ROOT selects the local dependency/ZIP root; B4_SOURCE selects the exact frozen source.
"""
from pathlib import Path
import argparse,json,os,subprocess,time,re

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--project',type=Path,default=Path(__file__).resolve().parents[3])
 parser.add_argument('--source',type=Path,required=True)
 parser.add_argument('--evidence',type=Path,required=True)
 args=parser.parse_args();project=args.project.resolve();source=args.source.resolve();evidence=args.evidence.resolve()
 root=Path(os.environ.get('B4_PROJECT_ROOT',str(Path.cwd()))).resolve()
 assert not evidence.exists(),'Never overwrite an acceptance run'
 evidence.mkdir(parents=True)
 # Refuse stale production slices, without rewriting host or product sources.
 repo=(source/'app/src/main/java/com/shici/app/data/LearningRepository.kt').read_text();repo=repo[repo.index('/** Explicit'):]
 ui=(source/'app/src/main/java/com/shici/app/ui/StudyScreen.kt').read_text();ui=ui[ui.index('/**'):]
 test=project/'app/src/test/java/com/shici/app/core/b4'
 assert (test/'host/RepositoryHost.kt').read_text().endswith(repo),'stale repository host'
 host=(test/'uihost/StudyScreenHost.kt').read_text();assert host.endswith(ui),'stale study UI host'
 activity=(source/'app/src/main/java/com/shici/app/ui/MainActivity.kt').read_text()
 for pattern in [r'    private val studyScreenDelegate = lazy .*\n    private val studyScreen by studyScreenDelegate',r'    override fun onDestroy\(\) \{.*?\n    }']:
  assert re.search(pattern,activity,re.S if 'onDestroy' in pattern else 0)[0] in host,'stale activity lifecycle slice'
 env=os.environ.copy();env.update(B4_PROJECT_ROOT=str(root),B4_SOURCE=str(source),JAVA_HOME=str(root/'.tools/jdk17'),PYTHONDONTWRITEBYTECODE='1')
 env['PATH']=str(root/'.tools/jdk17/bin')+':'+env['PATH']
 # Uses current checked-in adapter and exact candidate; no old private production host is read.
 init=evidence/'host-tests.gradle';quote=lambda p:str(p).replace('\\','\\\\').replace("'","\\'")
 init.write_text("allprojects { tasks.withType(Test).configureEach { maxHeapSize='1536m'; systemProperty 'b4.base', '"+quote(root/'.tools/validation/20261016-b4-study-review')+"'; systemProperty 'b4.source', '"+quote(source)+"'; systemProperty 'b4.bridge', '"+quote(project/'tools/dictionary/b4/sqlite_bridge.py')+"' } }\n")
 commands=[['python3',str(project/'tools/dictionary/b4/test_study_books_acceptance.py')],[str(root/'.tools/gradle-8.11.1/bin/gradle'),'-p',str(project),'-g',str(root/'.tools/gradle-home'),'-Dorg.gradle.java.home='+str(root/'.tools/jdk17'),'--offline','--no-daemon','--continue','-I',str(init),':app:testDebugUnitTest','--tests','com.shici.app.core.b4.*','--tests','com.shici.app.core.b3.B3SessionGateAcceptanceTest','--tests','com.shici.app.core.b3.B3R2ApplyHandleAcceptanceTest',':app:compileDebugAndroidTestKotlin']]
 records=[]
 for i,cmd in enumerate(commands):
  log=evidence/f'command-{i}.log';start=time.monotonic()
  with log.open('w') as f:result=subprocess.run(cmd,cwd=root,env=env,stdout=f,stderr=subprocess.STDOUT)
  record={'argv':cmd,'exit_code':result.returncode,'duration_seconds':round(time.monotonic()-start,2),'log':str(log)};records.append(record);print(json.dumps(record,ensure_ascii=False),flush=True)
 (evidence/'commands.json').write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n')
 return 0 if all(r['exit_code']==0 for r in records) else 1

if __name__=='__main__':raise SystemExit(main())
