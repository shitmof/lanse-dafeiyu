"""Exercise the current validator with controlled actual-frame fixtures, no ADB."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('edge_current', ROOT / 'scripts/edge_smoke_test.py')
edge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(edge)

def window(frame, token='123', package='com.lanse.fish', index=0):
    x, y, x2, y2 = frame
    return (f'Window #{index} Window{{{token} u0 {package}}}:\n'
            '  mViewVisibility=0x0\n'
            '  mAttrs={(0,400)(160x180) ty=APPLICATION_OVERLAY fmt=TRANSLUCENT}\n'
            f'  Frames: parent=[0,0][1080,2400] frame=[{x},{y}][{x2},{y2}]\n')

cases = [
    ('normal_left', 'left', window((0,400,160,580)), True),
    ('normal_right', 'right', window((920,400,1080,580)), True),
    ('full_display_not_restored', 'left', window((0,0,1080,2400)), False),
    ('requested_left_actual_inset', 'left', window((80,400,240,580)), False),
    ('unrelated_overlay_at_edge', 'left', window((0,100,20,120),'999','unrelated.fixture') + window((300,400,460,580),index=1), False),
    ('usable_area_fullscreen_left', 'left', window((0,80,1080,2320)), False),
    ('usable_area_fullscreen_right', 'right', window((0,80,1080,2320)), False),
    ('wholly_offscreen_left', 'left', window((-400,400,-200,580)), False),
    ('wholly_offscreen_right', 'right', window((1200,400,1400,580)), False),
]
rows=[]
for name, side, after_dump, expected in cases:
    state={'dragged':False}
    def fake_adb(device, *args, **kwargs):
        if args == ('shell','dumpsys','window','displays'):
            return 'Display: cur=1080x2400'
        if args == ('shell','dumpsys','window','windows'):
            return after_dump if state['dragged'] else window((300,400,460,580))
        if args[:3] == ('shell','input','swipe'):
            state['dragged']=True
            return ''
        raise AssertionError(args)
    edge.adb=fake_adb
    stdout=io.StringIO()
    with contextlib.redirect_stdout(stdout):
        actual,_=edge.drag('fixture-only','123',side,True,0)
    rows.append({'scenario':name,'expected_pass':expected,'actual_pass':actual,
                 'matches_expectation':actual==expected,'output':stdout.getvalue().strip()})

zorder=[]
# AOSP dumpsys traverses top-to-bottom. Index 0 is visually above index 1.
for label, dump, token, expected in [
 ('lower_pet_covered_by_upper',window((300,400,460,580),'aaa',index=0)+window((300,400,460,580),'bbb',index=1),'bbb',True),
 ('upper_pet_not_covered_by_lower',window((300,400,460,580),'aaa',index=0)+window((300,400,460,580),'bbb',index=1),'aaa',False),
]:
    def fake_order_adb(device,*args,**kwargs):return dump
    edge.adb=fake_order_adb
    actual=edge.target_covered('fixture-only',token,380,490)
    zorder.append({'scenario':label,'expected_covered':expected,'actual_covered':actual,'matches_expectation':actual==expected})
payload={'zorder_cases':zorder,'scope' :'validator logic only; no device calls or observed phone failures',
         'subject':'current checkout','cases':rows}
(ROOT/'output/regressions/edge-validator-results.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n','utf-8')
print(json.dumps(payload,ensure_ascii=False,indent=2))
