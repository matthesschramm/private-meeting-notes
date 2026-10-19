// Runs the reference Nemotron 3 Diarization pipeline (diar.js, fetched by setup-tooling.sh) on 16 kHz mono PCM16
// WAV files and writes each file's speaker probabilities as <name>.reference.f32 (frames x 8 float32) next to it.
//   node .tooling/parity/reference.mjs [--fp32] test-data/synthetic/<set>/meeting.wav ...
// --fp32 uses the full-precision step.onnx and writes <name>.reference-fp32.f32.
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { Diarizer } from './diar.js';

// ONNX Runtime otherwise sends usage telemetry to Microsoft. Set before loading it, hence the dynamic import.
process.env.ORT_DISABLE_TELEMETRY = '1';
const { default: ort } = await import('onnxruntime-node');

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const models = path.join(root, 'models', 'nemotron-3-diarization');
const floats = file => {
  const b = fs.readFileSync(file);
  return new Float32Array(b.buffer.slice(b.byteOffset, b.byteOffset + b.length));
};

function readWav(file) {
  const b = fs.readFileSync(file);
  if (b.toString('ascii', 0, 4) !== 'RIFF' || b.toString('ascii', 8, 12) !== 'WAVE') throw new Error(`${file}: not a WAV`);
  let pos = 12, fmt = null;
  while (pos + 8 <= b.length) {
    const id = b.toString('ascii', pos, pos + 4), size = b.readUInt32LE(pos + 4), body = pos + 8;
    if (id === 'fmt ') fmt = { enc: b.readUInt16LE(body), ch: b.readUInt16LE(body + 2), sr: b.readUInt32LE(body + 4), bits: b.readUInt16LE(body + 14) };
    if (id === 'data') {
      if (!fmt || fmt.enc !== 1 || fmt.ch !== 1 || fmt.sr !== 16000 || fmt.bits !== 16) throw new Error(`${file}: need 16 kHz mono PCM16`);
      const n = Math.min(size, b.length - body) >> 1, out = new Float32Array(n);
      for (let i = 0; i < n; i++) out[i] = b.readInt16LE(body + 2 * i) / 32768;
      return out;
    }
    pos = body + size + (size & 1);
  }
  throw new Error(`${file}: no data chunk`);
}

const fp32 = process.argv.includes('--fp32');
const options = { intraOpNumThreads: 4, interOpNumThreads: 1 };
const embed = await ort.InferenceSession.create(path.join(models, 'embed.onnx'), options);
const step = await ort.InferenceSession.create(path.join(models, fp32 ? 'step.onnx' : 'step_int8.onnx'), options);
const diarizer = new Diarizer(ort, embed, step, floats(path.join(models, 'mel_filters.bin')), floats(path.join(models, 'silence_embeds.bin')));

for (const file of process.argv.slice(2).filter(a => !a.startsWith('--'))) {
  const audio = readWav(file);
  const t0 = Date.now();
  const { probs, numFrames } = await diarizer.run(audio);
  const out = file.replace(/\.wav$/, fp32 ? '.reference-fp32.f32' : '.reference.f32');
  fs.writeFileSync(out, Buffer.from(probs.buffer, probs.byteOffset, numFrames * 8 * 4));
  console.log(`${path.basename(file)}: ${numFrames} frames in ${Date.now() - t0} ms -> ${path.basename(out)}`);
}
