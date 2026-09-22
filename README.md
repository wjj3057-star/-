# IMAX Cam

일반 스마트폰 카메라로 **IMAX 화면비(1.43:1 / 1.90:1)** 영상을 **UHD HDR10+** 로 녹화하는
Android 카메라 앱입니다. 촬영 경로 전체가 지연시간을 기준으로 설계되어 있습니다.

---

## 무엇을 하는가

| 항목 | 내용 |
|---|---|
| 화면비 | **1.43:1** (IMAX 15/70 풀프레임), **1.90:1** (IMAX Digital/Laser), 2.39:1, 16:9 |
| 해상도 | UHD 소스에서 크롭 — 1.43:1 → **3088×2160**, 1.90:1 → **3840×2022** |
| 코덱 | HEVC Main 10 (HDR10+ 프로파일), MP4 |
| 색공간 | BT.2020 / PQ (ST 2084), 10-bit |
| 동적 메타데이터 | SMPTE **ST 2094-40** (HDR10+) 프레임 단위 |
| 오디오 | AAC-LC 48 kHz 스테레오 |

화면비는 센서 프레임을 GPU 텍스처 좌표로 잘라내어 만듭니다. 중간 버퍼도, 복사도 없기 때문에
어떤 화면비든 촬영 비용이 동일합니다.

### 왜 1.43:1이 3088×2160인가

UHD(3840×2160)에서 높이를 온전히 쓰고 좌우를 잘라내면 `2160 × 1.43 = 3088.8` → 3088px입니다.
실제 화면비는 1.4296:1 (오차 0.03%)이고, 3088은 16의 배수라 하드웨어 인코더에도 최적입니다.
1.90:1은 가로를 온전히 쓰고 위아래를 잘라 3840×2022 (1.8991:1, 오차 0.05%)가 됩니다.

---

## 지연시간을 줄이기 위해 한 일

이 앱의 구조는 전부 이 목표에서 나왔습니다.

**파이프라인**
- **카메라 스트림은 하나뿐입니다.** 프리뷰와 녹화 프레임을 같은 `SurfaceTexture` 하나에서
  GPU로 그립니다. 스트림이 늘어날수록 ISP 대역폭과 큐잉 지연이 늘어납니다.
- **프레임이 도착하는 즉시 그립니다.** VSYNC 틱이 아니라 `onFrameAvailable` 콜백에서
  렌더링합니다. 디스플레이 리프레시 1회분을 아낍니다.
- **인코더 서피스를 프리뷰보다 먼저 그리고 먼저 swap합니다.** 파이프라인 어딘가가
  막히더라도 기다리는 쪽이 녹화가 되지 않도록.
- **앱 메모리로 프레임이 복사되는 지점이 없습니다.** 카메라 버퍼를 GPU가 직접 샘플링해서
  인코더 버퍼에 바로 씁니다.
- 유일한 read-back인 HDR10+ 히스토그램은 **PBO + fence 비동기**라 렌더 스레드를 멈추지
  않습니다 (통계만 1프레임 뒤처짐).

**카메라 (Camera2)**
- `CONTROL_AE_TARGET_FPS_RANGE`를 고정 — 저조도에서 AE가 프레임레이트를 절반으로 떨어뜨려
  프레임 간격과 지연이 같이 늘어나는 것을 막습니다.
- 저지연 모드에서 ZSL, 전자식 손떨림 보정, 고품질 노이즈 리덕션/샤프닝을 끕니다.
  전부 프레임을 큐에 쌓아 두는 방식으로 동작하므로, 화질을 지연으로 사는 기능들입니다.
- `streamUseCase = VIDEO_RECORD` — 대부분의 기기에서 범용 프리뷰 경로보다 짧은 ISP 경로를
  선택합니다.
- 렌즈 셰이딩 맵 / 얼굴 검출 통계 off.

**인코더 (MediaCodec)**
- **B-프레임 0개** (`KEY_MAX_B_FRAMES=0`). B-프레임은 뒤 프레임이 올 때까지 출력을 붙잡기
  때문에 인코더 지연의 가장 큰 원인입니다. 0이면 출력 순서 = 입력 순서입니다.
