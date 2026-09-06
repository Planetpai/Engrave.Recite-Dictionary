#!/bin/bash
# 用法: spell.sh <term>  —— 在当前拼写卡上按词序点字母
ADB="E:/AndroidDev/sdk/platform-tools/adb.exe"
S=emulator-5554
TERM="$1"

"$ADB" -s $S shell "uiautomator dump /sdcard/ui.xml >/dev/null; cat /sdcard/ui.xml" > /tmp/ui.xml

# 可点击格子 bounds -> x1 y1 x2 y2
grep -o 'clickable="true"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' /tmp/ui.xml \
  | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' \
  | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' > /tmp/boxes.txt

# 单字母 Text 节点 -> 字母 + 中心点
grep -o 'text="[a-z]"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' /tmp/ui.xml \
  | sed -E 's/.*text="([a-z])".*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4 \5/' \
  | awk '{print $1, int(($2+$4)/2), int(($3+$5)/2)}' > /tmp/letters.txt

# 把字母归到包含其中心的格子上，输出: 字母 格子中心x 格子中心y
> /tmp/mapped.txt
while read -r x1 y1 x2 y2; do
  cx=$(( (x1 + x2) / 2 )); cy=$(( (y1 + y2) / 2 ))
  while read -r ch tx ty; do
    if [ "$tx" -ge "$x1" ] && [ "$tx" -lt "$x2" ] && [ "$ty" -ge "$y1" ] && [ "$ty" -le "$y2" ]; then
      echo "$ch $cx $cy" >> /tmp/mapped.txt
    fi
  done < /tmp/letters.txt
done < /tmp/boxes.txt

echo "mapped: $(sort /tmp/mapped.txt | tr '\n' ' ')"

# 按词序点字母，重复字母用不同的格子
declare -a used=()
for (( i=0; i<${#TERM}; i++ )); do
  ch="${TERM:$i:1}"
  hit=""
  while read -r c x y; do
    [ "$c" = "$ch" ] || continue
    skip=0
    for u in "${used[@]:-}"; do [ "$u" = "$x,$y" ] && skip=1; done
    [ $skip -eq 1 ] || { hit="$x $y"; used+=("$x,$y"); break; }
  done < /tmp/mapped.txt
  if [ -z "$hit" ]; then echo "NO MATCH for $ch"; return 1 2>/dev/null || exit 1; fi
  "$ADB" -s $S shell input tap $hit
  sleep 0.6
done
sleep 2
