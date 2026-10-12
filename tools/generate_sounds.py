#!/usr/bin/env python3
"""Original short game cues, generated locally; no downloaded audio or runtime synthesis."""
from pathlib import Path
import math
import struct
import wave

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/main/res/raw'
RATE = 22050
SOUNDS = {
    'launch': [(520, 0.075), (780, 0.065)],
    'bounce': [(980, 0.055)],
    'match': [(660, 0.075), (880, 0.09)],
    'drop': [(880, 0.07), (660, 0.07), (440, 0.11)],
    'victory': [(523.25, 0.12), (659.25, 0.12), (783.99, 0.20)],
    'defeat': [(392, 0.13), (329.63, 0.20)],
}

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, notes in SOUNDS.items():
        samples = []
        for hz, duration in notes:
            count = int(RATE * duration)
            for i in range(count):
                t = i / RATE
                envelope = min(1, t / 0.008) * max(0, 1 - i / count) ** 1.8
                value = (math.sin(2 * math.pi * hz * t) + 0.18 * math.sin(4 * math.pi * hz * t))
                samples.append(int(value * envelope * 6500))
        with wave.open(str(OUT / f'{name}.wav'), 'wb') as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(RATE)
            audio.writeframes(struct.pack('<' + 'h' * len(samples), *samples))

if __name__ == '__main__':
    main()
