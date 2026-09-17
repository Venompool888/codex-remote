#!/usr/bin/env python3
"""Validate the merged release manifest, after variant/library manifests are applied."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parent.parent
manifest = repo / 'android/app/build/intermediates/packaged_manifests/release/processReleaseManifestForPackage/AndroidManifest.xml'
if not manifest.is_file():
    sys.exit('Packaged release manifest missing; assembleRelease must run first')
root = ET.parse(manifest).getroot()
app = root.find('application')
assert app is not None, 'Release application missing'
a = '{http://schemas.android.com/apk/res/android}'
checks = {
    'release package': root.get('package') == 'app.codexremote.android',
    # HTTP is an explicit user-selected connection protocol; HTTPS remains the default.
    'explicit HTTP connections supported': app.get(a + 'usesCleartextTraffic') == 'true',
    'backup disabled': app.get(a + 'allowBackup') == 'false',
    'debug disabled': app.get(a + 'debuggable', 'false') == 'false',
    'no test instrumentation': root.find('instrumentation') is None,
}
provider = next((p for p in app.findall('provider') if p.get(a + 'name') == 'androidx.core.content.FileProvider'), None)
checks['artifact provider private'] = provider is not None and provider.get(a + 'exported') == 'false'
checks['artifact authority scoped'] = provider is not None and provider.get(a + 'authorities') == 'app.codexremote.android.artifacts'
services = app.findall('service')
for name in ('app.codexremote.android.AttachmentUploadJob', 'app.codexremote.android.RemoteMonitorService'):
    service = next((s for s in services if s.get(a + 'name') == name), None)
    checks[name.rsplit('.', 1)[-1] + ' private'] = service is not None and service.get(a + 'exported') == 'false'
for name, passed in checks.items():
    print(('PASS ' if passed else 'FAIL ') + name)
if not all(checks.values()):
    sys.exit(1)
