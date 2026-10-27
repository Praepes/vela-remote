"""在已配对的双模拟器上验证会话隔离、断连释放与显示版本。"""
from pathlib import Path
import hashlib,json,socket,ssl,struct,time,weakref,xml.etree.ElementTree as ET
from emulator_test import adb,shell,LOG,RECEIVER,CONTROLLER

def vint(v):
    result=bytearray()
    while v>=128: result.append((v&127)|128);v>>=7
    result.append(v);return result

def encode(p):
    b=bytearray()
    for k,v in p.items():
        if k in (5,6): b+=vint(k*8+5)+struct.pack('<f',v)
        elif k in (9,10):
            v=v.encode() if isinstance(v,str) else v;b+=vint(k*8+2)+vint(len(v))+v
        else:b+=vint(k*8)+vint(v)
    return bytes(b)

def decode(b):
    pos=0;r={}
    def var():
        nonlocal pos
        v=0;shift=0
        while True:
            n=b[pos];pos+=1;v|=(n&127)<<shift
            if not n&128:return v
            shift+=7
    while pos<len(b):
        tag=var();k=tag>>3;t=tag&7
        if t==0:r[k]=var()
        elif t==5:r[k]=struct.unpack_from('<f',b,pos)[0];pos+=4
        elif t==2:n=var();r[k]=b[pos:pos+n];pos+=n
        else:raise ValueError('unexpected protobuf field')
    return r

def exact(s,n):
    b=b''
    while len(b)<n:
        d=s.recv(n-len(b))
        if not d:raise EOFError()
        b+=d
    return b
def read(s):return decode(exact(s,struct.unpack('>I',exact(s,4))[0]))
sequence_offsets=weakref.WeakKeyDictionary()

def send(s,p):
    if 2 in p and s in sequence_offsets: p={**p,2:p[2]+sequence_offsets[s]}
    b=encode(p);s.sendall(struct.pack('>I',len(b))+b)

def credentials():
    root=ET.fromstring(adb(CONTROLLER,'shell','run-as','dev.controlpad.controller','cat','shared_prefs/devices.xml'))
    values={n.get('name'):n.text for n in root};d=json.loads(values['saved'])[0]
    return values['controller_id'],d

def connect(d,port=27183):
    ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT);ctx.check_hostname=False;ctx.verify_mode=ssl.CERT_NONE;ctx.minimum_version=ssl.TLSVersion.TLSv1_2
    s=ctx.wrap_socket(socket.create_connection(('127.0.0.1',port),5),server_hostname='ControlPad');s.settimeout(6)
    assert hashlib.sha256(s.getpeercert(binary_form=True)).hexdigest()==d['pin'],'certificate changed'
    return s

def auth(identity,d):
    s=connect(d);assert read(s)[1]==1
    send(s,{1:2,7:1,9:identity,10:d['token']})
    assert read(s)[1]==3
    p=read(s);assert p[1]==7
    display=int(p[4])
    assert read(s)[1]==43, '0.3.0 capability handshake missing'
    # 生命周期基线验证触摸通道，不让 Root 默认系统鼠标改变坐标断言。
    send(s,{1:22,2:1,4:display,7:1,8:1})
    while read(s).get(1)!=43: pass
    sequence_offsets[s]=1
    return s,display

def run():
    identity,d=credentials();results={}
    shell("su -c 'am start -n dev.controlpad.receiver/.InputLabActivity'")
    time.sleep(1)
    # 视频连接不能使用错误的控制会话令牌。
    v=connect(d,27184);send(v,{1:31,9:'invalid-session'})
    try:read(v);raise AssertionError('invalid video session was accepted')
    except EOFError:results['video_session_rejected']=True
    finally:v.close()
    s,display=auth(identity,d)
    send(s,{1:21,2:1})
    while read(s).get(1)!=8:pass
    send(s,{1:10,2:2,4:display-1,5:200.0,6:0.0})
    send(s,{1:10,2:3,4:display,5:0.0,6:0.0})
    p=read(s)
    while p.get(1)!=8:p=read(s)
    assert abs(p[5]-640)<1,'stale display input executed';results['stale_display_rejected']=True
    send(s,{1:12,2:4,3:888,4:display})
    time.sleep(.2)
    send(s,{1:13,2:4,3:888,4:display,5:20.0,6:0.0})
    try:
        while read(s).get(1)!=4:pass
    except EOFError:pass
    s.close();time.sleep(.5)
    events=json.loads(shell('run-as dev.controlpad.receiver cat files/lab-events.json'))
    assert events['cancels']>=1,'out-of-order session did not cancel';results['duplicate_sequence_cancels']=True
    previous=events['cancels']
    s,display=auth(identity,d);send(s,{1:12,2:1,3:889,4:display})
    time.sleep(4)
    try:
        while read(s).get(1)!=4:pass
    except (EOFError,ConnectionResetError):pass
    finally:s.close()
    events=json.loads(shell('run-as dev.controlpad.receiver cat files/lab-events.json'))
    assert events['cancels']>previous,'heartbeat timeout did not release touch';results['heartbeat_timeout_releases']=True
    # 光标位于最下沿时，仍须结束边缘段并从屏内续滚，不能消耗位移却不移动。
    previous_down=events['actions'].count(0)
    s,display=auth(identity,d)
    send(s,{1:21,2:1})
    send(s,{1:10,2:2,4:display,5:-540.0,6:1000.0})
    send(s,{1:16,2:3,3:890,4:display})
    send(s,{1:17,2:4,3:890,4:display,6:1000.0})
    send(s,{1:14,2:5,3:890,4:display})
    time.sleep(.5); s.close()
    events=json.loads(shell('run-as dev.controlpad.receiver cat files/lab-events.json'))
    assert events['actions'].count(0)>previous_down+1,'edge scroll did not start replacement touch segments'
    results['edge_scroll_continues']=True
    (LOG/'lifecycle.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    print(json.dumps(results,indent=2))

if __name__=='__main__':run()
