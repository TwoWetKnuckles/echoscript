"""Local Vosk worker. WAV arrives on stdin; no evidence files or transcript logs."""
import io,json,os,sys,wave,hashlib,pathlib
from vosk import Model,KaldiRecognizer,SetLogLevel
SetLogLevel(-1)
limit=24*1024*1024
data=sys.stdin.buffer.read(limit+1)
if len(data)>limit:raise ValueError('Payload exceeds local worker policy')
with wave.open(io.BytesIO(data),'rb') as w:
    if w.getnchannels()!=1 or w.getsampwidth()!=2 or w.getframerate()!=16000:raise ValueError('Expected mono PCM16 WAV at 16 kHz')
    model_path=pathlib.Path(os.environ['VOSK_MODEL_PATH'])
    inventory=json.loads((pathlib.Path(__file__).resolve().parents[2]/'specs/models/vosk-small-en-us-0.15.json').read_text())
    for item in inventory['files']:
        p=model_path/item['path'];h=hashlib.sha256()
        with p.open('rb') as source:
            for chunk in iter(lambda:source.read(65536),b''):h.update(chunk)
        if p.stat().st_size!=item['bytes'] or h.hexdigest()!=item['sha256']:raise ValueError('Model file integrity mismatch')
    model=Model(str(model_path))
    recognizer=KaldiRecognizer(model,w.getframerate());recognizer.SetWords(True);recognizer.SetMaxAlternatives(5)
    results=[]
    while True:
        chunk=w.readframes(4000)
        if not chunk:break
        if recognizer.AcceptWaveform(chunk):results.append(json.loads(recognizer.Result()))
    results.append(json.loads(recognizer.FinalResult()))
sys.stdout.write(json.dumps({'provider':'vosk-local','apiVersion':'0.3.45','model':{'id':inventory['modelId'],'archiveSha256':inventory['archiveSha256']},'results':results},ensure_ascii=False,separators=(',',':')))
