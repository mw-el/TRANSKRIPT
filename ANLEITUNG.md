# Sprachausgabe mit DeepInfra Chatterbox Multilingual

Praxisanleitung für die Integration in eine VoiceChat-App (Spracheingabe + Sprachausgabe).

---

## Modell & Endpoint

| Einstellung | Wert |
| --- | --- |
| **Modell** | `ResembleAI/chatterbox-multilingual` |
| **Endpoint** | `https://api.deepinfra.com/v1/inference/ResembleAI/chatterbox-multilingual` |
| **Preis** | $1.00 / 1M Zeichen |
| **API-Key-Datei** | `.TTS-MW-Deepinfra-api-key.txt` (liegt im Repo-Root, nicht einchecken) |

---

## Stimme einrichten (einmalig)

Voice-IDs werden dauerhaft auf DeepInfra gespeichert und müssen nur einmal hochgeladen werden.

**Bewährte Voice ID für Matthias Wiemeyer:** `jqy7yrjgagtomro39ddy`  
(erstellt aus `matthias-ohne-pausen-cleaned-20260418181821.wav` — 27s, Mono, klar deutsch, ohne Pausen)

### Neue Stimme hochladen

```bash
curl -X POST "https://api.deepinfra.com/v1/voices/add" \
  -H "Authorization: Bearer <API_KEY>" \
  -F "audio=@stimme.wav" \
  -F "name=Matthias Wiemeyer" \
  -F "description=Deutsche Stimme"
```

Das Skript `tts_script.py` übernimmt Upload und ffmpeg-Konvertierung automatisch:

```bash
python tts_script.py --voice-file meine-stimme.wav
```

**Anforderungen ans Referenz-Audio:**

- Format: WAV, Mono, 24 kHz, 16-bit (s16) — Konvertierung übernimmt das Skript per ffmpeg
- Länge: 10–30 Sekunden
- Klar deutschsprachig, ohne lange Pausen oder Artefakte — Pausen veranlassen das Modell, die Stimmcharakteristik unvollständig zu lernen
- Keine Hintergrundgeräusche

### Vorhandene Stimmen auflisten

```bash
curl -X GET "https://api.deepinfra.com/v1/voices" \
  -H "Authorization: Bearer <API_KEY>"
```

---

## TTS-Aufruf

### Wichtige Feldnamen (häufige Fehlerquelle)

| Richtig | Falsch | Folge des Fehlers |
| --- | --- | --- |
| `"voice_id"` | `"voice"` | Stimme wird ignoriert, englische Standardstimme |
| `"language"` | `"language_id"` | Sprache wird ignoriert, Audio bricht nach erstem Wort ab |

`language_id` ist der Feldname in chatterbox-turbo (nicht Multilingual); `voice` ohne `_id` wird von der API stillschweigend ignoriert — beides ohne Fehlermeldung.

### Empfohlene Parameter

```python
import requests
import base64

API_KEY_FILE = ".TTS-MW-Deepinfra-api-key.txt"
TTS_URL = "https://api.deepinfra.com/v1/inference/ResembleAI/chatterbox-multilingual"

def text_to_speech(text: str, voice_id: str) -> bytes:
    api_key = open(API_KEY_FILE).read().strip()
    payload = {
        "text": text,
        "voice_id": voice_id,
        "language": "de",
        "response_format": "wav",
        "service_tier": "priority",
        "exaggeration": 0.4,   # 0.3–0.5: reduziert Fade-out bei Custom Voices
        "cfg": 0.3,            # 0.2–0.4: stabilisiert Generierung
        "temperature": 0.7,    # weniger Zufall, vollständigere Ausgabe
        "top_p": 0.95,
        "min_p": 0.0,
        "repetition_penalty": 1.2,
        "top_k": 1000,
    }
    response = requests.post(
        TTS_URL,
        json=payload,
        headers={"Authorization": f"Bearer {api_key}"},
        timeout=180,
    )
    response.raise_for_status()
    audio_field = response.json()["audio"]
    _, encoded = audio_field.split(",", 1)
    return base64.b64decode(encoded)
```

### Nicht unterstützte Parameter

| Parameter | Problem |
| --- | --- |
| `speed` | Führt zu truncated Audio (wenige Sekunden statt vollem Text) |
| `seed` | Verschlechtert die Ausgabe bei deutschem Text |

---

## Nicht-deterministisches Abbrechen (Cut-off-Problem)

Das Modell gibt für denselben Text manchmal vollständige Audiodaten zurück, manchmal bricht es nach dem ersten Wort ab — obwohl die API `status: succeeded` meldet und alle Zeichen verrechnet.

**Ursache:** Bekanntes Problem speziell bei Custom-Voice-Klonen (Community-Berichte, GitHub Issue #311). Die Tokenizer-Logik interpretiert Satzzeichen bei geklonten Stimmen teils als End-of-Sequence. Built-in-Stimmen sind davon weniger betroffen. Eine serverseitige Lösung von DeepInfra/ResembleAI existiert bisher nicht.

**Primäre Abhilfe — Text in kurze Chunks aufteilen:**

Sätze einzeln synthetisieren (max. ~150 Zeichen pro Aufruf) und anschliessend zusammenfügen. Das ist die zuverlässigste Massnahme und passt gut zur Streaming-Architektur einer VoiceChat-App.

**Sekundäre Absicherung — Retry bei zu kurzem Audio:**

```python
import wave
import io

def tts_with_retry(
    text: str,
    voice_id: str,
    max_attempts: int = 3,
) -> bytes:
    min_duration_s = max(1.0, len(text) * 0.04)  # ~40ms pro Zeichen als Untergrenze
    for attempt in range(1, max_attempts + 1):
        audio_bytes = text_to_speech(text, voice_id)
        with wave.open(io.BytesIO(audio_bytes)) as wf:
            duration = wf.getnframes() / wf.getframerate()
        if duration >= min_duration_s:
            return audio_bytes
        print(f"Versuch {attempt}: Audio zu kurz ({duration:.2f}s), wiederhole...")
    raise RuntimeError(f"TTS nach {max_attempts} Versuchen zu kurz")
```

---

## Bekannte Einschränkungen

**Leichter englischer Akzent:** Chatterbox Multilingual ist primär auf Englisch trainiert. Deutsche Phonologie wird nicht vollständig übernommen — besonders "St" im Anlaut klingt wie englisches [st] statt deutschem [ʃt]. Dieses Verhalten ist modellseitig bedingt und lässt sich durch Parameter oder Referenz-Audio nicht vollständig beheben. Community-Workarounds (phonetische Umschreibung im Text) haben sich als unzuverlässig erwiesen.

---

## Architektur einer VoiceChat-App

```text
Mikrofon → STT (z.B. Whisper) → LLM (z.B. Claude) → TTS (Chatterbox) → Lautsprecher
```

### Empfehlungen für flüssige Interaktion

**Kurze Textsegmente** — LLM-Antworten vor der Übergabe an TTS satzweise aufteilen (max. ~150 Zeichen). Löst gleichzeitig das Cut-off-Problem und ermöglicht Streaming.

**Streaming-Ansatz** — Den ersten Satz sofort synthetisieren, während das LLM noch generiert. Nutzer nehmen eine kurze Verzögerung am Anfang als natürlich wahr, Stille in der Mitte jedoch nicht.

**Retry transparent halten** — Eine Wiederholung dauert 2–4 Sekunden. Wenn parallel bereits der erste Satz abgespielt wird, fällt das nicht auf.

**WAV direkt abspielen** — Kein Umweg über MP3, spart Konvertierungszeit.
