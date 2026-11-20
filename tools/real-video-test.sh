#!/usr/bin/env bash
# Проверка умных границ на настоящих роликах: тот же код core, что в приложении,
# речь — тот же whisper.cpp (собранный для Linux), кусок видео — тот же yt-dlp с запасом ±60 с.
set -uo pipefail
OUT=${OUT:-real-test}
WHISPER=${WHISPER:-build-whisper/bin/whisper-cli}
MODEL=${MODEL:-models/ggml-tiny-q5_1.bin}
TOOL=tools/build/install/tools/bin/tools
mkdir -p "$OUT"
REPORT="$OUT/report.md"
echo "# Clip Radar — проверка на реальных роликах" > "$REPORT"
echo "" >> "$REPORT"
n=0
for URL in "$@"; do
  n=$((n+1))
  D="$OUT/video$n"; mkdir -p "$D"
  echo "== $URL"
  if ! yt-dlp --js-runtimes node -J --skip-download "$URL" > "$D/meta.json" 2> "$D/yt-dlp.log"; then
    echo "## $n. $URL" >> "$REPORT"; echo "yt-dlp не смог получить данные: $(tail -1 "$D/yt-dlp.log")" >> "$REPORT"; continue
  fi
  TITLE=$(python3 -c "import json,sys;print(json.load(open('$D/meta.json')).get('title',''))")
  "$TOOL" peaks "$D/meta.json" > "$D/peaks.json"
  COUNT=$(python3 -c "import json;print(len(json.load(open('$D/peaks.json'))))")
  echo "## $n. $TITLE" >> "$REPORT"
  echo "$URL — найдено пиков графика пересмотров: $COUNT" >> "$REPORT"; echo "" >> "$REPORT"
  if [ "$COUNT" = "0" ]; then echo "У ролика нет графика пересмотров — пропускаю." >> "$REPORT"; continue; fi
  read -r PEAK WS WE MS ME SCORE <<< "$(python3 -c "
import json; p=json.load(open('$D/peaks.json'))[0]
ms=p['mustStart']; me=p['mustEnd']
print(p['peak'], p['winStart'], p['winEnd'], '-' if ms is None else ms-p['winStart'], '-' if me is None else me-p['winStart'], p['score'])")"
  # Кусок видео с запасом ±60 с вокруг лучшего пика (как в приложении).
  yt-dlp --js-runtimes node -f "bv*[height<=720][vcodec^=avc1]+ba[ext=m4a]/b[height<=720]/b" \
    --download-sections "*${WS}-${WE}" --merge-output-format mp4 -o "$D/section.%(ext)s" "$URL" >> "$D/yt-dlp.log" 2>&1
  SEC=$(ls "$D"/section.* 2>/dev/null | head -1)
  if [ -z "$SEC" ]; then echo "Кусок видео не скачался: $(tail -2 "$D/yt-dlp.log" | tr '\n' ' ')" >> "$REPORT"; continue; fi
  ffmpeg -loglevel error -y -i "$SEC" -ar 16000 -ac 1 -c:a pcm_s16le "$D/audio.wav"
  # Речь: пословные таймкоды.
  "$WHISPER" -m "$MODEL" -f "$D/audio.wav" -l en -ml 1 -sow -oj -of "$D/words" -np > "$D/whisper.log" 2>&1
  # Смены сцен.
  ffmpeg -hide_banner -i "$SEC" -filter:v "select='gt(scene,0.3)',showinfo" -f null - 2>&1 | grep -o "pts_time:[0-9.]*" | cut -d: -f2 > "$D/scenes.txt" || true
  PEAK_REL=$(python3 -c "print($PEAK-$WS)")
  "$TOOL" bounds "$D/audio.wav" "$D/words.json" "$D/scenes.txt" "$PEAK_REL" $MS $ME > "$D/bounds.json"
  python3 - "$D" "$WS" "$PEAK" "$SCORE" >> "$REPORT" <<'PY'
import json, sys
d, ws, peak, score = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
b = json.load(open(f"{d}/bounds.json"))
fmt = lambda s: f"{int(s//60)}:{s%60:05.2f}"
print(f"- Пик графика: **{fmt(peak)}** (оценка {score}); скачано окно {fmt(ws)}–{fmt(ws+120)}")
print(f"- Клип: **{fmt(ws+b['start'])} → {fmt(ws+b['end'])}** (длина {b['length']:.1f} с)")
print(f"- Начало: {b['startReason']}  \n  на границе: {b['startContext']}")
print(f"- Конец: {b['endReason']}  \n  на границе: {b['endContext']}")
if b['hook']: print(f"- Хук (если включён): {fmt(ws+b['hook'][0])}–{fmt(ws+b['hook'][1])}")
print(f"- Расшифровано слов: {b['words']}, смен сцен: {b['scenes']}")
print(f"- Названия: " + " / ".join(b['titles']) + f"; хук-текст: {b['hookText']}")
print(f"- Первые субтитры: " + "; ".join(b['subtitles'][:3]))
print()
PY
  S=$(python3 -c "import json;print(json.load(open('$D/bounds.json'))['start'])")
  E=$(python3 -c "import json;print(json.load(open('$D/bounds.json'))['end'])")
  # Превью клипа (центр, 9:16) — чтобы глазами проверить границы.
  ffmpeg -loglevel error -y -ss "$S" -to "$E" -i "$SEC" -vf "crop=ih*9/16:ih,scale=540:960" -c:v libx264 -preset veryfast -crf 26 -c:a aac "$OUT/clip$n.mp4" || true
done
cat "$REPORT"
