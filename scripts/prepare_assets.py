"""Fetch pinned MIT-licensed dsh-pet 0.3.8 assets and preserve VP9 alpha in WebP.
No keying or generated substitutes. Full duration and source timing retained;
the canvas is resized from 640x360 to 480x270 for phone screens.
Requires ffmpeg with libvpx.
"""
from pathlib import Path
import concurrent.futures, hashlib, json, os, re, shutil, subprocess, time, urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
SOURCE = ROOT / 'research/upstream-assets'
UPSTREAM_COMMIT = '593b9b0785f883630243ef6213542f4603ef69ad'
RAW = f'https://raw.githubusercontent.com/PC2005-cloud/dsh-pet/{UPSTREAM_COMMIT}/'

def fetch(relative, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 100: return
    for attempt in range(5):
        try:
            with urllib.request.urlopen(RAW + urllib.parse.quote(relative, safe='/'), timeout=25) as r:
                data=r.read()
            tmp=dest.with_suffix(dest.suffix+'.part'); tmp.write_bytes(data); tmp.replace(dest); return
        except Exception:
            if attempt==4: raise
            time.sleep(1+attempt)

def uncomment(s):
    # Respect strings: URLs and prompt text must not be damaged by comment stripping.
    pattern = r'("(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/)'
    return re.sub(pattern, lambda m:m[0] if m[0].startswith('"') else '', s)

def main():
    ASSETS.mkdir(parents=True,exist_ok=True)
    expected={}
    if (ASSETS/'catalog.json').exists():
        expected={row['name']:row['source_sha256'] for row in json.loads((ASSETS/'catalog.json').read_text('utf-8'))['animations']}
    fetch('dsh-pet/assets/config.jsonc',SOURCE/'config.jsonc')
    config=json.loads(uncomment((SOURCE/'config.jsonc').read_text('utf-8')))
    (ASSETS/'original-config.json').write_text(json.dumps(config,ensure_ascii=False,indent=2),'utf-8')
    names={}
    a=config['animations']
    for key,cat in [('idle','日常'),('turn','日常'),('drag','互动'),('clicks','互动')]:
        for name in a[key]: names[name]=cat
    for row in a['moves']['actions']: names[row['name']]='移动'
    for row in a['categories']:
        for name in row['actions']:names[name]={'小动作':'日常','吃什么':'吃饭'}.get(row['id'],row['id'])
    for event,values in a['events'].items():
        for value in values:
            for name in value if isinstance(value,list) else [value]: names[name]={'balance':'余额','whisper':'碎碎念','workStatus':'工作'}[event]
    # All entries in the upstream animation pools are packaged, including event-only poses.
    print(f'Preparing {len(names)} original animations',flush=True)
    def job(item):
        name,cat=item; ident=hashlib.sha256(name.encode()).hexdigest()[:12]
        src=SOURCE/(name+'.webm'); fetch('dsh-pet/assets/webm/'+name+'.webm',src)
        if name in expected and hashlib.sha256(src.read_bytes()).hexdigest()!=expected[name]:
            raise RuntimeError('Upstream SHA-256 mismatch: '+name)
        dest=ASSETS/'animations'/(ident+'.webp');dest.parent.mkdir(exist_ok=True)
        existing_width=0
        if dest.exists():
            header=dest.read_bytes()[:30]
            if header[12:16]==b'VP8X': existing_width=int.from_bytes(header[24:27],'little')+1
        if existing_width!=480:
            temp=dest.with_suffix('.tmp.webp')
            proc=subprocess.run(['ffmpeg','-nostdin','-v','error','-y','-c:v','libvpx-vp9','-i',str(src),'-vf','scale=480:270:flags=lanczos','-an','-c:v','libwebp_anim','-quality','70','-compression_level','3','-loop','0',str(temp)],capture_output=True,timeout=120)
            if proc.returncode: raise RuntimeError(proc.stderr.decode(errors='replace'))
            temp.replace(dest)
        result={'id':ident,'name':name,'category':cat,'file':'animations/'+dest.name,
                'source_sha256':hashlib.sha256(src.read_bytes()).hexdigest(),'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()}
        print('Ready: '+name,flush=True); return result
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: rows=list(pool.map(job,names.items()))
    # Include all original meme images and the original attribution.
    memes=config.get('memes',{})
    if isinstance(memes,dict):
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            list(pool.map(lambda name:fetch('dsh-pet/assets/memes/'+name+'.png',ASSETS/'memes'/(name+'.png')),memes))
    fetch('LICENSE',ASSETS/'UPSTREAM-LICENSE.txt')
    (ASSETS/'catalog.json').write_text(json.dumps({'upstream':'PC2005-cloud/dsh-pet','version':'0.3.8','commit':UPSTREAM_COMMIT,'animations':rows},ensure_ascii=False,indent=2),'utf-8')
    print(f'Complete: {len(rows)} animations; {sum(p.stat().st_size for p in (ASSETS/"animations").glob("*.webp"))/1048576:.1f} MiB',flush=True)

if __name__=='__main__':
    import urllib.parse
    main()
