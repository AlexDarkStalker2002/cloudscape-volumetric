from pathlib import Path
import argparse, hashlib, json, zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
if args.output.exists(): raise SystemExit('Preserve existing source archives; choose a new output')
files=[]
for name in ['src','validation','tools','docs','presets','.github']:
    files.extend(p for p in (root/name).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
for name in ['build.ps1','check.ps1','validate-game.ps1','install.ps1','restore.ps1','package.ps1',
             'README.md','README.en.md','CHANGELOG.md','LICENSE','AGENTS.md','dependencies.lock.json','.gitignore','.gitattributes']:
    files.append(root/name)
for p in files:
    if p.suffix.lower() in {'.jar','.class','.zip','.dat','.mca','.dll','.exe'}:
        raise SystemExit('Binary/world dependency found in source allowlist: '+str(p))
args.output.parent.mkdir(parents=True,exist_ok=True)
manifest=[]
with zipfile.ZipFile(args.output,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for p in sorted(set(files)):
        data=p.read_bytes();relative=p.relative_to(root).as_posix()
        info=zipfile.ZipInfo(relative,(2026,10,24,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
        info.external_attr=0o100644<<16;z.writestr(info,data)
        manifest.append(dict(path=relative,sha256=hashlib.sha256(data).hexdigest().upper(),bytes=len(data)))
report=dict(archive=args.output.name,sha256=hashlib.sha256(args.output.read_bytes()).hexdigest().upper(),files=manifest)
args.output.with_suffix('.manifest.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='files'},indent=2))
