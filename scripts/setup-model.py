"""Download the selected local speech model; never upload evidence or credentials."""
import hashlib,json,pathlib,urllib.request,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
models=root/'models';models.mkdir(exist_ok=True)
url='https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip'
expected='30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498'
archive=models/'vosk-model-small-en-us-0.15.zip'
if not archive.exists():
    with urllib.request.urlopen(url,timeout=60) as response,archive.open('wb') as output:
        while True:
            chunk=response.read(65536)
            if not chunk:break
            output.write(chunk)
h=hashlib.sha256()
with archive.open('rb') as stream:
    for chunk in iter(lambda:stream.read(65536),b''):h.update(chunk)
if h.hexdigest()!=expected:raise ValueError('Model SHA-256 mismatch; refusing extraction')
with zipfile.ZipFile(archive) as z:
    total=0
    for e in z.infolist():
        path=pathlib.PurePosixPath(e.filename);total+=e.file_size
        if path.is_absolute() or '..' in path.parts or '\\' in e.filename or not e.filename.startswith('vosk-model-small-en-us-0.15/') or total>512*1024*1024:raise ValueError('Unsafe model archive')
    z.extractall(models)
print('Verified local Vosk model: '+expected)
