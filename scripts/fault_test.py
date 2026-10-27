"""已完成配对后，验证分辨率变化、工作进程退出、权限缺失及撤销配对。"""
import json,time
from emulator_test import adb,shell,ui,click,LOG,RECEIVER
from lifecycle_test import credentials,auth,read,send,connect

def text(): return '\n'.join(n.get('text','') for n in ui().iter('node'))
def wait_for(label,predicate,seconds=15):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if predicate(): return
        time.sleep(.3)
    raise AssertionError(label)
def page():
    adb(RECEIVER,'shell','am','start','-f','0x14000000','-n','dev.controlpad.receiver/.MainActivity')
    time.sleep(.5)

def run():
    identity,device=credentials(); results={}
    page(); click('启动遥控服务'); wait_for('受控服务未就绪',lambda:'UID 0' in text() and '服务正常' in text())
    s,old_display=auth(identity,device)
    try:
        shell('wm size 1920x1080')
        until=time.monotonic()+8; display=0
        while time.monotonic()<until:
            p=read(s)
            if p.get(1)==7:
                assert (p[5],p[6])==(1920.0,1080.0); display=p[4]; break
        assert display>old_display
        send(s,{1:21,2:1})
        while True:
            p=read(s)
            if p.get(1)==8: break
        assert (p[5],p[6])==(960.0,540.0)
        results['1920x1080_display_and_center']=True
    finally:
        s.close(); shell('wm size reset'); time.sleep(1.5)
    # 仅终止本项目 RootMain，不触碰其他应用或 Root 管理器。
    processes=shell('ps -A -o PID,ARGS')
    workers=[line.split()[0] for line in processes.splitlines() if len(line.split(None,1))==2 and line.split(None,1)[1].startswith('app_process / dev.controlpad.worker.RootMain ')]
    assert len(workers)==1, '预期恰好一个本项目 Root 工作进程'
    shell(f"su -c 'kill {int(workers[0])}'")
    page()
    wait_for('工作进程退出未报告错误',lambda: 'Root：未就绪' in text())
    assert '服务正常' not in text()
    results['root_worker_exit_reported']=True
    # 重新启用后，单独覆盖服务层的悬浮窗权限拒绝。
    click('启动遥控服务'); wait_for('工作进程未恢复',lambda:'UID 0' in text())
    click('停止服务'); time.sleep(.5)
    adb(RECEIVER,'shell','appops','set','dev.controlpad.receiver','SYSTEM_ALERT_WINDOW','deny')
    try:
        shell("su -c 'am startservice -n dev.controlpad.receiver/.ReceiverService'")
        wait_for('光标权限分支未完成',lambda:('服务正常' in text() and '系统 HID 鼠标' in text()) or '需要授权兼容光标' in text())
        if '系统 HID 鼠标' in text() and '服务正常' in text():
            results['native_mouse_without_overlay']=True
        else:
            assert '服务正常' not in text()
            results['missing_compatibility_overlay_reported']=True
    finally:
        adb(RECEIVER,'shell','appops','set','dev.controlpad.receiver','SYSTEM_ALERT_WINDOW','allow')
    click('启动遥控服务'); wait_for('恢复权限后服务未恢复',lambda:'UID 0' in text() and '服务正常' in text())
    s,_=auth(identity,device); s.close(); time.sleep(.5)
    click('撤销全部已配对设备'); click('撤销')
    time.sleep(.5)
    s=connect(device)
    try:
        assert read(s)[1]==1
        send(s,{1:2,7:1,9:identity,10:device['token']})
        p=read(s)
        assert p[1]==4,'撤销后仍接受旧凭据'
        results['revoked_credentials_rejected']=True
    finally:s.close()
    (LOG/'faults.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    print(json.dumps(results,indent=2))

if __name__=='__main__':run()
