"""Сборка карточек из реальных снимков и выбранного фона. Требуются Pillow и Inkscape."""
import base64
import json
import subprocess
from pathlib import Path
from xml.sax.saxutils import escape
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
WIDTH, HEIGHT = 1440, 2560

def uri(path):
    return 'data:image/png;base64,' + base64.b64encode(path.read_bytes()).decode()

def build(card):
    screen = ROOT / card['source']
    with Image.open(screen) as image:
        width, height = image.size
    top, bottom = card['crop_top'], card['crop_bottom']
    visible = height - top - bottom
    scale = min(920 / width, 1760 / visible)
    sw, sh = width * scale, visible * scale
    sx, sy = (WIDTH - sw) / 2, 650
    color = '#4B301A'
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}">',
             f'<image href="{uri(ROOT / "assets/backgrounds/01-cream.png")}" width="1440" height="2560" preserveAspectRatio="xMidYMid slice"/>',
             f'<text x="120" y="140" font-family="DejaVu Sans" font-size="30" letter-spacing="4" fill="{color}">СТОП ЭНУРЕЗ</text>']
    for n, line in enumerate(card['title']):
        parts.append(f'<text x="120" y="{270 + n*100}" font-family="DejaVu Sans" font-size="78" font-weight="bold" fill="{color}">{escape(line)}</text>')
    for n, line in enumerate(card['subtitle']):
        parts.append(f'<text x="120" y="{480 + n*48}" font-family="DejaVu Sans" font-size="34" fill="{color}">{escape(line)}</text>')
    parts += [f'<rect x="{sx-14}" y="{sy-14}" width="{sw+28}" height="{sh+28}" rx="42" fill="#63452B"/>',
              f'<defs><clipPath id="screen"><rect x="{sx}" y="{sy}" width="{sw}" height="{sh}" rx="30"/></clipPath></defs>',
              f'<image href="{uri(screen)}" x="{sx}" y="{sy-top*scale}" width="{sw}" height="{height*scale}" clip-path="url(#screen)"/>', '</svg>']
    svg = ROOT / 'sources/layouts' / f'{card["id"]:02}.svg'
    svg.parent.mkdir(parents=True, exist_ok=True)
    svg.write_text('\n'.join(parts))
    out = ROOT / 'set-01-cream' / f'{card["id"]:02}.png'
    out.parent.mkdir(exist_ok=True)
    subprocess.run(['inkscape', str(svg), '--export-type=png', f'--export-filename={out}'], check=True, stdout=subprocess.DEVNULL)
    with Image.open(out) as image:
        image.convert('RGB').save(out, optimize=True)
    assert out.stat().st_size <= 3_000_000, f'Слишком большой файл: {out}'
    return out

if __name__ == '__main__':
    cards = json.loads((ROOT / 'sources/manifest.json').read_text())['cards']
    paths = [build(card) for card in cards]
    overview = Image.new('RGB', (1080, 1320), '#F8F0E4')
    draw = ImageDraw.Draw(overview)
    for index, path in enumerate(paths):
        with Image.open(path) as image:
            assert image.size == (WIDTH, HEIGHT) and image.mode == 'RGB'
            image.thumbnail((340, 605))
            x, y = (index % 3)*360+10, (index//3)*660+35
            overview.paste(image, (x, y))
            draw.text((x, y-25), f'{index+1:02}', fill='#4B301A', font=ImageFont.truetype('DejaVuSans.ttf', 20))
    overview.save(ROOT / 'overview.jpg', quality=92)
    print('Готовы шесть карточек и общий обзор.')
