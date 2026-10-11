#!/usr/bin/env python3
"""Compare public package identity and actual AOSP35 channel controls, no private data."""
import argparse,json,re
from pathlib import Path
p=argparse.ArgumentParser()
for key in ['before-package','after-package','before-channels','after-channels','report']:
    p.add_argument('--'+key, required=True, type=Path)
a=p.parse_args()
def one(pattern,text,label):
    matches=re.findall(pattern,text,re.M)
    if len(matches)!=1: raise AssertionError(f'{label}: expected one diagnostic match, found {len(matches)}')
    return matches[0]
def package(path,code,name):
    text=path.read_text();assert 'Package [cn.goff.shiben]' in text
    actual=int(one(r'^\s*versionCode=(\d+)\s',text,'versionCode'));assert actual==code
    assert one(r'^\s*versionName=([^\r\n]+)',text,'versionName')==name
    flags=one(r'^ *pkgFlags=\[([^\r\n]*)\] *$',text,'pkgFlags');assert 'DEBUGGABLE' not in flags
    return {'uid':int(one(r'^\s*appId=(\d+)\s*$',text,'appId')),
            'firstInstallTime':one(r'^\s*firstInstallTime=([^\r\n]+)',text,'firstInstallTime')}
def channels(path,uid):
    text=path.read_text();lines=text.splitlines()
    heads=[i for i,line in enumerate(lines) if re.fullmatch(r'\s*AppSettings: cn\.goff\.shiben \('+str(uid)+r'\)(?: .*)?',line)]
    assert len(heads)==1, ('Expected unique current UID AppSettings',heads)
    start=heads[0];head=lines[start].strip();block=[]
    for line in lines[start+1:]:
        if re.match(r'^\s*AppSettings:',line): break
        if 'NotificationChannel{' in line: block.append(line.strip())
    assert block, 'No actual existing channel inventory'
    required={'mId','mImportance','mBypassDnd','mLockscreenVisibility','mSound','mLights','mLightColor','mVibrationPattern','mVibrationEffect','mUserLockedFields','mVibrationEnabled','mShowBadge','mDeleted','mDeletedTimeMs','mGroup','mAudioAttributes','mBlockableSystem','mAllowBubbles','mImportanceLockedDefaultApp','mOriginalImp','mParent','mConversationId','mDemoted','mImportantConvo'}
    result={}
    for line in block:
        assert line.startswith('NotificationChannel{') and line.endswith('}')
        fields={}
        for value in re.split(r', (?=m[A-Z][A-Za-z]+=)',line[len('NotificationChannel{'):-1]):
            key,sep,data=value.partition('=');assert sep and key not in fields;fields[key]=data
        assert required <= fields.keys(), required-fields.keys()
        channel=fields['mId'];assert channel not in result
        controls={key:fields[key] for key in sorted(required)}
        controls['mUserLockedFields']=int(controls['mUserLockedFields'],16)
        result[channel]=controls
    return {'app_settings':head,'channels':result}
before=package(a.before_package,5,'2.0.2');after=package(a.after_package,7,'3.0');assert before==after,('Package identity changed',before,after)
left=channels(a.before_channels,before['uid']);right=channels(a.after_channels,after['uid']);assert left==right,('Existing channel controls changed',left,right)
report={'status':'passed','scope':'formal public package identity and existing channel inventory only; no four-mode route or private SQL assertion','package_identity':before,'existing_channels':left}
a.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print('PASS same formal package UID/first-install time and exact existing channel controls:',len(left['channels']),'channels')