- `KEY_LATENCY=1`, `KEY_PRIORITY=0` (realtime), `KEY_OPERATING_RATE=fps`.
- Surface 입력이므로 GPU → 인코더가 zero-copy입니다.

**스레드 / 스케줄링**
- 렌더 스레드는 `THREAD_PRIORITY_URGENT_DISPLAY`.
- `PerformanceHintManager`로 프레임 단위 데드라인을 스케줄러에 알려, 렌더 스레드가 프레임
  도중에 little 코어로 내려가지 않게 합니다.
- 오디오는 `UNPROCESSED` 소스(없으면 CAMCORDER → MIC)에 드라이버 최소 버퍼의 2배만 사용.

**측정**
- HUD에 실제 지연이 표시됩니다: 센서 타임스탬프와 GPU가 인코더에 프레임을 넘긴 시각의 차이
  (p50 / p95). 둘 다 같은 monotonic 클럭이라 추정이 아닌 실측값입니다.

---

## HDR10+ 동적 메타데이터에 대해 (중요)

**설계상의 트레이드오프를 분명히 해 둡니다.**

카메라가 `HDR10_PLUS` 프로파일로 내보내는 프레임에는 벤더가 만든 ST 2094-40 메타데이터가
붙어 있습니다. 하지만 그 메타데이터는 **GPU 크롭 패스를 통과하지 못합니다** — 셰이더가
만들어낸 새 버퍼에는 원본의 메타데이터가 따라오지 않기 때문입니다. 그리고 Camera2 공개
API로는 IMAX 화면비로 자른 프레임을 인코더에 직접 넘길 방법이 없습니다
(`SCALER_CROP_REGION`은 FOV만 바꿀 뿐 출력 화면비를 바꾸지 못합니다).

그래서 이 앱은 **메타데이터를 직접 생성합니다**:

1. 크롭된 프레임을 GPU에서 128×N PQ 그리드로 축소 (4탭 max — 작은 하이라이트가 평균에
   묻히지 않도록)
2. PBO로 비동기 read-back
3. CPU에서 `maxscl[3]`, `average_maxrgb`, 9개 백분위 분포, `fraction_bright_pixels` 계산
4. 장면 밝기를 따라가는 knee와 Bezier 숄더로 톤매핑 커브 생성
5. `user_data_registered_itu_t_t35()` 페이로드로 직렬화해
   `MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO`로 프레임에 부착

결과물은 규격에 맞는 HDR10+ 스트림이고 HDR10+ 디스플레이가 장면별로 톤매핑합니다. 다만
커브는 벤더 ISP의 튜닝이 아니라 이 앱이 프레임 통계로 만든 것입니다. 분석 패스는 4프레임마다
한 번만 돌립니다 — 톤 커브는 장면 밝기를 따라가고, 장면 밝기는 인접 프레임 사이에서 의미
있게 변하지 않습니다.

---

## 기기 지원과 폴백

최소 **Android 13 (API 33)** — Camera2의 10-bit 동적 범위 프로파일이 이때 들어왔습니다.

기기가 HDR10+를 못 하면 자동으로 아래로 내려갑니다. 화면비 크롭은 어느 단계에서도 동일하게
동작합니다.

```
HDR10+  →  HDR10  →  HLG10  →  SDR
```

- 카메라가 `DynamicRangeProfiles.HDR10_PLUS`를 지원하지 않으면 지원하는 최상위 모드로 시작
- 인코더가 HDR10+ 프로파일 configure를 거부하면 Main10 HDR10으로 재시도 (10-bit PQ 화면과
  정적 메타데이터는 유지되고, 프레임 단위 커브만 빠집니다)
- EGL이 `BT2020_PQ` 색공간을 주지 않으면 프리뷰만 셰이더에서 Rec.709로 톤매핑 —
  **파일에 기록되는 내용은 영향을 받지 않습니다**
- 마이크가 없으면 비디오 트랙만으로 녹화

HUD 첫 줄에 실제로 무엇이 기록되고 있는지 표시됩니다 (`HDR10+ · dynamic` 등).

---

## 빌드

