#!/usr/bin/env python3
"""Capture visible tablet pages. No app files, OCR, or automatic tap retries."""
import argparse
import fcntl
import hashlib
import io
import json
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

from PIL import Image, ImageChops, ImageStat
from reportlab.pdfgen import canvas

ROOT = Path(__file__).resolve().parent
ADB = '/opt/homebrew/bin/adb' if Path('/opt/homebrew/bin/adb').exists() else shutil.which('adb')


class CaptureError(Exception):
    pass


def adb(*args, device=None, timeout=20):
    if not ADB:
        raise CaptureError('ADB bulunamadı. Android platform-tools kurulmalı.')
    command = [ADB] + (['-s', device] if device else []) + list(map(str, args))
    try:
        result = subprocess.run(command, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        raise CaptureError('ADB zaman aşımı. İşlem durdu; ilerletme tekrarlanmadı.') from exc
    if result.returncode:
        raise CaptureError(result.stderr.decode(errors='replace').strip() or 'ADB komutu başarısız.')
    return result.stdout


def select_device(profile, explicit=None):
    if explicit:
        if adb('get-state', device=explicit).strip() != b'device':
            raise CaptureError('Seçilen cihaz hazır değil.')
        return explicit
    identity = profile.get('device_serial')
    for attempt in range(2):
        rows = [line.split() for line in adb('devices').decode().splitlines()[1:] if line.strip()]
        candidates = [row[0] for row in rows if len(row) >= 2 and row[1] == 'device']
        matches = {}
        for target in candidates:
            serial = adb('shell', 'getprop', 'ro.serialno', device=target).decode().strip()
            if not identity or serial == identity:
                # mDNS and IP endpoints can both refer to the same tablet.
                matches.setdefault(serial, target)
        if len(matches) == 1:
            return next(iter(matches.values()))
        if len(matches) > 1:
            raise CaptureError('Birden fazla tablet var. --device ile birini seçin.')
        if attempt == 0:
            # Reconnect only to the saved tablet, never an unrelated discovered device.
            time.sleep(1)
            for line in adb('mdns', 'services').decode().splitlines():
                parts = line.split()
                if len(parts) == 3 and parts[1] == '_adb-tls-connect._tcp':
                    if identity and parts[0].startswith(f'adb-{identity}-'):
                        adb('connect', parts[2], timeout=10)
    raise CaptureError('Tablet bağlı değil. Aynı Wi-Fi ağında Kablosuz hata ayıklamayı açın. '
                       'Gerekirse ./capture --connect IP:BAĞLANTI_PORTU kullanın.')


def bounds(node):
    values = tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    if len(values) != 4:
        raise CaptureError('Ekran alanı okunamadı.')
    return values


def center(box):
    x1, y1, x2, y2 = box
    return [(x1 + x2) // 2, (y1 + y2) // 2]


def read_state(device, profile):
    # /dev/tty keeps the accessibility dump off the tablet's storage.
    raw = adb('exec-out', 'uiautomator', 'dump', '/dev/tty', device=device)
    start, end = raw.find(b'<?xml'), raw.rfind(b'</hierarchy>')
    if start < 0 or end < 0:
        raise CaptureError('Ekran okunamadı. Kilidi açıp kitap sayfasına dönün.')
    root = ET.fromstring(raw[start:end + len(b'</hierarchy>')])
    nodes = list(root.iter('node'))
    if not nodes or nodes[0].get('package') != profile['package']:
        raise CaptureError('Beklenen kitap uygulaması önde değil. Çekim durduruldu.')
    if profile['mode'] != 'isler':
        return {'number': None, 'crop': tuple(profile['crop']),
                'action': profile['next'], 'rotation': root.get('rotation')}
    if any(n.get('content-desc') in {'Kitabı Kapat', 'Sayfa Görselleri', 'Sayfaya Git'}
           or n.get('text') in {'Sayfaya Git', 'Sayfa Görselleri'} for n in nodes):
        raise CaptureError('Kitap menüsü veya sayfa seçici açık. Kapatıp yeniden başlayın.')
    bars = [n for n in nodes if n.get('content-desc') == 'Sayfa Görünümü']
    if len(bars) != 1:
        raise CaptureError('Tek sayfa görünümü bulunamadı. Kitapta bir sayfayı açın.')
    bar = bars[0]
    buttons = list(bar.iter('node'))
    numbers = [int(n.get('content-desc')) for n in buttons if n.get('content-desc', '').isdigit()]
    forward = [n for n in buttons if n.get('content-desc') == 'İleri' and n.get('enabled') == 'true']
    if len(numbers) != 1 or len(forward) != 1:
        raise CaptureError('Sayfa numarası veya İleri düğmesi okunamadı.')
    width, height = profile['screen_size']
    bar_top = bounds(bar)[1]
    areas = [bounds(n) for n in nodes if n.get('NAF') == 'true' and n.get('clickable') == 'true']
    areas = [b for b in areas if b[2] - b[0] >= width * .8
             and b[3] - b[1] >= height * .5 and b[3] <= bar_top]
    if not areas:
        raise CaptureError('Kitabın tam sayfa alanı bulunamadı; kırpma tahmin edilmedi.')
    crop = max(areas, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
    # Expanded drawing bars overlap book content; collapse through the app UI first.
    if any(n.get('content-desc') == 'Basit' for n in nodes):
        raise CaptureError('Çizim çubuğu sayfayı örtüyor. Çubuğun altındaki Basit düğmesine basın.')
    return {'number': numbers[0], 'crop': crop,
            'action': ['tap', *center(bounds(forward[0]))], 'rotation': root.get('rotation')}


def screenshot(device, profile, crop):
    raw = adb('exec-out', 'screencap', '-p', device=device)
    try:
        im = Image.open(io.BytesIO(raw))
        im.load()
    except OSError as exc:
        raise CaptureError('Ekran görüntüsü geçersiz veya eksik.') from exc
    if list(im.size) != profile['screen_size']:
        raise CaptureError('Ekran boyutu/yönü değişti; yanlış kırpmayı önlemek için duruldu.')
    x1, y1, x2, y2 = crop
    if not (0 <= x1 < x2 <= im.width and 0 <= y1 < y2 <= im.height):
        raise CaptureError('Yakalama alanı ekranın dışında.')
    return im.convert('RGB').crop(crop)


def difference(a, b):
    return max(ImageStat.Stat(ImageChops.difference(a, b)).mean)


def stable_page(device, profile, previous_state, expected, previous_image, timeout):
    deadline = time.monotonic() + timeout
    candidate = None
    stable = 0
    while time.monotonic() < deadline:
        current = screenshot(device, profile, previous_state['crop'])
        stable = stable + 1 if candidate is not None and difference(candidate, current) < .08 else 0
        candidate = current
        if stable >= 2:
            state = read_state(device, profile)
            if state['crop'] != previous_state['crop'] or state['rotation'] != previous_state['rotation']:
                raise CaptureError('Sayfa yerleşimi veya ekran yönü değişti.')
            number = state['number']
            if number != expected:
                if number is not None and expected is not None and number > expected:
                    raise CaptureError(f'Sayfa atlandı: {expected} beklenirken {number} görüldü.')
                # Never repeat a navigation gesture when it may have succeeded.
                time.sleep(.6)
                continue
            after = screenshot(device, profile, state['crop'])
            stats = ImageStat.Stat(after.convert('L'))
            uniform = stats.stddev[0] < 2
            unchanged = previous_image is not None and difference(previous_image, after) < .08
            if not uniform and not unchanged and difference(current, after) < .08:
                return after, state
        time.sleep(.6)
    raise CaptureError('Sayfa değişmedi, boş kaldı veya yüklenmesi bitmedi. '
                       'Kitap sonu / bağlantı / ekranı kontrol edin. İlerletme tekrarlanmadı.')


def save_json(path, data):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    temp.replace(path)


def make_pdf(paths, destination):
    temp = destination.with_suffix('.tmp')
    pdf = canvas.Canvas(str(temp), pageCompression=1)
    pdf.setTitle(destination.stem)
    pdf.setAuthor('Tab PDF Capture')
    for path in paths:
        with Image.open(path) as im:
            width, height = im.size
        # Image pixels are preserved; PDF dimensions correspond to 192 dpi.
        w, h = width * 72 / 192, height * 72 / 192
        pdf.setPageSize((w, h))
        pdf.drawImage(str(path), 0, 0, width=w, height=h)
        pdf.showPage()
    pdf.save()
    temp.replace(destination)


def transfer(device, path):
    remote = '/sdcard/Download/' + path.name
    adb('push', path, remote, device=device, timeout=180)
    adb('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', 'file://' + remote,
        '-t', 'application/pdf', '-p', 'com.samsung.android.app.notes', '-f', '1', device=device)
    print(f'Tablete aktarıldı: Download/{path.name}\nSamsung Notes’ta “Nota ekle” ile içe aktarın.')


def capture(args, profile):
    device = select_device(profile, args.device)
    adb('shell', 'input', 'keyevent', '224', device=device)  # Wake; never unlock.
    time.sleep(.5)
    state = read_state(device, profile)
    count = args.pages
    if count is None:
        count = int(input('Kaç sayfa? '))
    if not 1 <= count <= 2000:
        raise CaptureError('Sayfa sayısı 1–2000 arasında olmalı.')
    if shutil.disk_usage(ROOT).free < count * 5_000_000 + 100_000_000:
        raise CaptureError('Çekim için yeterli boş disk alanı yok.')
    first = state['number']
    print(f'Tablet: {device}\nBaşlangıç: {first if first is not None else "açık ekran"}; {count} sayfa.')
    if first is None:
        print('Bu profilde sayfa numarası okunmuyor; atlama kontrolü yapılamaz.')
    print('Çekim sırasında tablete dokunmayın. Durdurmak için Ctrl+C.')
    label = f'{datetime.now():%Y%m%d-%H%M%S-%f}'
    run = ROOT / 'output' / 'captures' / label
    run.mkdir(parents=True)
    pdf_dir = ROOT / 'output' / 'pdf'
    pdf_dir.mkdir(parents=True, exist_ok=True)
    stem = f'{label}-{args.profile.stem}'
    record = {'status': 'running', 'requested': count, 'device': device,
              'profile': profile, 'started_at': datetime.now().isoformat(), 'pages': []}
    paths, hashes, previous = [], set(), None
    started = time.monotonic()
    try:
        for index in range(count):
            expected = first + index if first is not None else None
            im, state = stable_page(device, profile, state, expected, previous, args.timeout)
            digest = hashlib.sha256(im.tobytes()).hexdigest()
            if digest in hashes:
                raise CaptureError('Daha önce kaydedilmiş bir sayfa tekrar göründü.')
            path = run / f'{index + 1:04}.png'
            temp = run / f'{index + 1:04}.tmp'
            im.save(temp, format='PNG')
            temp.replace(path)
            paths.append(path)
            hashes.add(digest)
            record['pages'].append({'file': path.name, 'number': expected, 'sha256': digest})
            save_json(run / 'capture.json', record)
            print(f'[{index + 1}/{count}] Sayfa {expected if expected is not None else index + 1} kaydedildi.', flush=True)
            previous = im
            if index + 1 < count:
                adb('shell', 'input', *state['action'], device=device)
                time.sleep(.8)
        if first is not None:
            stem += f'-{first}-{first + count - 1}'
        pdf = pdf_dir / f'{stem}.pdf'
        make_pdf(paths, pdf)
        record.update(status='complete', pdf=str(pdf), elapsed_seconds=round(time.monotonic() - started, 1))
        save_json(run / 'capture.json', record)
    except (Exception, KeyboardInterrupt) as exc:
        record.update(status='incomplete', error=str(exc) or 'Kullanıcı durdurdu.')
        save_json(run / 'capture.json', record)
        print(f'EKSİK: {len(paths)}/{count} sayfa. Kaynaklar: {run}', file=sys.stderr)
        if paths:
            partial = pdf_dir / f'{stem}.partial.pdf'
            try:
                make_pdf(paths, partial)
                print(f'Yalnızca kaydedilen sayfalar: {partial}', file=sys.stderr)
            except Exception as pdf_error:
                print(f'Kısmi PDF üretilemedi: {pdf_error}. PNG dosyaları duruyor.', file=sys.stderr)
        raise
    print(f'TAMAMLANDI: {len(paths)} sayfa, {record["elapsed_seconds"]} saniye\nPDF: {pdf}')
    if not args.no_transfer:
        try:
            transfer(device, pdf)
        except CaptureError as exc:
            raise CaptureError(f'PDF Mac’te hazır; tablete aktarım/açılış başarısız: {exc}') from exc


def main():
    parser = argparse.ArgumentParser(description='Açık tablet sayfasından sıralı PDF oluşturur.')
    parser.add_argument('--pages', type=int, help='Açık sayfa dahil toplam sayfa sayısı')
    parser.add_argument('--profile', type=Path, default=next((p for p in (ROOT / 'profiles' / 'isler.local.json', ROOT / 'profiles' / 'isler.json') if p.exists())))
    parser.add_argument('--device', help='adb devices çıktısındaki cihaz adresi')
    parser.add_argument('--connect', metavar='IP:PORT', help='Bağlantı portuna bağlan ve çık')
    parser.add_argument('--pair', metavar='IP:PORT', help='Eşleştirme portuyla eşleş ve çık')
    parser.add_argument('--timeout', type=float, default=30, help='Sayfa başına bekleme süresi (saniye)')
    parser.add_argument('--no-transfer', action='store_true', help='PDF yalnızca Mac’te kalsın')
    args = parser.parse_args()
    try:
        if not ADB:
            raise CaptureError('ADB bulunamadı.')
        with (ROOT / '.capture.lock').open('w') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise CaptureError('Başka bir çekim çalışıyor; ikinci çekim başlatılmadı.') from exc
            if args.pair:
                result = subprocess.run([ADB, 'pair', args.pair])
                return result.returncode
            if args.connect:
                result = adb('connect', args.connect).decode().strip()
                print(result)
                return 0 if ('connected to ' in result) else 1
            if args.timeout < 5:
                raise CaptureError('--timeout en az 5 saniye olmalı.')
            profile = json.loads(args.profile.read_text())
            if profile.get('mode') not in {'isler', 'generic'}:
                raise CaptureError('Profil mode değeri isler veya generic olmalı.')
            size = profile.get('screen_size', [])
            if len(size) != 2 or not all(type(n) is int and n > 0 for n in size):
                raise CaptureError('Profil screen_size değeri iki pozitif tam sayı olmalı.')
            if profile['mode'] == 'generic':
                action = profile.get('next', [])
                lengths = {'tap': 3, 'swipe': 6}
                if not action or len(action) != lengths.get(action[0]) or not all(type(n) is int and n >= 0 for n in action[1:]):
                    raise CaptureError('Profil next değeri sayısal tap veya swipe koordinatları içermeli.')
                points = action[1:3] if action[0] == 'tap' else action[1:5]
                if any(n >= size[i % 2] for i, n in enumerate(points)):
                    raise CaptureError('Geçiş koordinatları ekranın dışında.')
                if action[0] == 'swipe' and not 50 <= action[5] <= 2000:
                    raise CaptureError('Kaydırma süresi 50–2000 ms arasında olmalı.')
            capture(args, profile)
        return 0
    except KeyboardInterrupt:
        print('Durduruldu.', file=sys.stderr)
        return 130
    except (CaptureError, OSError, ValueError, KeyError, ET.ParseError) as exc:
        print(f'HATA: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
