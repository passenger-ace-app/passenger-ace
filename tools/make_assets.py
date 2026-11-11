"""원본 소리를 앱 자산으로 만든다. 사용: python tools/make_assets.py <시안 폴더>

공모전이 블라인드 심사라서 원본 경로는 코드에 적지 않고 인자로 받는다.
다시 돌리면 새로 생긴 이야기와 바뀐 줄만 처리한다.
"""
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

import imageio_ffmpeg
from PIL import Image

sys.stdout.reconfigure(encoding="utf-8")
FF = imageio_ffmpeg.get_ffmpeg_exe()
ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
NAV_ASSETS = ROOT / "navsim" / "src" / "main" / "assets"
RES = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
# 표지 파일 이름(ace-cover-<이름>.png)이 이야기 id와 다른 것만 적는다. 연재는 회차 없이 표지 하나를 같이 쓴다.
COVER_IDS = {"after-twenty-years": "twenty-years", "emperors-clothes": "emperor-clothes", "gift-of-the-magi": "christmas-gift",
             "gyeonwu-jiknyeo": "gyeonu", "little-match-girl": "match-girl", "sun-moon-siblings": "sun-moon", "the-bet": "bet"}
COVER_PX = 640  # 앱 표지 칸 640dp, 밀도 160이라 640px
# 앱 폴더 이름(두 번째)은 StoryService의 ORDER와 맞춘다. 끝의 수는 연재 회차, 단편은 0이다.
STORIES = [
    ("미다스-왕의-손", "midas", "미다스 왕의 손", "그리스 신화", 0),
    ("세-가지-질문", "three-questions", "세 가지 질문", "톨스토이", 0),
    ("햄릿", "hamlet", "햄릿", "셰익스피어", 0),
    ("해와-바람", "sun-wind", "해와 바람", "이솝", 0),
    ("크리스마스-선물", "christmas-gift", "크리스마스 선물", "오 헨리", 0),
    ("마지막-잎새", "last-leaf", "마지막 잎새", "오 헨리", 0),
    ("목걸이", "necklace", "목걸이", "모파상", 0),
    ("오르페우스와-에우리디케", "orpheus", "오르페우스와 에우리디케", "그리스 신화", 0),
    ("사람에게는-얼마만큼의-땅이-필요한가", "how-much-land", "사람에게는 얼마만큼의 땅이 필요한가", "톨스토이", 0),
    ("흥부와-놀부", "heungbu", "흥부와 놀부", "우리 옛이야기", 0),
    ("토끼와-거북", "tortoise-hare", "토끼와 거북", "이솝", 0),
    ("벌거벗은-임금님", "emperor-clothes", "벌거벗은 임금님", "안데르센", 0),
    ("성냥팔이-소녀", "match-girl", "성냥팔이 소녀", "안데르센", 0),
    ("브레멘-음악대", "bremen", "브레멘 음악대", "그림 형제", 0),
    ("20년-후", "twenty-years", "20년 후", "오 헨리", 0),
    ("내기", "bet", "내기", "체호프", 0),
    ("이카로스의-날개", "icarus", "이카로스의 날개", "그리스 신화", 0),
    ("판도라의-상자", "pandora", "판도라의 상자", "그리스 신화", 0),
    ("해와-달이-된-오누이", "sun-moon", "해와 달이 된 오누이", "우리 옛이야기", 0),
    ("견우와-직녀", "gyeonu", "견우와 직녀", "우리 옛이야기", 0),
    ("몬테크리스토-백작-1회", "monte-cristo-1", "몬테크리스토 백작", "뒤마", 1),
    ("몬테크리스토-백작-2회", "monte-cristo-2", "몬테크리스토 백작", "뒤마", 2),
    ("몬테크리스토-백작-3회", "monte-cristo-3", "몬테크리스토 백작", "뒤마", 3),
    ("몬테크리스토-백작-4회", "monte-cristo-4", "몬테크리스토 백작", "뒤마", 4),
    ("몬테크리스토-백작-5회", "monte-cristo-5", "몬테크리스토 백작", "뒤마", 5),
    ("몬테크리스토-백작-6회", "monte-cristo-6", "몬테크리스토 백작", "뒤마", 6),
]
# 앱이 쓰는 에이스 말만 넣는다. 미리 알림은 차선·추가 시간을 말하지 않는 alert_plain 하나다.
# 옆자리 안내 녹음은 help.json의 답·먼저 알림 id와 lines 키와 같은 이름이다.
HELP = json.loads((ASSETS / "help.json").read_text(encoding="utf-8"))
ACE = ["alert_plain", "next", "end", "start", "resume", "missed", "yes"] +     [a["id"] for a in HELP["answers"]] + [t["id"] for t in HELP["tips"]] + list(HELP["lines"])


