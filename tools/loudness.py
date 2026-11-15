"""Offline loudness evidence (B20 / ui_gates loudness): renders a 30 s session of the app's own WAVs at the default
volume stop (100 %, SoundPlayer.VOLUME_PERCENT[2]) with ±5 % rate and one instance per kind (a new one stops the
previous one): typing 12 × 3 + 4 = at ~4 keys a second, a unit tap, 📏 out and back in. kj-mbp-m5 has no audio
loopback from the emulator, so this is rendered, not recorded (build.md「只有短促反馈音的应用」): RMS over the voiced
part (20 ms windows above peak − 30 dB). Also 5 quick taps of each kind: the peak must stay ≤ −1 dBFS.
  python3 tools/loudness.py OUT.wav"""
import math, os, random, struct, sys, wave
SR = 44100
SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
VOL = 1.0
FILES = {"key": ["key", "key_2", "key_3"], "equals": ["equals"], "tape": ["tape"], "tape_in": ["tape_in"], "tick": ["key", "key_2", "key_3"]}
GAIN = {"key": 1.0, "equals": 1.0, "tape": 1.0, "tape_in": 1.0, "tick": 0.5}

def load(n):
    with wave.open(os.path.join(SND, n + ".wav")) as w:
        return [v / 32768 for v in struct.unpack("<%dh" % w.getnframes(), w.readframes(w.getnframes()))]

def rate(x, r): return [x[min(len(x) - 1, int(i * r))] for i in range(int(len(x) / r))]

def render(events, seconds):
    buf = [0.0] * int(seconds * SR); last = {}; rnd = random.Random(3)
    for t, kind in events:
        x = rate(load(rnd.choice(FILES[kind])), 0.95 + rnd.random() * 0.1)
        base = "key" if kind == "tick" else kind
        o = int(t * SR)
        if base in last:  # one instance per kind: stop the previous one
            s0, n0, y0 = last[base]
            for i in range(o, min(len(buf), s0 + n0)): buf[i] -= y0[i - s0]
        y = [v * VOL * GAIN[kind] for v in x]
        for i, v in enumerate(y):
            if o + i < len(buf): buf[o + i] += v
        last[base] = (o, len(y), y)
    return buf

def levels(buf, db=30):
    peak = max(abs(v) for v in buf) or 1e-9
    win = 882; thr = (peak * 10 ** (-db / 20)) ** 2
    e = [sum(v * v for v in buf[k:k + win]) / win for k in range(0, len(buf) - win, win)]
    voiced = [x for x in e if x >= thr] or [1e-12]
    return 10 * math.log10(sum(voiced) / len(voiced)), 20 * math.log10(peak)

ev = []; t = 0.4
while t < 28:
    for k in "12*3+4": ev.append((t, "key")); t += 0.25
    ev.append((t, "equals")); t += 0.6
    ev.append((t, "key")); t += 0.5          # unit
    ev.append((t, "tape")); t += 1.0         # 📏 out
    ev.append((t, "tick")); t += 0.4
    ev.append((t, "tape_in")); t += 0.8      # 📏 in
buf = render(ev, 30)
rms, peak = levels(buf)
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(struct.pack("<%dh" % len(buf), *[max(-32767, min(32767, int(v * 32767))) for v in buf]))
print("session (rendered, default 100 %%): voiced RMS %.1f dBFS, peak %.1f dBFS" % (rms, peak))
for db in (40, 50): print("session: voiced RMS %.1f dBFS at -%d dB threshold" % (levels(buf, db)[0], db))
for kind in FILES:
    r, p = levels(render([(0.2 + i * 0.12, kind) for i in range(5)], 2))
    print("5 quick %s: peak %.1f dBFS" % (kind, p))
for v in (0.5,):
    VOL = v; r, p = levels(render(ev, 30)); print("same session at the 50 %% stop: voiced RMS %.1f dBFS" % r)
