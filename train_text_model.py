# ─────────────────────────────────────────────────────────────
# 진상 발언 감지 AI 학습 스크립트
#  - 입력: 음성인식으로 받은 한국어 문장
#  - 출력: "공격적인 말일 확률" (0~1)
#  - 방식: 글자 조각(1~3글자 n-gram) TF-IDF + 로지스틱 회귀
#          → 워치에서 라이브러리 없이 Kotlin 으로 바로 계산 가능
#  - 결과 파일: text_model.tsv (워치 app/src/main/assets 에 넣기)
# ─────────────────────────────────────────────────────────────
import csv, re, math, random, json, sys, os
from collections import Counter
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report, f1_score, accuracy_score, precision_score, recall_score

random.seed(42)
DATA = os.environ.get("DATA_DIR", "data")
SEED_CSV = os.environ.get("SEED_CSV", "jinsang_seed.csv")
OUT = os.environ.get("OUT", "text_model.tsv")

SEED_REPEAT = 15         # 매장 진상 문장은 적으니까 여러 번 반복해서 비중을 키움
MAX_FEATURES = 60000     # 글자 조각 최대 개수 (모델 크기)
NGRAM = (1, 3)

# 워치(Kotlin)와 똑같이 해야 하는 전처리: 소문자 → 한글·영어·숫자만 남기고 띄어쓰기 제거
KEEP = re.compile(r"[^가-힣ㄱ-ㅎㅏ-ㅣa-z0-9]")
def clean(t: str) -> str:
    return KEEP.sub("", t.lower())

def read_tsv(path):
    with open(path, encoding="utf-8") as f:
        return list(csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE))

# ── 1. 공개 데이터 ──
def load_unsmile(name):
    rows = read_tsv(f"{DATA}/korean_unsmile_dataset/{name}")
    h = rows[0]
    ci = h.index("clean")
    return [(r[0], 0 if r[ci] == "1" else 1) for r in rows[1:] if len(r) == len(h)]

def load_khs(name):
    rows = read_tsv(f"{DATA}/korean-hate-speech/labeled/{name}")
    return [(r[0], 0 if r[3] == "none" else 1) for r in rows[1:] if len(r) >= 4]

def load_curse():   # 2runo/Curse-detection-data (MIT): "문장|1(욕설)/0"
    path = f"{DATA}/Curse-detection-data/dataset.txt"
    if not os.path.exists(path): return []
    out = []
    for line in open(path, encoding="utf-8"):
        if "|" not in line: continue
        t, l = line.rsplit("|", 1)
        if l.strip() in ("0", "1"): out.append((t, int(l.strip())))
    return out

def load_malicious():   # ZIZUN/korean-malicious-comments-dataset (MIT): 0=악성, 1=정상
    path = f"{DATA}/korean-malicious-comments-dataset/Dataset.csv"
    if not os.path.exists(path): return []
    out = []
    for line in open(path, encoding="utf-8-sig").read().splitlines()[1:]:
        if "\t" not in line: continue
        t, l = line.rsplit("\t", 1)
        if l.strip() in ("0", "1"): out.append((t, 1 - int(l.strip())))
    return out

# 매장 진상 문장: 기본 시드 + 팀이 모은 문장(team_jinsang.csv, 있으면 자동 포함)
# CSV 열: text,label,split   (label 1=진상 0=일반, split 은 train 또는 test, 비우면 train)
TEAM_CSV = os.environ.get("TEAM_CSV", "team_jinsang.csv")
def load_seed(split):
    out = []
    for path in [SEED_CSV, TEAM_CSV]:
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8-sig") as f:
            for r in csv.DictReader(f):
                if not r.get("text", "").strip():
                    continue
                if (r.get("split") or "train").strip() == split:
                    out.append((r["text"].strip(), int(r["label"])))
    return out

train = []
train += load_unsmile("unsmile_train_v1.0.tsv")
train += load_khs("train.tsv")
extra = load_curse() + load_malicious()   # 추가 공개 데이터: 10%는 평가용으로 떼어둠
random.shuffle(extra)
cut = len(extra) // 10
train += extra[cut:]
seed_train = load_seed("train")
train += seed_train * SEED_REPEAT

