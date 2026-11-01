# Gallio — 폭언이 시작되면, 손목이 먼저 녹음합니다

> 매장 CCTV는 소리를 남기지 못합니다.
> Gallio는 손님을 응대하는 근무자가 갤럭시 워치에서 **근무 시작**만 누르면, 말·목소리·심박을 함께 보고 **폭언이 시작될 때만 자동으로 녹음**하는 앱입니다.

- 시연 영상: https://www.youtube.com/shorts/BbJxchGW01U https://www.youtube.com/shorts/qNGmVTSTdOU

---

## 주요 기능

| 기능 | 설명 |
|---|---|
| 자동 녹음 | 위험 점수가 기준(50점)을 넘으면 녹음 시작, 직전 5초 소리까지 함께 저장 |
| 위험 점수 | 언어 65% · 고함 10% · 지속 5% · 심박 20%를 0.1초마다 합산 |
| 말 인식 | 폰이 인터넷 없이 한국어로 받아쓰기 → 욕설·막말·위협 단어 + 문장 AI로 판단 |
| 긴급 녹음 | 버튼 한 번으로 즉시 최소 60초 녹음 |
| 증거 보존 | SHA-256 파일 지문으로 원본 확인, 폰으로 자동 백업 후 재검증 |
| 보호자 알림 | 녹음이 시작되면 보호자에게 시각 + 위치 링크 문자 (선택) |
| 사생활 보호 | 위험할 때만 저장, 모든 처리는 기기 안에서, 7일 뒤 자동 삭제 |

## 화면

| 상태 | 화면 |
|---|---|
| 보호 중 (30점 미만) | 파란 링 · "주변 소리를 듣고 있어요" |
| 주의 (30점 이상) | 주황 링 · "분위기가 험해지고 있어요" |
| 녹음 중 (50점 이상) | 빨간 링 · 깜빡이는 점 · 경과 시간 · 진동 |

점수 화면에서는 신호별 점수(예: 언어 33 / 65점)와 폰이 방금 받아쓴 말을 실시간으로 볼 수 있습니다.

---

## 시스템 구성

```
┌──────────── 갤럭시 워치 (Wear OS) ────────────┐        ┌────────── 휴대폰 (Android) ──────────┐
│ 마이크 16kHz ─┐                                │  소리  │                                      │
│ 고함 AI(YAMNet)├─▶ 위험 점수 계산 ─▶ 자동 녹음 ─┼──────▶ │ 한국어 받아쓰기 (기기 안, 인터넷 X) │
│ 심박 센서 ────┘        ▲              │ SHA-256│ ◀──────┼─ 받아쓴 문장                        │
│                        └── 받아쓴 문장 │        │  파일  │                                      │
│                                        ├────────┼──────▶ │ 백업 저장 · 지문 재검증             │
│ 화면 · 진동 ◀──────────────────────────┘        │  알림  │                                      │
│                                                 ├──────▶ │ 알림 · 보호자 위치 문자             │
└─────────────────────────────────────────────────┘        └──────────────────────────────────────┘
                     블루투스 (Google Wearable Data Layer) — 인터넷 사용 안 함
```

| 통신 | 방향 | 내용 |
|---|---|---|
| ChannelClient | 워치 → 폰 | 마이크 소리 실시간 스트림 |
| MessageClient | 폰 → 워치 | 받아쓴 문장 (`P|` 말하는 중, `F|` 문장 끝, `E|` 오류) |
| MessageClient | 워치 → 폰 | 녹음 시작 알림 + 감지 이유 |
| DataClient | 워치 → 폰 | 녹음 파일 + 증거 정보 백업 |

---

## 위험 점수

```
위험 점수 = 0.65 × 언어 + 0.10 × 고함 + 0.05 × 지속 + 0.20 × 심박     (0 ~ 100점)
```

| 신호 | 가중치 | 계산 방법 |
|---|---|---|
| 언어 | 65% | 단어 사전 점수와 문장 AI 점수 중 높은 쪽 (최근 30초) |
| 고함 | 10% | (주변 소음 대비 데시벨 상승 + YAMNet 고함 확률) / 2 |
| 지속 | 5% | 최근 30초 중 거친 소리였던 비율 (15초 이상이면 만점) |
| 심박 | 20% | 최근 10초 동안 오른 폭 (+25bpm이면 만점) |

**단어 사전**

| 종류 | 예 | 1번 | 2번 이상 |
|---|---|---|---|
| 욕설 | 씨발, 병신, 존나 (+ 받아쓰기 변형) | 47점 | 65점 (녹음) |
| 막말·갑질 | 사장 나와, 일머리, 알바 주제, 니가 뭔데 | 욕설과 같이 셈 | |
| 위협 | 죽여, 가만 안 둬, 찾아간다 | 49점 | 65점 (녹음) |
| 불만 | 눈 없어, 어떻게 들고, 답답하네 | 32점 (주의까지만) | |

**녹음 규칙**: 50점 이상이 0.5초 이어지면 시작 · 직전 5초 포함 · 25점 미만 10초면 종료 · 최대 120초