def duration_ms(path):
    err = subprocess.run([FF, "-i", str(path)], capture_output=True, text=True, encoding="utf-8", errors="replace").stderr
    h, m, s = re.search(r"Duration: (\d+):(\d+):([\d.]+)", err).groups()
    return round((int(h) * 3600 + int(m) * 60 + float(s)) * 1000)


def stale(src, dst):
    return not dst.exists() or dst.stat().st_mtime < src.stat().st_mtime


def encode(src, dst):
    r = subprocess.run([FF, "-y", "-loglevel", "error", "-i", str(src), "-map_metadata", "-1", "-ac", "1", "-b:a", "96k", str(dst)],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode:
        sys.exit(f"ffmpeg 실패 {src.name}: {r.stderr[-300:]}")


def story(parts, folder, sid, title, source, part):
    segs = sorted((parts / folder).glob("seg_*.wav"), key=lambda p: int(p.stem[4:]))  # 연재는 100줄이 넘는다
    if not segs or not (parts / folder / "list.txt").exists():  # list.txt는 녹음이 다 끝나야 생긴다
        print(f"건너뜀: {folder} (아직 없음)")
        return
    out = ASSETS / "stories" / sid
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.mp3"):
        if int(old.stem) > len(segs):
            old.unlink()
    ms = []
    for i, seg in enumerate(segs):
        assert seg.stem == f"seg_{i + 1:02}", f"번호가 빠졌다: {seg}"
        mp3 = out / f"{i + 1:02}.mp3"
        if stale(seg, mp3):
            encode(seg, mp3)
        ms.append(duration_ms(mp3))
    (out / "index.json").write_text(json.dumps({"title": title, "source": source, "part": part, "lines": len(ms), "ms": ms}, ensure_ascii=False), encoding="utf-8")
    total = sum(ms) // 1000
    print(f"{sid}\t{title}\t{len(ms)}줄\t{total // 60}분 {total % 60:02}초")


def copy_all(src, pattern, dst):
    dst.mkdir(parents=True, exist_ok=True)
    files = sorted(src.glob(pattern))
    for f in files:
        shutil.copy2(f, dst / f.name)
    print(f"{dst.name}: {len(files)}개")


def copy_ace(src, dst):
    dst.mkdir(parents=True, exist_ok=True)
    for old in dst.glob("*.mp3"):
        if old.stem not in ACE:
            old.unlink()
    for name in ACE:
        shutil.copy2(src / f"{name}.mp3", dst / f"{name}.mp3")
    print(f"{dst.name}: {len(ACE)}개")


def covers(src):
    ids = {sid.rsplit("-", 1)[0] if part else sid for _, sid, _, _, part in STORIES}
    made = set()
    for f in sorted(src.glob("ace-cover-*.png")):
        name = f.stem[len("ace-cover-"):]
        if name.endswith(("-old", "-old2")):  # 지난 판은 쓰지 않는다
            continue
        sid = COVER_IDS.get(name, name)
        if sid not in ids:
            print(f"표지 못 맞춤: {f.name}")
            continue
        out = RES / f"cover_{sid.replace('-', '_')}.webp"
        if stale(f, out):
            Image.open(f).convert("RGB").resize((COVER_PX, COVER_PX), Image.LANCZOS).save(out, "WEBP", quality=85, method=6)
        made.add(out.name)
    for old in RES.glob("cover_*"):
        if old.name not in made:
            old.unlink()
    print(f"표지: {len(made)}개, 표지 없는 이야기: {sorted(ids - {m[6:-5].replace('_', '-') for m in made}) or '없음'}")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    base = Path(sys.argv[1])
    for folder, sid, title, source, part in STORIES:
        story(base / "소리-샘플" / "parts", folder, sid, title, source, part)
    copy_ace(base / "소리-샘플" / "에이스", ASSETS / "ace")
    copy_all(base / "시뮬레이션" / "소리조각", "nav_*.wav", NAV_ASSETS)
    covers(base / "디자인개선" / "공용" / "표지-현대")


if __name__ == "__main__":
    main()