tests = {
    "UnSmile 검증셋 (온라인 댓글)": load_unsmile("unsmile_valid_v1.0.tsv"),
    "Korean HateSpeech 검증셋 (뉴스 댓글)": load_khs("dev.tsv"),
    "욕설·악성댓글 추가 데이터 평가용 10%": extra[:cut],
    "매장 진상 시나리오 (직접 작성, 학습에 안 씀)": load_seed("test"),
}

X = [clean(t) for t, _ in train]
y = [l for _, l in train]
print(f"학습 문장 {len(X)}개 (공격 {sum(y)} / 일반 {len(y) - sum(y)})")

# ── 2. 학습 ──
vec = TfidfVectorizer(analyzer="char", ngram_range=NGRAM, lowercase=False,
                      max_features=MAX_FEATURES, min_df=2, sublinear_tf=False, norm="l2")
Xv = vec.fit_transform(X)
clf = LogisticRegression(C=4.0, max_iter=3000, class_weight="balanced")
clf.fit(Xv, y)

def proba(texts):
    return clf.predict_proba(vec.transform([clean(t) for t in texts]))[:, 1]

# ── 3. 기준값: 매장 일반 대화를 공격으로 잘못 잡지 않는 선에서 정함 ──
seed_neg = [t for t, l in seed_train if l == 0]
neg_p = sorted(proba(seed_neg))
THRESHOLD = round(max(0.5, neg_p[int(len(neg_p) * 0.95)] + 0.02), 2)
print(f"\n판단 기준값(threshold) = {THRESHOLD}")

# ── 4. 평가 ──
report = {}
for name, data in tests.items():
    texts = [t for t, _ in data]; labels = [l for _, l in data]
    pred = [1 if p >= THRESHOLD else 0 for p in proba(texts)]
    r = dict(n=len(data), acc=accuracy_score(labels, pred), f1=f1_score(labels, pred),
             precision=precision_score(labels, pred), recall=recall_score(labels, pred))
    report[name] = r
    print(f"\n[{name}] 문장 {r['n']}개")
    print(f"  정확도 {r['acc']:.3f} · 정밀도 {r['precision']:.3f} · 재현율 {r['recall']:.3f} · F1 {r['f1']:.3f}")

# 매장 시나리오 하나하나 결과 (발표 자료용)
print("\n[매장 시나리오 문장별 결과]")
seed_test = tests["매장 진상 시나리오 (직접 작성, 학습에 안 씀)"]
for (t, l), p in zip(seed_test, proba([t for t, _ in seed_test])):
    mark = "O" if (p >= THRESHOLD) == (l == 1) else "X"
    print(f"  {mark} {'진상' if l else '일반'} {p:5.2f}  {t}")

# ── 5. 워치용 파일로 저장 ──
# 형식: 첫 줄 = 메타 정보(JSON), 이후 한 줄에 "글자조각 \t idf \t 가중치"
names = vec.get_feature_names_out()
idf = vec.idf_
w = clf.coef_[0]
meta = dict(version=1, ngram_min=NGRAM[0], ngram_max=NGRAM[1], bias=float(clf.intercept_[0]),
            threshold=THRESHOLD, features=len(names))
with open(OUT, "w", encoding="utf-8") as f:
    f.write(json.dumps(meta, ensure_ascii=False) + "\n")
    for n, i, c in zip(names, idf, w):
        f.write(f"{n}\t{i:.5f}\t{c:.5f}\n")
print(f"\n저장: {OUT} ({os.path.getsize(OUT) / 1024:.0f} KB, 글자 조각 {len(names)}개)")

# 가장 "공격적"으로 보는 글자 조각 (설명용)
top = sorted(zip(names, w), key=lambda x: -x[1])[:25]
print("\n공격적으로 보는 글자 조각 상위:", " ".join(n for n, _ in top))

json.dump(report, open("eval_report.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
