"""双模拟器联调。仅操作本项目包与指定模拟器，凭据保存在被忽略的 .local。"""
from pathlib import Path
import argparse, base64, json, os, re, subprocess, time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk')))
ADB = str(SDK / 'platform-tools/adb.exe')
LOG = ROOT / '.local/logs'
LOG.mkdir(parents=True, exist_ok=True)
RECEIVER = 'emulator-5556'
CONTROLLER = 'emulator-5554'

def adb(serial, *args, timeout=40):
    p = subprocess.run([ADB, '-s', serial, *map(str,args)], capture_output=True, timeout=timeout)
    if p.returncode:
        raise RuntimeError(p.stderr.decode('utf-8', errors='replace') + p.stdout.decode('utf-8',errors='replace'))
    return p.stdout.decode('utf-8', errors='replace')

def shell(command):
    return adb(RECEIVER, 'shell', command)

def ui():
    adb(RECEIVER, 'shell', 'uiautomator dump /sdcard/controlpad-window.xml')
    return ET.fromstring(adb(RECEIVER, 'shell', 'cat /sdcard/controlpad-window.xml'))

def click(text):
    nodes=[n for n in ui().iter('node') if n.get('clickable')=='true' and text in n.get('text','')]
    nodes.sort(key=lambda n:n.get('text')!=text)
    if nodes:
        x1,y1,x2,y2=map(int,re.findall(r'\d+',nodes[0].get('bounds')))
        adb(RECEIVER, 'shell', 'input', 'tap', (x1+x2)//2, (y1+y2)//2)
        return
    raise RuntimeError('未找到按钮：'+text)

def policy(uid, value):
    if uid < 10000 and uid != 2000:
        raise ValueError('仅允许测试应用与 shell 的 Root 策略')
    q=f'INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) VALUES ({uid},{value},0,1,0)'
    shell(f"su -c '/sbin/magisk --sqlite \"{q}\"'")

def receiver_uid():
    return int(re.search(r'userId=(\d+)', shell('dumpsys package dev.controlpad.receiver')).group(1))

def instrument_root(denied=False):
    result=adb(RECEIVER,'shell','am','instrument','-w','-r','-e','expectDenied',str(denied).lower(),
               'dev.controlpad.receiver.test/androidx.test.runner.AndroidJUnitRunner',timeout=70)
    (LOG / ('root-denied.txt' if denied else 'root-granted.txt')).write_text(result,encoding='utf-8')
    if 'OK (1 test)' not in result:
        raise RuntimeError('应用内 Root 测试失败，见 .local/logs')

def prepare():
    for serial,module in [(RECEIVER,'receiver'),(CONTROLLER,'controller')]:
        adb(serial,'install','-r',ROOT / f'{module}/build/outputs/apk/debug/{module}-debug.apk')
        adb(serial,'install','-r','-t',ROOT / f'{module}/build/outputs/apk/androidTest/debug/{module}-debug-androidTest.apk')
    adb(RECEIVER,'shell','appops','set','dev.controlpad.receiver','SYSTEM_ALERT_WINDOW','allow')
    uid=receiver_uid()
    policy(uid,1)
    try: instrument_root(True)
    finally: policy(uid,2)
    instrument_root(False)
    adb(RECEIVER,'shell','am','start','-n','dev.controlpad.receiver/.MainActivity')
    time.sleep(1)
    for n in ui().iter('node'):
        if '允许模拟器软件编码' in n.get('text','') and n.get('checked')!='true':
            click('允许模拟器软件编码'); break
    click('停止服务'); time.sleep(1)
    policy(uid,1)
    click('启动遥控服务')
    deadline=time.monotonic()+15
    while time.monotonic()<deadline:
        denied_text='\n'.join(n.get('text','') for n in ui().iter('node'))
        if 'Root 授权被拒绝' in denied_text and '服务正常' not in denied_text:
            break
        time.sleep(.3)
    else:
        policy(uid,2)
        raise RuntimeError('拒绝 Root 后没有显示错误或仍显示正常状态')
    (LOG/'root-denied-service.txt').write_text('通过：拒绝 Root 后显示明确错误，服务停止。',encoding='utf-8')
    policy(uid,2)
    click('启动遥控服务')
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        tree=ui()
        texts='\n'.join(n.get('text','') for n in tree.iter('node'))
        if 'UID 0' in texts and re.search(r'配对码：\d{6}',texts): break
        time.sleep(1)
    else: raise RuntimeError('Root 服务未就绪')
    code=re.search(r'配对码：(\d{6})',texts).group(1)
    config={'host':'10.0.2.2','token':code}
    for port in [27183,27184]: adb(RECEIVER,'forward',f'tcp:{port}',f'tcp:{port}')
    shell("su -c 'am start -n dev.controlpad.receiver/.InputLabActivity'")
    time.sleep(1)
    for n in ui().iter('node'):
        if n.get('content-desc')=='click_target':
            x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
            config.update(targetX=(x1+x2)//2,targetY=(y1+y2)//2)
    if 'targetX' not in config: raise RuntimeError('输入测试页未显示')
    (ROOT / '.local/test-config.json').write_text(json.dumps(config),encoding='utf-8')
    print('测试环境已就绪：Android 14 主控 → Android 8.0 Root 受控；应用内授权／拒绝均通过。')

def run():
    config=json.loads((ROOT / '.local/test-config.json').read_text())
    encoded=base64.b64encode(json.dumps(config).encode()).decode()
    result=adb(CONTROLLER,'shell','am','instrument','-w','-r','-e','class','dev.controlpad.controller.EndToEndTest','-e','config',encoded,
        'dev.controlpad.controller.test/androidx.test.runner.AndroidJUnitRunner',timeout=110)
    (LOG / 'integration.txt').write_text(result,encoding='utf-8')
    print(result)
    if 'OK (1 test)' not in result: raise RuntimeError('双端联调测试失败')
    data=shell('run-as dev.controlpad.receiver cat files/lab-events.json')
    (LOG / 'lab-events.json').write_text(data,encoding='utf-8')
    events=json.loads(data)
    if events['multi']<2 or events['touches']<20: raise RuntimeError('受控应用未收到预期的真实触摸序列')
    outcomes=json.loads(shell('run-as dev.controlpad.receiver cat files/lab-results.json'))
    (LOG / 'lab-results.json').write_text(json.dumps(outcomes),encoding='utf-8')
    if not (outcomes['clicks']>=2 and outcomes['longPresses']>=1 and outcomes['scale']>1 and outcomes['drag']>30 and outcomes['scroll']>0):
        raise RuntimeError('应用内点击／长按／缩放／拖动／滚动结果未全部通过：'+str(outcomes))
    print('受控应用真实触摸事件验证通过。')

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--prepare',action='store_true'); parser.add_argument('--run',action='store_true'); args=parser.parse_args()
    if args.prepare: prepare()
    if args.run: run()
