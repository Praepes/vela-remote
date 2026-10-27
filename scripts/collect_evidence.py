"""采集双模拟器的协议 RTT、预览释放和截图证据；不把 RTT 当作可见操作延迟。"""
from pathlib import Path
import json,time,statistics,subprocess
from emulator_test import adb,shell,LOG,CONTROLLER
from lifecycle_test import credentials,connect,read,send

def run():
    identity,device=credentials(); s=connect(device); assert read(s)[1]==1
    send(s,{1:2,7:1,9:identity,10:device['token']}); auth=read(s); assert auth[1]==3
    read(s)
    samples=[]; seq=0
    for _ in range(30):
        seq+=1; start=time.perf_counter(); send(s,{1:5,2:seq})
        while read(s).get(1)!=6:pass
        samples.append((time.perf_counter()-start)*1000)
        time.sleep(.02)
    video=connect(device,27184); send(video,{1:31,9:auth[9]}); assert read(video)[1]==3
    seq+=1; send(s,{1:30,2:seq,8:1})
    format_packet=read(video); assert format_packet[1]==32
    # 等待编码器进入稳定输出，再关预览检查采集线程是否消失。
    while read(video).get(1)!=33:pass
    workers=[line.split()[0] for line in shell('ps -A -o PID,ARGS').splitlines() if len(line.split(None,1))==2 and line.split(None,1)[1].startswith('app_process / dev.controlpad.worker.RootMain ')]
    assert len(workers)==1
    def encoder_exists():
        return 'screen-encoder' in shell(f"su -c 'cat /proc/{int(workers[0])}/task/*/comm'")
    assert encoder_exists()
    started=time.perf_counter(); seq+=1; send(s,{1:30,2:seq,8:0}); video.close()
    while encoder_exists() and time.perf_counter()-started<2:time.sleep(.05)
    release=time.perf_counter()-started
    assert release<2 and not encoder_exists(),'关闭两秒后采集线程仍存在'
    s.close()
    result={'transport':'ADB端口转发；不是实机局域网','rttSamples':len(samples),'rttMedianMs':round(statistics.median(samples),2),'rttP95Ms':round(sorted(samples)[28],2),'previewEncoder':format_packet[9].decode(),'previewSize':[int(format_packet[5]),int(format_packet[6])],'encoderThreadReleasedSeconds':round(release,3)}
    (LOG/'performance-emulator.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    (LOG/'receiver-meminfo.txt').write_text(shell('dumpsys meminfo dev.controlpad.receiver'),encoding='utf-8')
    for name in ('preview.png','preview-landscape.png'):
        data=subprocess.run([str(Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'),'-s',CONTROLLER,'exec-out','run-as','dev.controlpad.controller','cat','files/'+name],capture_output=True,check=True).stdout
        (LOG/name).write_bytes(data)
    print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=='__main__':run()
