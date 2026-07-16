# ASR Evaluator

Python service for jiwer-based ASR scoring. Audio decoding happens in the browser before a Run is created.

`Loose` scoring uses normalizer `zh-v2`: punctuation and English case are ignored, and equivalent Chinese/Arabic number forms are normalized before CER/WER calculation. `Strict` scoring preserves the original number format.

## Local Run

```bash
python3 -m venv .venv
.venv/bin/python3 -m pip install -r requirements.txt
.venv/bin/python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8090
```

## Docker

```bash
docker build -t asr-evaluator .
docker run --rm -p 8090:8090 asr-evaluator
```

## Test

```bash
.venv/bin/python3 -m pytest
```
