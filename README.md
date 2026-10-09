# 오늘출석 · Academy Attendance

학원 선생님이 **반 구분 없이 전체 학생의 출석을 관리**하고, 학부모에게 출석 SMS를 보낼 수 있는 Android 앱입니다.

- 버전: **1.2.0** (`versionCode 3`)
- 한국어 UI, Android 8.0 (API 26) 이상
- 기기 내부 저장 방식으로 인터넷, 계정, 외부 서버 불필요

## 출석 체크 및 자정 초기화

1. **학생 관리 → 학생 등록**에서 학생 이름과 등록일을 입력합니다. 반 생성 또는 반 선택 과정은 없습니다.
2. **출석 체크**에서 학생별로 **출석 / 지각 / 결석 / 공결** 중 하나를 선택합니다. 선택한 상태를 한 번 더 누르면 **미출석**으로 변경됩니다.
3. **미출석 모두 출석** 버튼은 현재 검색 결과에 표시된 미출석 학생만 일괄 출석 처리합니다.
4. 매일 **기기 현지 시간 오전 0시**에 화면이 새 날짜로 전환되며, 활성 학생 전체가 **미출석** 상태로 시작합니다.
5. **어제의 출석 기록은 삭제되지 않습니다.** 날짜 선택, 출석 기록 화면, CSV로 과거 기록을 조회할 수 있습니다.

앱을 켜 둔 상태에서는 다음 자정 시각에 맞춘 타이머로 화면을 전환합니다. 앱이 백그라운드에 있거나 종료된 경우에도 다음에 화면으로 돌아올 때 날짜를 다시 확인하고 새로운 출석부를 표시합니다. Android 절전 정책으로 백그라운드 타이머가 지연될 수 있지만, 날짜별 출결은 독립적으로 보관되기 때문에 과거 상태가 새 날짜에 복사되지 않습니다. 보관 중인 학생은 새로운 출석부에 나타나지 않습니다.

**기존 데이터:** 버전 1.0/1.1에서 저장된 학생·학부모 정보와 과거 출결은 유지합니다. 과거 반 구분은 새로운 화면에서 사용하지 않으며, 구버전 기록과 백업을 읽을 수 있도록 내부 호환성 정보만 보존합니다. 새로 추가한 학생은 반을 선택하지 않습니다.

## 연락처 연동

**학생 관리 → 연락처에서 자동 등록**에서 휴대폰 주소록을 읽어 학생과 학부모 전화번호를 연결할 수 있습니다.

- `김하늘 어머니`, `김하늘아버지`, `(김하늘)어머니`, `김하늘(어머니)`, `김하늘 학생` 같은 이름을 인식합니다.
- 키워드는 설정에서 편집할 수 있으며 동명이인은 사용자가 연결할 기존 학생을 직접 선택합니다.
- 학생 연락처와 최대 6개의 학부모 연락처를 저장합니다.
- 주소록은 요청할 때만 읽습니다. 기존 연락처는 수정하지 않으며, 자동 등록만으로 문자를 발송하지 않습니다.

## 학부모 문자 발송

**설정 → 출석 문자 설정**에서 문자 발송 권한을 허용하고 자동 발송을 활성화할 수 있습니다.

- 기본적으로 오늘 날짜의 **출석 및 지각 상태 변경** 시 수신 동의가 설정된 학부모 번호로 SMS를 요청합니다.
- 문자 문구와 발송 대상 상태는 수정할 수 있습니다. 기본 문구는 아래와 같습니다.
- 메모만 변경하거나 미출석으로 되돌릴 때, 과거 기록을 수정할 때, 백업을 복원할 때는 자동 문자 발송을 요청하지 않습니다.
- 같은 학생·날짜·상태·정규화된 전화번호 조합으로 자동 중복 발송을 하지 않습니다.
- **설정 → 문자 발송 기록**에서 최근 200건의 처리 상태를 확인합니다.
- 기본 SMS용 SIM과 `SEND_SMS` 권한이 필요하며, 통신사 요금제에 따라 추가 문자 요금이 발생할 수 있습니다.

```text
[{학원명}] {학생이름} 학생이 {날짜} {시간}에 {상태} 처리되었습니다.
```

