"""Install only the five official QA archives after parent confirms scope/license."""
import pathlib,json,hashlib,zipfile,shutil,subprocess,os,xml.dom.minidom as D
root=pathlib.Path(__file__).resolve().parents[1]; downloads=root/'tools/downloads'; sdk=root/'tools/android-sdk'
pkgs=json.loads((downloads/'qa-packages.json').read_text())
locations={
 'build-tools;35.0.0':('android-15',sdk/'build-tools/35.0.0'),
 'platform-tools':('platform-tools',sdk/'platform-tools'),
 'cmdline-tools;latest':('cmdline-tools',sdk/'cmdline-tools/latest'),
 'emulator':('emulator',sdk/'emulator'),
 'system-images;android-35;default;x86_64':('x86_64',sdk/'system-images/android-35/default/x86_64'),
}
for name in ['platform-tools','build-tools;35.0.0','cmdline-tools;latest','emulator','system-images;android-35;default;x86_64']:
 p=pkgs[name]; archive=downloads/p['url'].rsplit('/',1)[1]
 assert archive.stat().st_size==p['size']
 h=hashlib.sha1()
 with archive.open('rb') as f:
  while chunk:=f.read(4*1024*1024):h.update(chunk)
 assert h.hexdigest()==p['sha1']
 top,dest=locations[name]; staging=downloads/('extract-'+top)
 assert not staging.exists() and not dest.exists(),(staging,dest)
 staging.mkdir();subprocess.run(['unzip','-q',str(archive),'-d',str(staging)],check=True)
 dest.parent.mkdir(parents=True,exist_ok=True);shutil.move(str(staging/top),str(dest));staging.rmdir()
 # Preserve exact vendor package/license content; localPackage excludes remote archive/channel data.
 src=D.parse(str(downloads/('sys-img2-1.xml' if name.startswith('system-images;') else 'repository2-1.xml')))
 pkg=next(p for p in src.getElementsByTagName('remotePackage') if p.getAttribute('path')==name and (not p.getElementsByTagName('channelRef') or p.getElementsByTagName('channelRef')[0].getAttribute('ref')=='channel-0'))
 doc=D.Document(); repo=doc.createElement('ns2:repository');doc.appendChild(repo)
 for attr in src.documentElement.attributes.values():
  if attr.name.startswith('xmlns:'):repo.setAttribute(attr.name,attr.value)
 repo.setAttribute('xmlns:ns2','http://schemas.android.com/repository/android/common/02')
 if repo.hasAttribute('xmlns:generic'):repo.setAttribute('xmlns:generic','http://schemas.android.com/repository/android/generic/02')
 if repo.hasAttribute('xmlns:sys-img'):repo.setAttribute('xmlns:sys-img','http://schemas.android.com/sdk/android/repo/sys-img2/05')
 local=doc.createElement('localPackage');local.setAttribute('path',name);local.setAttribute('obsolete','false')
 for node in pkg.childNodes:
  if node.nodeType==node.ELEMENT_NODE and node.tagName not in ['archives','channelRef']:local.appendChild(doc.importNode(node,True))
 if name.startswith('system-images;'):
  td=local.getElementsByTagName('type-details')[0];a=doc.createElement('abis');a.appendChild(doc.createTextNode('x86_64'));td.appendChild(a)
 licid=p['license']; lic=next(l for l in src.getElementsByTagName('license') if l.getAttribute('id')==licid)
 repo.appendChild(doc.importNode(lic,True));repo.appendChild(local)
 (dest/'package.xml').write_text(doc.toxml())
 print('INSTALLED',name,str(dest),flush=True)
link=root/'tools/jdk-21.0.2'
if not link.exists():link.symlink_to('/usr/lib/jvm/java-21-openjdk-amd64',target_is_directory=True)
print('SYSTEM JDK LINK READY',flush=True)
