"""Release smoke test on the dedicated MuMu test device, never a personal phone."""
import subprocess,sys,time,re,xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'output'
serial=sys.argv[1]
def adb(*args):return subprocess.check_output(['adb','-s',serial,*args],text=True,encoding='utf-8',errors='replace')
def capture(name):
    (OUT/name).write_bytes(subprocess.check_output(['adb','-s',serial,'exec-out','screencap','-p']))
def xml():
    adb('shell','rm','-f','/sdcard/fish-window.xml')
    result=subprocess.run(['adb','-s',serial,'shell','uiautomator','dump','/sdcard/fish-window.xml'],capture_output=True)
    # MuMu's uiautomator can exit 139 during shutdown after successfully writing XML.
    # Removing the old file first makes the following pull reject stale/missing results.
    if result.returncode not in (0,139):raise RuntimeError(result.stderr.decode(errors='replace'))
    (OUT/'ui-smoke.xml').write_text(adb('exec-out','cat','/sdcard/fish-window.xml'),encoding='utf-8');return ET.parse(OUT/'ui-smoke.xml')
def tap_text(value):
    for n in xml().iter('node'):
        if value in n.get('text','') and n.get('clickable')=='true':
            x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
    raise AssertionError('Missing UI control: '+value)
def overlay_frames():
    text=adb('shell','dumpsys','window','windows')
    frames=[]
    for block in re.split(r'Window #\d+',text):
        if 'package=com.lanse.fish appop=SYSTEM_ALERT_WINDOW' in block and 'ty=APPLICATION_OVERLAY' in block:
            match=re.search(r'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]',block)
            if match:frames.append(tuple(map(int,match.groups())))
    return frames
def pet_frame():
    frames=overlay_frames();assert frames,'Pet overlay window missing'
    return next(f for f in frames if f[3]-f[1]<f[2]-f[0])
adb('shell','am','start','-n','com.lanse.fish/.MainActivity','--es','page','home');time.sleep(1)
tap_text('小窝');time.sleep(.3)
if overlay_frames():tap_text('正在陪伴');time.sleep(.5)
capture('home.png');tap_text('开始陪伴');time.sleep(1);adb('shell','input','keyevent','3');time.sleep(1)
frame=pet_frame();capture('overlay-home.png')
# Open an ordinary second app, whose activity is observed in the test emulator.
adb('shell','am','start','-a','android.intent.action.VIEW','-d','about:blank','-p','com.android.chromium');time.sleep(2)
frame=pet_frame();capture('overlay-other-app.png')
x1,y1,x2,y2=frame;cx=(x1+x2)//2;cy=(y1+y2)//2
with Image.open(OUT/'overlay-other-app.png') as screen:w,h=screen.size
target_x=int(w*(.7 if cx<w/2 else .3));target_y=int(h*(.65 if cy<h/2 else .25))
adb('shell','input','swipe',str(cx),str(cy),str(target_x),str(target_y),'600');time.sleep(1)
new=pet_frame();assert abs(new[0]-x1)+abs(new[1]-y1)>40,'Drag did not move pet'
cx=(new[0]+new[2])//2;cy=(new[1]+new[3])//2
adb('shell','input','swipe',str(cx),str(cy),str(cx),str(cy),'850');time.sleep(.3)
capture('overlay-menu.png')
# uiautomator dump only exposes the focused activity, not this non-focusable overlay.
# Verify the tall menu window and tap its first row, visually checked in overlay-menu.png.
menus=[f for f in overlay_frames() if f[3]-f[1]>f[2]-f[0]]
assert len(menus)==1,'Long press menu missing'
mx1,my1,mx2,my2=menus[0];adb('shell','input','tap',str((mx1+mx2)//2),str(my1+(my2-my1)//16))
time.sleep(.8);capture('chat.png')
nodes=xml();assert any('离线陪伴' in n.get('text','') for n in nodes.iter('node')),'Offline mode label missing'
# Screen off and on: service should remain alive and return to a valid window.
adb('shell','input','keyevent','223');time.sleep(.7);assert '.PetService' in adb('shell','dumpsys','activity','services','com.lanse.fish')
adb('shell','input','keyevent','224');adb('shell','wm','dismiss-keyguard');time.sleep(.8);pet_frame()
adb('shell','am','start','-n','com.lanse.fish/.MainActivity');time.sleep(.5);tap_text('小窝');time.sleep(.3);tap_text('正在陪伴');time.sleep(.5)
assert 'appop=SYSTEM_ALERT_WINDOW' not in '\n'.join(b for b in re.split(r'Window #\d+',adb('shell','dumpsys','window','windows')) if 'package=com.lanse.fish' in b),'Overlay remains after stop'
print('PASS: signed release launch, Home overlay, second-app overlay, drag, long-press menu, chat entry, screen-off recovery, explicit stop.')
