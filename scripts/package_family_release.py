#!/usr/bin/env python3
"""SpidiBoost: publish a version-consistent Fabric release and verified updater manifest."""
from pathlib import Path
import hashlib,json,re,zipfile
root=Path(__file__).resolve().parent.parent
spec=json.loads((root/'release.json').read_text())
version=re.search(r'^mod_version=(\d+\.\d+\.\d+)$',(root/'gradle.properties').read_text(),re.M).group(1)
jar=root/'build/libs'/f'{spec["name"]}-1.21.4-{version}.jar'
with zipfile.ZipFile(jar) as z:
 d=json.loads(z.read('fabric.mod.json'))
 assert d['id']==spec['id'] and d['version']==version
 assert d['name']=='spidiboost.'+spec['name'] and d['authors']==['SpidiBoost']
 assert spec['id']+'-shared-update-agent.jar' in z.namelist()
 assert d['icon'] in z.namelist()
 assert not any('verification/' in n or 'runtimeProbe/' in n for n in z.namelist())
 assert not any(x in json.dumps(d).lower() for x in ('chatgpt','codex','codec'))
 for n in z.namelist():
  if n.endswith('.class'):assert int.from_bytes(z.read(n)[6:8],'big')==65
 if spec['id']=='spidiban':
  assert 'net/spidiboost/shist/ShistEngine.class' in z.namelist()
  assert 'spidiban-shist.mixins.json' in z.namelist()
sha=hashlib.sha256(jar.read_bytes()).hexdigest()
(root/'build/libs'/spec['manifest']).write_text(f'version={version}\nminecraft=1.21.4\nartifact={jar.name}\nsha256={sha}\n',encoding='utf-8')
source=root/'build/libs'/f'{spec["name"]}-1.21.4-{version}-source-project.zip'
with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED) as z:
 for name in ('src','scripts','.github','gradle','verification','upstream','build.gradle','shared-updater.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','README.md','AGENTS.md','.gitignore','docs','release.json'):
  p=root/name
  files=sorted(p.rglob('*')) if p.is_dir() else [p]
  for f in files:
   if f.is_file() and '__pycache__' not in f.parts and f.suffix not in ('.log','.pyc') and f.name!='.DS_Store':z.write(f,f'{spec["name"]}-{version}/{f.relative_to(root).as_posix()}')
print(jar.name,sha)
