import argparse, hashlib, json, zipfile
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('baseline', type=Path)
p.add_argument('candidate', type=Path)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
with zipfile.ZipFile(a.baseline) as old, zipfile.ZipFile(a.candidate) as new:
    old_files = {x.filename: old.read(x) for x in old.infolist() if not x.is_dir()}
    new_files = {x.filename: new.read(x) for x in new.infolist() if not x.is_dir()}
allowed = {'fabric.mod.json', 'META-INF/MANIFEST.MF'}
changed = sorted(k for k in old_files.keys() | new_files.keys() if old_files.get(k) != new_files.get(k))
unexpected = [k for k in changed if k not in allowed]
old_meta, new_meta = (json.loads(x['fabric.mod.json']) for x in (old_files, new_files))
old_meta.pop('version'); new_meta.pop('version')
report = dict(passed=not unexpected and old_meta == new_meta, changed=changed, unexpected=unexpected,
    identicalClasses=sum(k.endswith('.class') and new_files.get(k) == v for k, v in old_files.items()),
    identicalAssets=sum(k.startswith('assets/') and new_files.get(k) == v for k, v in old_files.items()),
    baselineSHA256=hashlib.sha256(a.baseline.read_bytes()).hexdigest().upper(),
    candidateSHA256=hashlib.sha256(a.candidate.read_bytes()).hexdigest().upper())
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, indent=2))
if not report['passed']: raise SystemExit('Release candidate changes runtime payload')
