#!/usr/bin/env python3
"""Добавляет новые каналы из out_auto/channels.txt в channels_auto.txt (старые остаются, дубли убираются).
Если новый файл пуст или отсутствует, channels_auto.txt не меняется.
Использование: python tools/merge_channels.py новый.txt channels_auto.txt"""
import os, sys

new_path, target = sys.argv[1], sys.argv[2]
read = lambda p: [l.strip() for l in open(p, encoding="utf-8") if l.strip()] if os.path.exists(p) else []
old, new = read(target), read(new_path)
if not new:
    print("Новых каналов нет, файл не меняю")
    sys.exit(0)
seen, out = set(), []
for n in old + new:
    if len(n) >= 6 and n.lower() not in seen:
        seen.add(n.lower()); out.append(n)
open(target, "w", encoding="utf-8").write("\n".join(out) + "\n")
print(f"Было {len(old)}, найдено {len(new)}, стало {len(out)}")
