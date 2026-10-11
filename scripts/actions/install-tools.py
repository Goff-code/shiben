#!/usr/bin/env python3
"""Install the previously approved, fixed vendor archives into a disposable runner.

No sdkmanager latest resolution, license prompt acceptance, package manager,
third-party installer script, shared cache, signing key, or system setting.
"""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import sys
import tarfile
import urllib.parse
import urllib.request
import xml.dom.minidom as DOM
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SDK_LICENSE = 'efa68a6b3c661d18699d5c026771d5911cdc2f83'
METADATA = {'repository2-1.xml': '2fe5ec9185d840be617b2d336f12f6683c4c365e213599a8be28a2d7fb026afe',
            'sys-img2-1.xml': 'ebf2d810d9e0c0b511ae49ee6e8c671a8fa67d210d06f5e3e068f83244ece435'}


def digest(path, algorithm):
    with path.open('rb') as source:
        return hashlib.file_digest(source, algorithm).hexdigest()


def vendor_metadata(name, item):
    filename = 'sys-img2-1.xml' if name.startswith('system-images;') else 'repository2-1.xml'
    data = (ROOT / 'tests/actions/vendor' / filename).read_bytes()
    assert hashlib.sha256(data).hexdigest() == METADATA[filename]
    source = DOM.parseString(data)
    archive_name = urllib.parse.urlparse(item['url']).path.rsplit('/', 1)[-1]
    matches = [p for p in source.getElementsByTagName('remotePackage')
               if p.getAttribute('path') == name
               and any(u.firstChild.nodeValue == archive_name for u in p.getElementsByTagName('url'))]
    assert len(matches) == 1, (name, 'vendor archive metadata differs')
    package = matches[0]
    selected = [a for a in package.getElementsByTagName('complete')
                if a.getElementsByTagName('url')[0].firstChild.nodeValue == archive_name][0]
    assert selected.getElementsByTagName('checksum')[0].firstChild.nodeValue == item['sha1']
    assert int(selected.getElementsByTagName('size')[0].firstChild.nodeValue) == item['size']
    license_id = package.getElementsByTagName('uses-license')[0].getAttribute('ref')
    assert license_id == 'android-sdk-license'
    license_node = next(n for n in source.getElementsByTagName('license') if n.getAttribute('id') == license_id)
    license_text = ''.join(n.nodeValue for n in license_node.childNodes if n.nodeType in (n.TEXT_NODE, n.CDATA_SECTION_NODE))
    assert hashlib.sha1(license_text.encode()).hexdigest() == SDK_LICENSE, 'Unapproved SDK license: stop'
    return source, package, license_node


def write_local_package(destination, name, metadata):
    source, package, license_node = metadata
    doc = DOM.Document()
    repository = doc.createElement('ns2:repository')
    doc.appendChild(repository)
    for attr in source.documentElement.attributes.values():
        if attr.name.startswith('xmlns:'):
            repository.setAttribute(attr.name, attr.value)
    # Keep vendor schema versions consistent. Mixing common/02 with the
    # archived platform type in repository2/01 makes AGP reject the package.
    repository.setAttribute('xmlns:ns2', 'http://schemas.android.com/repository/android/common/01')
    local = doc.createElement('localPackage')
    local.setAttribute('path', name)
    local.setAttribute('obsolete', 'false')
    for node in package.childNodes:
        if node.nodeType == node.ELEMENT_NODE and node.tagName not in ('archives', 'channelRef'):
            local.appendChild(doc.importNode(node, True))
    repository.appendChild(doc.importNode(license_node, True))
    repository.appendChild(local)
    (destination / 'package.xml').write_text(doc.toxml())


def fetch(item, destination):
    url = item['url']
    assert urllib.parse.urlparse(url).hostname in ('dl.google.com', 'github.com', 'services.gradle.org')
    request = urllib.request.Request(url, headers={'User-Agent': 'Shiben-official-toolchain-verification'})
    with urllib.request.urlopen(request, timeout=60) as response, destination.open('xb') as output:
        shutil.copyfileobj(response, output, 1024 * 1024)
    if 'size' in item:
        assert destination.stat().st_size == item['size'], (url, 'size mismatch')
    for algorithm in ('sha1', 'sha256'):
        if algorithm in item:
            assert digest(destination, algorithm) == item[algorithm], (url, algorithm, 'mismatch')
    return {'url': url, 'size': destination.stat().st_size, 'sha256': digest(destination, 'sha256')}


def extract_single_root(archive, destination, staging):
    staging.mkdir()
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as z:
            for member in z.infolist():
                path = PurePosixPath(member.filename)
                assert not path.is_absolute() and '..' not in path.parts and '\\' not in member.filename
                assert not stat.S_ISLNK(member.external_attr >> 16), 'Unexpected ZIP link'
                output = Path(z.extract(member, staging))
                mode = (member.external_attr >> 16) & 0o777
                if mode:
                    output.chmod(mode)
    else:
        with tarfile.open(archive) as t:
            t.extractall(staging, filter='data')
    roots = list(staging.iterdir())
    assert len(roots) == 1 and roots[0].is_dir(), ('Unexpected archive root', roots)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(roots[0]), destination)
    staging.rmdir()


def main():
    if len(sys.argv) != 3:
        raise SystemExit('Usage: install-tools.py EMPTY_OUTPUT_DIRECTORY EVIDENCE_DIRECTORY')
    tools, evidence = map(lambda s: Path(s).resolve(), sys.argv[1:])
    tools.mkdir(exist_ok=False)
    evidence.mkdir(parents=True, exist_ok=True)
    lock = json.loads((ROOT / 'tests/actions/toolchain-lock.json').read_text())
    receipt = {}
    for name, item in lock.items():
        print('Verify/install', name, flush=True)
        android = name not in ('temurin-jdk21', 'gradle8.11.1')
        metadata = vendor_metadata(name, item) if android else None
        destination = tools / 'sdk' / name.replace(';', '/') if android else tools / ('jdk' if name == 'temurin-jdk21' else 'gradle')
        archive = tools / ('download-' + name.replace(';', '_'))
        receipt[name] = fetch(item, archive)
        extract_single_root(archive, destination, tools / 'extract')
        if android:
            write_local_package(destination, name, metadata)
        archive.unlink()  # Only this newly downloaded temporary vendor archive.
        (evidence / 'toolchain-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    licenses = tools / 'sdk/licenses'
    licenses.mkdir()
    (licenses / 'android-sdk-license').write_text(SDK_LICENSE + '\n')


if __name__ == '__main__':
    main()
