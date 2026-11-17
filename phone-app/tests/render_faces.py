#!/usr/bin/env python3
"""
Offline face renderer for the hand-drawn face states: pulls the pre-drawn
SVGs (face-happy, face-curious, ...) straight out of index.html, wraps each in
the pill head, and applies the same head tilt/slump + gaze offsets the app's
EXPRESSIONS use. No device needed.

Style matches the app's inverted CSS: black head, white strokes.

  python3 phone-app/tests/render_faces.py [outdir]

Writes one PNG per mood plus a montage sheet (sheet.png).
Requires rsvg-convert and montage (ImageMagick) on PATH.
"""
import os
import re
import subprocess
import sys

SRC = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'index.html')
OUT = sys.argv[1] if len(sys.argv) > 1 else '/tmp/faces_states'

# Inverted palette (copied from index.html :root)
STROKE = '#ffffff'
FILL = '#ffffff'
HEAD_BG = '#000000'
STROKE_W = 15        # svg.face-state stroke-width
HEAD_BORDER = 10     # .robot-head border (scaled for the 400x250 sheet)

# Copied verbatim from EXPRESSIONS in index.html (mood -> head pose / gaze)
EXPRESSIONS = {
    'neutral':   {},
    'attentive': dict(tilt=3),
    'happy':     dict(mouthCurve=5),
    'excited':   dict(tilt=-3, mouthCurve=8, mouthOpen=0.34),
    'surprised': dict(mouthOpen=0.5),
    'thinking':  dict(gazeX=12, gazeY=-9, tilt=-5),
    'confused':  dict(tilt=7),
    'sad':       dict(tilt=-5, gazeY=6, mouthCurve=-6),
    'angry':     dict(mouthCurve=-4),
    'bored':     dict(tilt=-4, gazeX=-10),
    'sleepy':    dict(tilt=-7, slump=3),
    'asleep':    dict(tilt=-9, slump=7, gazeY=4),
    'wink':      dict(gazeX=8),
    'dead':      {},
}

# mood -> face-state id (copied from MOOD_FACE in index.html)
MOOD_FACE = {
    'neutral': 'neutral', 'attentive': 'curious', 'happy': 'happy',
    'excited': 'excited', 'surprised': 'surprised', 'thinking': 'pensive',
    'confused': 'confused', 'sad': 'sad', 'angry': 'angry',
    'bored': 'bored', 'sleepy': 'sleepy', 'asleep': 'asleep',
    'wink': 'wink', 'dead': 'dead',
}


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def extract(html, face_id):
    m = re.search(rf'<svg class="face-state" id="face-{face_id}".*?</svg>', html, re.S)
    assert m, f'face-{face_id} not found'
    inner = m.group(0).split('>', 1)[1].rsplit('</svg>', 1)[0]
    # .fill-black is a CSS class in the app (white on black now); bake it in
    inner = inner.replace('class="fill-black"', f'fill="{FILL}" stroke="none"')
    # per-element stroke-width overrides (e.g. the loading bar) survive as-is
    return inner


def mouth_svg(html, open_, curve):
    """Port of renderFace()'s speaking-mouth math (dot—line—dot)."""
    half_w = 36 + open_ * 10
    x1, x2 = 200 - half_w, 200 + half_w
    y_mid = 196
    y_end = y_mid - curve * 0.6
    ctl = y_mid + curve * 1.4 + open_ * 26
    sw = 12 + open_ * 8
    dot_r = 11 + open_ * 7
    return (
        f'<circle fill="{FILL}" cx="{x1 - dot_r:.1f}" cy="{y_end:.1f}" r="{dot_r:.1f}"/>'
        f'<path fill="none" stroke="{STROKE}" stroke-width="{sw:.1f}" stroke-linecap="round" '
        f'd="M {x1:.1f} {y_end:.1f} Q 200 {ctl:.1f} {x2:.1f} {y_end:.1f}"/>'
        f'<circle fill="{FILL}" cx="{x2 + dot_r:.1f}" cy="{y_end:.1f}" r="{dot_r:.1f}"/>'
    )


def build(frame_id, inner, tilt=0.0, slump=0.0, gaze=(0, 0), mouth=''):
    """Pill head + face state (+ optional mouth), matching the app's CSS."""
    gx, gy = gaze
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 250" width="400" height="250">'
        f'<rect x="7" y="7" width="386" height="236" rx="118" ry="118" fill="{HEAD_BG}" '
        f'stroke="{STROKE}" stroke-width="{HEAD_BORDER}"/>'
        f'<g transform="rotate({tilt:.2f} 200 125) translate(0 {slump:.1f})">'
        f'<g transform="translate({gx:.1f} {gy:.1f})" fill="none" stroke="{STROKE}" '
        f'stroke-width="{STROKE_W}" stroke-linecap="round" stroke-linejoin="round">'
        f'{inner}</g>'
        f'<g fill="none" stroke="{STROKE}" stroke-width="14" stroke-linecap="round">{mouth}</g>'
        '</g></svg>'
    )


def main():
    html = open(SRC).read()
    os.makedirs(OUT, exist_ok=True)

    frames = []
    for mood, p in EXPRESSIONS.items():
        face_id = MOOD_FACE[mood]
        inner = extract(html, face_id)
        mouth = mouth_svg(html, p.get('mouthOpen', 0.0), p.get('mouthCurve', 0.0)) \
            if p.get('mouthOpen') else ''
        frames.append((mood, build(mood, inner, p.get('tilt', 0.0), p.get('slump', 0.0),
                                   (p.get('gazeX', 0), p.get('gazeY', 0)), mouth)))

    # blink: the app flashes the sleepy face for ~120ms
    frames.append(('blink', build('blink', extract(html, 'sleepy'))))

    # speaking: happy face + the animated mouth at two phases
    happy = extract(html, 'happy')
    frames.append(('speaking', build('speaking', happy, mouth=mouth_svg(html, 0.3, 5))))
    frames.append(('speaking-open', build('speaking-open', happy, mouth=mouth_svg(html, 0.85, 9))))

    # one raw frame per face state, so every hand-drawn face gets eyeballed.
    # FACE_STATES is the JS array — id="face-layer" etc. must not match.
    fs = re.search(r'const FACE_STATES = \[([^\]]*)\]', html)
    assert fs, 'FACE_STATES not found'
    for face_id in sorted(re.findall(r"'([a-z]+)'", fs.group(1))):
        frames.append((f'state-{face_id}', build(f'state-{face_id}', extract(html, face_id))))

    for name, svg in frames:
        svg_path = os.path.join(OUT, f'{name}.svg')
        png_path = os.path.join(OUT, f'{name}.png')
        open(svg_path, 'w').write(svg)
        subprocess.run(['rsvg-convert', '-w', '400', '-h', '250', '-o', png_path, svg_path],
                       check=True)

    order = [n for n, _ in frames]
    subprocess.run(
        'montage ' + ' '.join(os.path.join(OUT, f'{n}.png') for n in order) +
        f' -tile 4x -geometry +8+8 -background "#000000" {os.path.join(OUT, "sheet.png")}',
        shell=True, check=True)
    print('rendered', len(frames), 'frames ->', os.path.join(OUT, 'sheet.png'))


if __name__ == '__main__':
    main()
