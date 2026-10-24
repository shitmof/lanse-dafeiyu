"""Check packaging hashes, transparent frames, timing, and crop metadata for all 106 clips."""
from pathlib import Path
import hashlib,json,subprocess
import numpy as np
from PIL import Image
root=Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets'
catalog=json.loads((assets/'catalog.json').read_text('utf-8'))
assert len(catalog['animations'])==106
assert len(list((assets/'memes').glob('*.png')))==27
VISUAL_ALPHA=8
names=set(); total_frames=0
for row in catalog['animations']:
    assert row['name'] not in names
    names.add(row['name'])
    visual=row.get('visual')
    assert visual and len(visual)==4,row['name']
    assert 0 <= visual[0] < visual[2] <= 1 and 0 <= visual[1] < visual[3] <= 1,row['name']
    path=assets/row['file']; assert hashlib.sha256(path.read_bytes()).hexdigest()==row['sha256']
    with Image.open(path) as im:
        assert im.size==(480,270) and im.is_animated and im.n_frames>100,row['name']
        duration=0; vx0,vy0,vx1,vy1=480,270,-1,-1
        for i in range(im.n_frames):
            im.seek(i); im.load();duration+=im.info.get('duration',0)
            alpha=np.asarray(im.convert('RGBA').getchannel('A'))
            ys,xs=np.where(alpha>VISUAL_ALPHA)
            if len(xs):
                vx0=min(vx0,int(xs.min())); vy0=min(vy0,int(ys.min()))
                vx1=max(vx1,int(xs.max())+1); vy1=max(vy1,int(ys.max())+1)
        assert vx1>vx0 and vy1>vy0,row['name']
        expected=[vx0/480,vy0/270,vx1/480,vy1/270]
        assert max(abs(a-b) for a,b in zip(visual,expected)) <= 0.003, (row['name'],visual,expected)
        source=root/'research/upstream-assets'/(row['name']+'.webm')
        probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','json',str(source)]))
        original_duration=float(probe['format']['duration'])*1000
        assert abs(duration-original_duration)<100,(row['name'],duration,original_duration)
        im.seek(im.n_frames//2);alpha=im.convert('RGBA').getchannel('A');assert alpha.getextrema()==(0,255),row['name']
        total_frames+=im.n_frames
print(json.dumps({'animations':len(names),'memes':27,'decoded_frames':total_frames,'hashes':'pass','alpha':'pass','duration':'pass','visual_bounds':'pass'}))
