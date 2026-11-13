"""Reproduce the bundled static CPU graph from the pinned ClearVoice checkpoint.
Run in the requirements-clearvoice-export.txt environment after setup-clearvoice.py.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import numpy as np
import torch
import onnxruntime as ort
from clearvoice import ClearVoice

parser = argparse.ArgumentParser()
parser.add_argument('model_home', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
output = args.output.resolve()
home = args.model_home.resolve()
inventory = json.loads((home/'inventory.json').read_text())
if inventory['revision'] != '3766e6a64b0d8cb58f08d913d617bf129f11ed53':
    raise ValueError('unreviewed model revision')
for entry in inventory['files']:
    path = home/'checkpoints'/'FRCRN_SE_16K'/entry['path']
    if Path(entry['path']).name != entry['path']:
        raise ValueError('invalid checkpoint path')
    with path.open('rb') as stream:
        if hashlib.file_digest(stream,'sha256').hexdigest() != entry['sha256']:
            raise ValueError('checkpoint hash mismatch')
os.environ.update(HF_HUB_OFFLINE='1', CUDA_VISIBLE_DEVICES='')
os.chdir(home)
torch.set_num_threads(1)
torch.manual_seed(0)
network = ClearVoice(task='speech_enhancement', model_names=['FRCRN_SE_16K']).models[0].model
marker = (home/'checkpoints/FRCRN_SE_16K/last_best_checkpoint').read_text().strip()
if Path(marker).name != marker:
    raise ValueError('invalid checkpoint marker')
checkpoint = torch.load(home/'checkpoints/FRCRN_SE_16K'/marker, map_location='cpu', weights_only=True)
weights = checkpoint.get('model', checkpoint)
strict = {}
for key in network.state_dict():
    found = next((name for name in (key, key.removeprefix('module.'), 'module.'+key) if name in weights), None)
    if found is None:
        raise ValueError('checkpoint does not populate every inference tensor')
    strict[key] = weights[found]
network.load_state_dict(strict, strict=True)

class Export(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model
    def forward(self, audio):
        return self.model.inference_batch(audio)

model = Export(network).eval()
output.parent.mkdir(parents=True, exist_ok=True)
with torch.inference_mode():
    sample = torch.randn(1,32000)*0.01
    torch.onnx.export(model,(sample,),str(output),input_names=['audio'],output_names=['enhanced'],opset_version=17,dynamo=False,external_data=False)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(output),options,providers=['CPUExecutionProvider'])
    actual = session.run(None,{'audio':sample.numpy()})[0]
    expected = model(sample).numpy()
    error = float(np.max(np.abs(actual-expected)))
    if actual.shape != (1,32000) or not np.isfinite(actual).all() or error > 1e-5:
        raise ValueError('export failed numerical parity')
with output.open('rb') as stream:
    digest = hashlib.file_digest(stream,'sha256').hexdigest()
print(json.dumps({'onnxSha256':digest,'maxAbsoluteError':error,'inputShape':[1,32000],'bytes':output.stat().st_size}))