```bash
git clone <repo> && cd <repo>
# Android Studio로 열거나
./gradlew :app:assembleDebug
```

Gradle wrapper JAR은 저장소에 포함되어 있지 않습니다. Android Studio는 자동으로 생성하고,
CLI만 쓴다면 한 번:

```bash
gradle wrapper --gradle-version 8.9
```

요구사항: Android SDK 35, JDK 17 이상, `minSdk 33` 이상 실기기.
10-bit 카메라 출력과 HEVC Main10 인코딩은 에뮬레이터에서 동작하지 않습니다.

### 외부 의존성 없음

`minSdk 33`에서는 AppCompat / Material Components / ConstraintLayout / ViewBinding이
백포트할 것이 남아 있지 않습니다. UI는 전부 프레임워크 API(`Activity`,
`WindowInsetsController`, `FrameLayout`, `findViewById`)로 되어 있고 런타임 의존성이
0개입니다. AppCompat은 모든 뷰 인플레이션을 가로채 AppCompat 위젯으로 바꾸는데, 오버레이가
12개뿐인 저지연 카메라 앱에서는 비용만 남습니다. APK는 **669 KB**입니다.

녹화 파일은 `Movies/IMAXCam/`에 저장됩니다:
`IMAX_1.43-1_3088x2160_HDR10+_20260922_154312.mp4`

---

## 구조

```
core/      ImaxFormat, CropCalc      화면비 -> 정렬된 출력 크기, 비트레이트 산정
           CropMatrix                크롭/회전/미러 -> 4x4 텍스처 행렬 (프레임워크 비의존)
           LatencyMonitor            실측 지연 통계
camera/    CameraCapabilities        10-bit 프로파일/크기/FPS 탐지
           CameraController          Camera2 세션, 저지연 튜닝
gl/        EglCore                   10-bit + BT.2020 PQ EGL 컨텍스트
           CropRenderer              단일 드로우콜 블릿 (passthrough / tonemap / histogram)
           FrameAnalyzer             PBO 비동기 휘도 분석
           Shaders                   GLSL ES 3.00
hdr/       Pq                        ST 2084 전달함수
           BitWriter, Hdr10Plus      ST 2094-40 직렬화 + 톤 커브 생성
           HdrStaticInfo             CTA-861.3 정적 메타데이터
record/    VideoEncoder              HEVC Main10 HDR10+, Surface 입력
           AudioEncoder              AAC-LC
           MuxerGate                 두 트랙 동기화 먹싱
           OutputFile                MediaStore pending 엔트리
pipeline/  CaptureEngine             전체 파이프라인 + 스레드 수명주기
```

---

## 검증 상태

이 저장소에서 실제로 실행한 것과 하지 못한 것을 구분해 둡니다.

**실행함**
- 유닛 테스트 18개 통과 — 크롭 계산, 크롭 행렬(코너 매핑/회전/미러/축 교환), PQ 전달함수
  왕복, ST 2094-40 페이로드 헤더와 비트 길이(64바이트), 톤 커브 단조성
- `MainActivity.kt`를 제외한 전체 Kotlin 소스가 실제 Android 프레임워크 클래스를 상대로
  경고 없이 컴파일됨 (54 클래스)

- **서명된 디버그 APK 빌드 완료** — aapt2로 리소스 링크, 전체 Kotlin 20개 파일 컴파일,
  D8 dexing, APK Signature Scheme v3 서명까지. `apksigner verify` 통과, dex에 androidx
  참조 0건, `findViewById` ID 11개 전부 R에 존재.

**실행하지 못함**
- **실기기 동작 확인** — 카메라, GL, 코덱 경로는 실제 하드웨어에서만 검증할 수 있습니다.
  특히 기기별로 확인이 필요한 부분: HDR10+ 프로파일의 세션 구성 성공 여부, 인코더가
  3088×2160 / 3840×2022 같은 비표준 크기를 받는지, EGL `BT2020_PQ` 색공간 지원 여부.

먼저 실기기에서 한 번 돌려 보시고, HUD에 표시되는 모드와 지연 수치를 확인해 보시면 됩니다.