---

## 문장 AI (직접 학습)

욕이 없는 갑질("사장 나오라 그래", "너 몇 살이야")을 잡기 위한 모델입니다.

- **모델**: 글자 1~3개 조각(n-gram) TF-IDF + 로지스틱 회귀, 조각 60,000개, 파일 1.4MB
- **워치 추론**: 라이브러리 없이 Kotlin으로 직접 계산 (Python 결과와 차이 0.000002 이하)
- **학습 데이터**: 약 4만 2천 줄
  - [UnSmile](https://github.com/smilegate-ai/korean_unsmile_dataset) (CC BY-NC-ND 4.0)
  - [Korean HateSpeech](https://github.com/kocohub/korean-hate-speech) (CC BY-SA 4.0)
  - [Curse-detection-data](https://github.com/2runo/Curse-detection-data) (MIT)
  - [korean-malicious-comments-dataset](https://github.com/ZIZUN/korean-malicious-comments-dataset) (MIT)
  - 팀이 직접 작성한 매장 진상 문장 (학습 346개, 평가 60개)

| 평가 데이터 (학습에 사용 안 함) | 문장 수 | 정확도 |
|---|---|---|
| 매장 진상 시나리오 (직접 작성) | 60 | 88% (정밀도 93%) |
| 욕설·악성댓글 평가용 10% | 1,580 | 89% |
| UnSmile 검증셋 | 3,737 | 83% |
| Korean HateSpeech 검증셋 | 471 | 72% |

※ 위 정확도는 판단 기준 50%에서 측정했습니다. 앱은 더 민감하게 40%를 사용하고, 문장 AI만으로는 '주의'(최대 32점)까지만 올립니다.

학습 재현: `ml/jinsang_text_ai.ipynb`를 Google Colab에서 열고 순서대로 실행합니다.

---

## 폴더 구조

```
Gallio/
├── app/                              # 워치 앱 (Wear OS)
│   └── src/main/
│       ├── java/com/example/gallio/presentation/
│       │   ├── RecordingService.kt   # 감지·점수·녹음 (포그라운드 서비스)
│       │   ├── KeywordSpotter.kt     # 욕설·막말·위협·불만 단어 사전
│       │   ├── TextAbuseModel.kt     # 문장 AI 추론
│       │   ├── PhoneLink.kt          # 폰 연결 (소리 전송, 문장 수신, 백업)
│       │   ├── RecordingStore.kt     # 녹음 저장·목록·SHA-256
│       │   └── MainActivity.kt       # 화면 (메인·점수·목록·상세)
│       └── assets/
│           ├── yamnet.tflite         # 소리 분류 AI
│           └── text_model.tsv        # 문장 AI 모델
├── mobile/                           # 폰 앱 (Android)
│   └── src/main/java/com/example/mobile/
│       ├── WatchListenerService.kt   # 워치 알림·백업·소리 수신 + 한국어 받아쓰기
│       ├── KeepAliveService.kt       # 연결 유지 (앱을 닫아도 받아쓰기)
│       └── MainActivity.kt           # 보호자 설정·백업 목록·연결 상태
└── ml/                               # 문장 AI 학습
    ├── train_text_model.py
    ├── jinsang_seed.csv
    ├── team_jinsang_template.csv
    └── jinsang_text_ai.ipynb
```

---

## 개발 환경

| 항목 | 내용 |
|---|---|
| 언어 · UI | Kotlin, Jetpack Compose |
| 개발 도구 | Android Studio |
| 워치 | Galaxy Watch7 (SM-L310), Wear OS |
| 폰 | Galaxy S26, Android |
| 연결 | Google Play Services Wearable (MessageClient · DataClient · ChannelClient) |
| 소리 AI | YAMNet (TensorFlow Lite) + MediaPipe Audio |
| 문장 AI 학습 | Python, scikit-learn, Google Colab |

## 설치 방법

1. Android Studio에서 이 프로젝트를 엽니다.
2. 갤럭시 워치와 폰을 Galaxy Wearable 앱으로 페어링합니다.
3. 폰: 실행 대상 `mobile` → 폰 선택 → ▶ 실행
4. 워치: 개발자 옵션에서 무선 디버깅 켜기 → `adb pair` / `adb connect` → 실행 대상 `app` → 워치 선택 → ▶ 실행
5. 폰 앱에서 **① 권한 받기**(마이크·알림·문자·위치)와 **배터리 제한 풀기**를 누르고, "연결 유지: 켜짐"을 확인합니다.
6. 워치에서 **근무 시작**을 누릅니다.

> 두 앱은 같은 applicationId와 같은 서명이어야 워치–폰 통신이 됩니다.

## 한계와 향후 계획

- 돌려 말하는 협박은 문장 AI가 아직 약함 → 매장 문장 데이터 추가 수집
- 실제 매장 소음 환경에서 기준값 검증 필요
- 녹음의 법적 효력은 상황에 따라 다를 수 있음

## 팀

[GALLIO] · [남윤성.이도현.이승용.정지윤] · [모커톤 트랙 1] 2026