문자 치환 항목: `{학원명}`, `{학생이름}`, `{날짜}`, `{시간}`, `{상태}`, `{선생님}`, `{메모}`.
기존 버전에서 저장한 `{반이름}` 템플릿은 백업 호환성을 위해 해석할 수 있으나, 신규 편집 화면에서는 권장하지 않습니다.

**문자 발송 완료**는 통신망 발송 결과이며 학부모의 실제 수신 또는 읽음을 보증하지 않습니다. 문자가 실패했거나 응답이 불확실할 때 자동 재전송하지 않습니다. 문자를 보낸 뒤 출결을 되돌려도 이미 전송된 문자는 취소할 수 없습니다.

## 출석 기록 및 데이터 관리

- 월별 출석률 = (출석 + 지각) / (출석 + 지각 + 결석)
- 공결과 미출석은 출석률 계산에서 제외합니다.
- 등록일부터 출석부에 표시되며, 보관된 학생의 과거 기록은 유지합니다.
- **설정 → 전체 데이터 백업**: JSON 파일 저장
- **설정 → 백업 파일 복원**: 유효성을 검사하고 동의를 받은 뒤 현재 데이터를 교체합니다. 복원 후 문자 자동 발송 설정은 꺼집니다.
- **출석 기록 → CSV 내보내기**: 현재 월별 기록을 파일로 내보냅니다.

학생 이름과 전화번호가 포함된 JSON 백업은 **암호화되어 있지 않습니다.** 공유하거나 보관할 때 주의하세요. 문자 발송 기록은 JSON 백업에 포함되지 않습니다.

## 기술 구성

```text
app/src/main/assets/        HTML/CSS/JavaScript 화면 및 출석 로직
app/src/main/java/          Android WebView, 연락처, SMS, 파일 접근
app/src/main/res/           테마 및 아이콘
scripts/build_apk.py        APK 컴파일, 정렬, 서명, 검증
tests/core.test.cjs         출석·자정 변경·백업·통계 테스트
tests/contacts-sms.test.cjs 주소록·SMS 규칙 테스트
tests/ui.cjs                브라우저 UI 및 날짜 전환 테스트
tests/contacts-ui.cjs       연락처·SMS 화면 통합 테스트
tests/SmsRulesTest.java     Java 휴대폰 번호와 SMS 규칙 테스트
```

WebView에는 번들 로컬 자산만 로드합니다. 외부 페이지 탐색은 차단하고, HTML 문자열은 이스케이프합니다. 출석 데이터는 앱 전용 디렉터리에 `AtomicFile`로 저장됩니다. 런타임 외부 라이브러리는 없습니다.

## APK 빌드 (PowerShell)

필요 도구: **JDK 17, Python 3.10 이상, Android SDK Platform 35, Build Tools 35.0.0**.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
python .\scripts\build_apk.py
```

APK 출력: `out\AcademyAttendance.apk`

Android Studio를 사용할 경우 Gradle 8.9 / Android Gradle Plugin 8.7.3으로 빌드할 수 있습니다.

**업데이트 주의:** 기기에 설치된 기존 앱의 데이터를 유지하려면 이전 APK와 **동일한 서명 키**를 사용해야 합니다. 신규 설치용 임시 키와 기존 배포 키는 서로 교환할 수 없습니다. 원래 APK의 서명 키를 보관한 상태에서 `--keystore` 옵션으로 재사용하세요.

```powershell
python .\scripts\build_apk.py --keystore "C:\MyPrivateKeys\AcademyAttendance-signing.keystore"
```

## 테스트 (PowerShell)

```powershell
node --test .\tests\core.test.cjs .\tests\contacts-sms.test.cjs
node --check .\app\src\main\assets\core.js
node --check .\app\src\main\assets\app.js
node --check .\app\src\main\assets\contacts-sms.js
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d out\sms-tests .\app\src\main\java\kr\academy\attendance\SmsRules.java .\tests\SmsRulesTest.java
java -cp out\sms-tests SmsRulesTest
```

선택적 UI 테스트:

```powershell
npm install --no-save playwright
npx playwright install chromium
node .\tests\ui.cjs
node .\tests\contacts-ui.cjs
```

실제 Android 기기에서의 연락처 권한, 통신사 SMS 발송, 배터리 절전 상황은 별도 실기기 검증이 필요합니다.
