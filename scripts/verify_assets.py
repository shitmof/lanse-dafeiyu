"""Check packaging hashes, transparent frames, and timing for all 106 clips."""
from pathlib import Path
import hashlib,json,subprocess
from PIL import Image
root=Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets'
catalog=json.loads((assets/'catalog.json').read_text('utf-8'))
assert len(catalog['animations'])==106
assert len(list((assets/'memes').glob('*.png')))==27
names=set(); total_frames=0
for row in catalog['animations']:
    assert row['name'] not in names
    names.add(row['name'])
    path=assets/row['file']; assert hashlib.sha256(path.read_bytes()).hexdigest()==row['sha256']
    with Image.open(path) as im:
        assert im.size==(480,270) and im.is_animated and im.n_frames>100,row['name']
        duration=0
        for i in range(im.n_frames):
            im.seek(i); im.load();duration+=im.info.get('duration',0)
        source=root/'research/upstream-assets'/(row['name']+'.webm')
        probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','json',str(source)]))
        original_duration=float(probe['format']['duration'])*1000
        assert abs(duration-original_duration)<100,(row['name'],duration,original_duration)
        im.seek(im.n_frames//2);alpha=im.convert('RGBA').getchannel('A');assert alpha.getextrema()==(0,255),row['name']
        total_frames+=im.n_frames
print(json.dumps({'animations':len(names),'memes':27,'decoded_frames':total_frames,'hashes':'pass','alpha':'pass','duration':'pass'}))
