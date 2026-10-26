"""Host regression gate: real controller methods / PetApi, deterministic platform stubs.

Does not replace device rendering, gesture dispatch, or real-account tests.
JDK 17 required. Only the API fixture's loopback server receives test requests.
"""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, sys, urllib.request

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'output/regressions'
FIXTURES=ROOT/'scripts/regression_fixtures'
SHA='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'

def run(args):
    return subprocess.check_output([str(a) for a in args],cwd=ROOT,text=True,encoding='utf-8',stderr=subprocess.STDOUT)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--json-jar',type=Path);args=ap.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    jar=args.json_jar or OUT/'json-20240303.jar'
    if not jar.exists():
        urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',jar)
    if hashlib.sha256(jar.read_bytes()).hexdigest()!=SHA:raise RuntimeError('JSON dependency hash mismatch')
    jar=jar.resolve()
    suffix='.exe' if os.name=='nt' else ''
    home=os.environ.get('JAVA_HOME')
    java=Path(home)/'bin'/('java'+suffix) if home else 'java'
    javac=Path(home)/'bin'/('javac'+suffix) if home else 'javac'
    controller=OUT/'controller';controller.mkdir(exist_ok=True)
    source=(ROOT/'app/src/main/java/com/lanse/fish/PetService.java').read_text('utf-8')
    a=source.index('    private final class Pet {');b=source.index('    // ---------------- compact floating menu',a)
    template=(FIXTURES/'Controller.java.template').read_text('utf-8')
    (controller/'PetService.java').write_text(template.replace('/* PET_CONTROLLER */',source[a:b]),'utf-8')
    shutil.copyfile(FIXTURES/'AnimationCatalog.java',controller/'AnimationCatalog.java')
    run([javac,'-encoding','UTF-8','-cp',jar,'-d',controller/'classes',*controller.glob('*.java')])
    text=run([java,'-Dfile.encoding=UTF-8','-cp',str(controller/'classes')+os.pathsep+str(jar),'com.lanse.fish.PetService',ROOT/'app/src/main/assets/catalog.json'])
    (OUT/'controller.json').write_text(text,'utf-8');rows=json.loads(text)
    for r in rows:
        if 'tap_anchor_delta' in r:assert r['tap_anchor_delta']<=1,r
        if 'drag_scale_y' in r:
            assert r['drag_scale_y']==1 and not r['drag_resized'] and not r['drag_changed_clip'],r
        if 'play_calls_after_cancel' in r:assert r['play_calls_after_cancel']==1,r
        if 'play_calls_after_resume' in r:assert r['play_calls_after_resume']==1,r
        if 'cancel_anchor_error' in r:
            assert abs(r['cancel_anchor_error'])<=1 and abs(r['anchor_y_error'])<=1,r
            assert abs(r['first_anchor_delta']-r['first_finger_delta'])<=1,r
    api=OUT/'api';shutil.copytree(FIXTURES/'api',api/'src',dirs_exist_ok=True)
    shutil.copyfile(ROOT/'app/src/main/java/com/lanse/fish/PetApi.java',api/'src/com/lanse/fish/PetApi.java')
    run([javac,'-encoding','UTF-8','-cp',jar,'-d',api/'classes',*(api/'src').rglob('*.java')])
    text=run([java,'-Dfile.encoding=UTF-8','-cp',str(api/'classes')+os.pathsep+str(jar),'com.lanse.fish.ChatFixture'])
    (OUT/'api.json').write_text(text,'utf-8');api_rows=json.loads(text)
    for r in api_rows:
        for k in ('normalized_request_text_returned','fixture_credential_returned','fixture_request_text_returned'):
            assert not r.get(k,False),(r['scenario'],k)
        if r['scenario'] in ('normal','contentArray'):assert r['savedMessages']==2,r
        if r['scenario']=='offline':assert r['requestPaths']==[],r
        if r['scenario'] in ('chat400','fullEndpoint','reasoningOnly','sse','blankModel'):assert r['savedMessages']==0,r
    run([sys.executable,FIXTURES/'test_edges.py'])
    edges=json.loads((OUT/'edge-validator-results.json').read_text('utf-8'))
    assert all(x['matches_expectation'] for x in edges['cases']+edges['zorder_cases'])
    summary={'controller_cases':len(rows),'api_cases':len(api_rows),'edge_cases':len(edges['cases']+edges['zorder_cases']),
             'controller_source_sha256':hashlib.sha256(source[a:b].encode()).hexdigest(),'result':'PASS',
             'scope':'host logic and loopback HTTP only; not real phone or real account'}
    (OUT/'summary.json').write_text(json.dumps(summary,indent=2),'utf-8');print(json.dumps(summary,indent=2))

if __name__=='__main__':main()
