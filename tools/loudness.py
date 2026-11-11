"""Offline loudness evidence (B20 / ui_gates loudness): renders a 30 s session of the app's own WAVs at the default
volume (85 %) with the app's gains (pin 1.0, chip 1.0, pour 1.0, thud 1.0, tape 0.9) and ±5 % rate, one instance per
kind (a new one stops the previous one): measure 3 slots (pin, tape, pin, chip each), = (pour / thud), repeat.
kj-mbp-m5 has no audio loopback, so this is rendered, not recorded (build.md「只有短促反馈音的应用」).
Also renders 5 quick taps of the same kind to check the peak stays <= -1 dBFS.
  python3 tools/loudness.py OUT.wav"""
import math, os, random, struct, sys, wave
SR = 44100
SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
GAIN = {"pin": 1.0, "chip": 1.0, "pour": 1.0, "thud": 1.0, "tape": 0.9}
VOL = 0.85  # default volume stop (SoundPlayer.VOLUME_PERCENT[1])

def load(n):
    with wave.open(os.path.join(SND, n + ".wav")) as w:
        return [v / 32768 for v in struct.unpack("<%dh" % w.getnframes(), w.readframes(w.getnframes()))]

def rate(x, r): return [x[min(len(x) - 1, int(i * r))] for i in range(int(len(x) / r))]

def render(events, seconds):
    buf = [0.0] * (seconds * SR); last = {}
    rnd = random.Random(3)
    for t, kind in events:
        variants = [n for n in ("%s" % kind, "%s_2" % kind, "%s_3" % kind) if os.path.exists(os.path.join(SND, n + ".wav"))]
        x = rate(load(rnd.choice(variants)), 0.95 + rnd.random() * 0.1)
        o = int(t * SR)
        if kind in last:  # stop the previous instance of the same kind
            s0 = last[kind]
            for i in range(o, min(len(buf), s0[0] + s0[1])): buf[i] -= s0[2][i - s0[0]] if 0 <= i - s0[0] < len(s0[2]) else 0
        g = VOL * GAIN[kind]
        y = [v * g for v in x]
        for i, v in enumerate(y):
            if o + i < len(buf): buf[o + i] += v
        last[kind] = (o, len(y), y)
    return buf

def levels(buf):
    peak = max(abs(v) for v in buf) or 1e-9
    win = 882; thr = (peak * 0.0316) ** 2
    e = [sum(v * v for v in buf[k:k + win]) / win for k in range(0, len(buf) - win, win)]
    voiced = [x for x in e if x >= thr] or [1e-12]
    return 20 * math.log10(math.sqrt(sum(voiced) / len(voiced))), 20 * math.log10(peak)

ev = []; t = 0.5
while t < 27:
    for _ in range(3):
        ev += [(t, "pin"), (t + 0.05, "tape"), (t + 1.2, "pin"), (t + 1.3, "chip")]; t += 2.0
    ev += [(t, "pour" if int(t) % 2 else "thud")]; t += 1.5
buf = render(ev, 30)
rms, peak = levels(buf)
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(struct.pack("<%dh" % len(buf), *[max(-32767, min(32767, int(v * 32767))) for v in buf]))
print("session: voiced RMS %.1f dBFS, peak %.1f dBFS" % (rms, peak))
for db in (40, 50):  # QA's thresholds (round 1 measured the voiced part at -40 / -50 dB under the peak)
    pk = max(abs(v) for v in buf); thr = (pk * 10 ** (-db / 20)) ** 2; win = 882
    e = [sum(v * v for v in buf[k:k + win]) / win for k in range(0, len(buf) - win, win)]
    vv = [x for x in e if x >= thr]
    print("session: voiced RMS %.1f dBFS at -%d dB threshold" % (10 * math.log10(sum(vv) / len(vv)), db))
for kind in GAIN:
    r, p = levels(render([(0.2 + i * 0.12, kind) for i in range(5)], 2))
    print("5 quick %-5s: peak %.1f dBFS" % (kind, p))
