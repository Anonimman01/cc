#!/usr/bin/env python3
"""
Собирает каналы YouTube по поисковым запросам (китайские дорамы и т.п.)
и пишет списки для чёрного списка AI Blocker.

Установка:   pip install yt-dlp
Запуск:      python collect_channels.py
Параметры:   python collect_channels.py -n 80 --min-videos 2 -q "мини дорама" -q "дорама миллиардер"

Результат (папка out/):
  channels.txt      имена каналов, по одному в строке: вставляется в поле «Каналы/авторы вручную»
  channels_all.csv  все найденные каналы: имя, @handle, id, сколько видео нашлось, примеры названий
  skipped.txt       что отфильтровано (короткие имена, белый список), чтобы можно было проверить
"""
import argparse
import csv
import os
import sys
import time
from collections import OrderedDict

try:
    from yt_dlp import YoutubeDL
except ImportError:
    sys.exit("Не установлен yt-dlp. Выполните: pip install yt-dlp")

DEFAULT_QUERIES = [
    "китайские дорамы",
    "китайские мини дорамы русская озвучка",
    "мини дорама автодубляж",
    "дорама миллиардер CEO",
    "дорама месть наследница",
    "дорама босс унижал а она оказалась",
    "китайская драма русская озвучка",
    "короткие дорамы на русском",
    "дорама замуж за богача",
    "cdrama русская озвучка",
    "short drama russian dub",
    "нейро дорама ИИ",
]

# Заведомо нормальные каналы (официальные платформы), их не блокируем. Дополните при необходимости.
ALLOW = [
    "iqiyi", "wetv", "netflix", "viki", "mango tv", "mangotv", "youku", "disney",
    "кинопоиск", "okko", "ivi", "start", "premier", "youtube movies", "bbc", "cctv",
]


def search(query, n):
    opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,
        "skip_download": True,
        "ignoreerrors": True,
    }
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(f"ytsearch{n}:{query}", download=False)
    return [e for e in (info or {}).get("entries") or [] if e]


def aggregate(results):
    """results: список (запрос, [записи yt-dlp]). Возвращает словарь каналов со счётчиками."""
    chans = OrderedDict()
    for query, entries in results:
        for e in entries:
            name = (e.get("channel") or e.get("uploader") or "").strip()
            if not name:
                continue
            c = chans.setdefault(name.lower(), {
                "name": name,
                "handle": e.get("uploader_id") or "",
                "id": e.get("channel_id") or "",
                "videos": set(),
                "titles": [],
                "queries": set(),
            })
            vid = e.get("id") or e.get("url") or e.get("title")
            if vid not in c["videos"]:
                c["videos"].add(vid)
                if len(c["titles"]) < 3 and e.get("title"):
                    c["titles"].append(e["title"])
            c["queries"].add(query)
    return chans


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-q", "--query", action="append", help="поисковый запрос (можно несколько); без -q берётся встроенный набор")
    ap.add_argument("-n", type=int, default=60, help="сколько результатов брать на запрос (по умолчанию 60)")
    ap.add_argument("--min-videos", type=int, default=2, help="канал попадает в channels.txt, если найден минимум в стольких видео (по умолчанию 2)")
    ap.add_argument("--min-name", type=int, default=5, help="минимальная длина имени (короткие дают ложные срабатывания)")
    ap.add_argument("--out", default="out")
    ap.add_argument("--sleep", type=float, default=2.0, help="пауза между запросами, сек")
    args = ap.parse_args()

    queries = args.query or DEFAULT_QUERIES
    os.makedirs(args.out, exist_ok=True)

    results = []
    for i, q in enumerate(queries, 1):
        print(f"[{i}/{len(queries)}] {q} ...", flush=True)
        try:
            entries = search(q, args.n)
        except Exception as ex:
            print(f"   ошибка: {ex}")
            entries = []
        print(f"   найдено видео: {len(entries)}")
        results.append((q, entries))
        time.sleep(args.sleep)

    chans = aggregate(results)
    ordered = sorted(chans.values(), key=lambda c: (-len(c["videos"]), c["name"].lower()))

    keep, skipped = [], []
    for c in ordered:
        low = c["name"].lower()
        if any(a in low for a in ALLOW):
            skipped.append((c["name"], "белый список"))
        elif len(c["name"]) < args.min_name:
            skipped.append((c["name"], "слишком короткое имя"))
        elif len(c["videos"]) < args.min_videos:
            skipped.append((c["name"], f"найдено видео: {len(c['videos'])} (< {args.min_videos})"))
        else:
            keep.append(c)

    with open(os.path.join(args.out, "channels.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(c["name"] for c in keep) + "\n")
    with open(os.path.join(args.out, "channels_all.csv"), "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["name", "handle", "channel_id", "videos_found", "queries", "example_titles"])
        for c in ordered:
            w.writerow([c["name"], c["handle"], c["id"], len(c["videos"]),
                        len(c["queries"]), " || ".join(c["titles"])])
    with open(os.path.join(args.out, "skipped.txt"), "w", encoding="utf-8") as f:
        for name, why in skipped:
            f.write(f"{name}\t{why}\n")

    print(f"\nВсего каналов: {len(ordered)}; в channels.txt: {len(keep)}; отфильтровано: {len(skipped)}")
    print(f"Файлы в папке: {os.path.abspath(args.out)}")


if __name__ == "__main__":
    main()
